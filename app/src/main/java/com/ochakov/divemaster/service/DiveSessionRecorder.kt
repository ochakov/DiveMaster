package com.ochakov.divemaster.service

import com.ochakov.divemaster.data.db.DiveDao
import com.ochakov.divemaster.data.db.DiveEntity
import com.ochakov.divemaster.data.db.SampleEntity
import com.ochakov.divemaster.data.db.TissueStateEntity
import com.ochakov.divemaster.data.settings.DiveSettings
import com.ochakov.divemaster.deco.Buhlmann
import com.ochakov.divemaster.deco.DepthConverter
import com.ochakov.divemaster.deco.Oxygen
import com.ochakov.divemaster.deco.TissueState
import com.ochakov.divemaster.deco.ZhL16c
import com.ochakov.divemaster.engine.DiveEngine
import com.ochakov.divemaster.engine.DiveStats
import com.ochakov.divemaster.engine.EngineEvent
import kotlin.math.roundToInt

/**
 * Maps engine events to Room, persists tissue/CNS state (crash-safe, every
 * 15 s), and finalizes dives that were left open by a previous crash.
 */
class DiveSessionRecorder(
    private val dao: DiveDao,
    private var settings: DiveSettings,
    /** versionName of this build, stamped on every dive. */
    private val appVersion: String = "?",
) {
    data class Restored(val tissue: TissueState, val cnsFraction: Double)

    /** Called by the service on surface-time settings reloads; affects future dive rows only. */
    fun updateSettings(newSettings: DiveSettings) {
        settings = newSettings
    }

    private var currentDive: DiveEntity? = null
    private var lastTissuePersistMs = 0L

    /** The dive that just ended — the exit fix usually lands after the 60 s end hold. */
    private var lastEndedDiveId: Long? = null

    /** Finalize orphans, then rebuild tissue state with surface off-gassing for the downtime. */
    suspend fun restore(): Restored {
        finalizeOrphans()
        val saturated = TissueState.saturatedAir(DepthConverter.STANDARD_ATMOSPHERE_BAR)
        val row = dao.tissueState() ?: return Restored(saturated, 0.0)
        val loads = row.n2BarCsv.split(',').mapNotNull { it.toDoubleOrNull() }
        if (loads.size != ZhL16c.SIZE) return Restored(saturated, 0.0)
        val elapsedSec = ((System.currentTimeMillis() - row.updatedEpochMs) / 1000.0).coerceAtLeast(0.0)
        return Restored(
            Buhlmann.surfaceInterval(
                TissueState(loads.toDoubleArray()),
                DepthConverter.STANDARD_ATMOSPHERE_BAR,
                elapsedSec,
            ),
            Oxygen.surfaceDecay(row.cnsFraction, elapsedSec),
        )
    }

    private suspend fun finalizeOrphans() {
        for (open in dao.openDives()) {
            val samples = dao.samplesFor(open.id)
            if (samples.size < 60) {
                dao.deleteDive(open.id)
                continue
            }
            val depths = samples.map { it.depthM }
            val stats = DiveStats.fromSamples(depths, samples.map { it.tempC })
            dao.updateDive(
                open.copy(
                    endEpochMs = open.startEpochMs + samples.last().tOffsetSec * 1000L,
                    maxDepthM = stats.maxDepthM,
                    avgDepthM = stats.avgDepthM,
                    minTempC = stats.minTempC,
                    maxAscentRateMPerMin = stats.maxAscentRateMPerMin,
                    safetyStopResult = DiveStats.safetyStopResult(
                        depths,
                        settings.safetyStopMinutes * 60,
                        settings.safetyStopMinDepthM,
                        settings.safetyStopMaxDepthM,
                    ).name,
                    // Battery at end and CNS are unknown for a dive the process died in.
                ),
            )
        }
    }

    /**
     * [batteryPct] is stamped on dive start/end; [entryLocation] is asked for
     * the last known position only when a dive actually starts.
     */
    suspend fun handle(
        events: List<EngineEvent>,
        engine: DiveEngine,
        nowMs: Long,
        batteryPct: Int? = null,
        entryLocation: () -> LocationFix? = { null },
    ) {
        for (event in events) {
            when (event) {
                is EngineEvent.DiveStarted -> {
                    val entry = entryLocation()
                    val dive = DiveEntity(
                        startEpochMs = event.startEpochMs,
                        endEpochMs = 0,
                        maxDepthM = 0.0,
                        avgDepthM = 0.0,
                        minTempC = null,
                        gasO2Fraction = settings.o2Fraction,
                        waterType = settings.waterType.name,
                        surfacePressureMbar = event.surfacePressureBar * 1000.0,
                        gfLow = (settings.gradientFactors.low * 100).roundToInt(),
                        gfHigh = (settings.gradientFactors.high * 100).roundToInt(),
                        startedUnderwater = event.startedUnderwater,
                        batteryStartPct = batteryPct,
                        appVersion = appVersion,
                        entryLat = entry?.lat,
                        entryLon = entry?.lon,
                        entryAccuracyM = entry?.accuracyM,
                        entryFixEpochMs = entry?.epochMs,
                    )
                    currentDive = dive.copy(id = dao.insertDive(dive))
                    lastEndedDiveId = null
                }

                is EngineEvent.SurfaceReferenceCorrected -> currentDive?.let { dive ->
                    // The engine found its frozen reference too high (or, for a
                    // guessed one, too low): re-base every sample written so far
                    // and record the corrected surface pressure.
                    dao.shiftSampleDepths(dive.id, event.depthShiftM)
                    val corrected = dive.copy(surfacePressureMbar = event.surfacePressureBar * 1000.0)
                    dao.updateDive(corrected)
                    currentDive = corrected
                }

                is EngineEvent.SampleRecorded -> currentDive?.let { dive ->
                    dao.insertSamples(
                        listOf(
                            SampleEntity(
                                diveId = dive.id,
                                tOffsetSec = event.tOffsetSec,
                                depthM = event.depthM,
                                tempC = event.tempC,
                                ndlMin = event.ndlMin,
                            ),
                        ),
                    )
                }

                is EngineEvent.DiveEnded -> currentDive?.let { dive ->
                    dao.trimSamplesAfter(dive.id, event.durationSec)
                    dao.updateDive(
                        dive.copy(
                            endEpochMs = event.endEpochMs,
                            maxDepthM = event.maxDepthM,
                            avgDepthM = event.avgDepthM,
                            minTempC = event.minTempC,
                            batteryEndPct = batteryPct,
                            safetyStopResult = event.safetyStopResult.name,
                            maxAscentRateMPerMin = event.maxAscentRateMPerMin,
                            cnsEndFraction = event.cnsFractionAtEnd,
                        ),
                    )
                    currentDive = null
                    lastEndedDiveId = dive.id
                    persistTissueNow(engine, nowMs)
                }

                EngineEvent.DiveDiscarded -> currentDive?.let { dive ->
                    dao.deleteDive(dive.id)
                    currentDive = null
                    lastEndedDiveId = null
                }
            }
        }
        if (nowMs - lastTissuePersistMs >= TISSUE_PERSIST_INTERVAL_MS) persistTissueNow(engine, nowMs)
    }

    /**
     * Attaches the exit fix to the dive in progress or — the fix often lands
     * after the 60 s end hold — to the dive that just ended. Returns the id of
     * an already-finalized dive that now needs re-publishing, else null.
     */
    suspend fun recordExitLocation(fix: LocationFix): Long? {
        val open = currentDive
        if (open != null) {
            val updated = open.copy(
                exitLat = fix.lat, exitLon = fix.lon, exitAccuracyM = fix.accuracyM, exitFixEpochMs = fix.epochMs,
            )
            dao.updateDive(updated)
            currentDive = updated
            return null
        }
        val ended = lastEndedDiveId ?: return null
        dao.updateExitLocation(ended, fix.lat, fix.lon, fix.accuracyM, fix.epochMs)
        return ended
    }

    suspend fun persistTissueNow(engine: DiveEngine, nowMs: Long = System.currentTimeMillis()) {
        lastTissuePersistMs = nowMs
        dao.upsertTissueState(
            TissueStateEntity(
                updatedEpochMs = nowMs,
                n2BarCsv = engine.tissue.n2Bar.joinToString(","),
                cnsFraction = engine.cnsFraction,
            ),
        )
    }

    private companion object {
        const val TISSUE_PERSIST_INTERVAL_MS = 15_000L
    }
}
