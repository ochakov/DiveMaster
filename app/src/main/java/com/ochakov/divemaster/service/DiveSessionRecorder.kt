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
    private val settings: DiveSettings,
) {
    data class Restored(val tissue: TissueState, val cnsFraction: Double)

    private var currentDive: DiveEntity? = null
    private var lastTissuePersistMs = 0L

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
            val stats = DiveStats.fromSamples(samples.map { it.depthM }, samples.map { it.tempC })
            dao.updateDive(
                open.copy(
                    endEpochMs = open.startEpochMs + samples.last().tOffsetSec * 1000L,
                    maxDepthM = stats.maxDepthM,
                    avgDepthM = stats.avgDepthM,
                    minTempC = stats.minTempC,
                ),
            )
        }
    }

    suspend fun handle(events: List<EngineEvent>, engine: DiveEngine, nowMs: Long) {
        for (event in events) {
            when (event) {
                is EngineEvent.DiveStarted -> {
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
                    )
                    currentDive = dive.copy(id = dao.insertDive(dive))
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
                        ),
                    )
                    currentDive = null
                    persistTissueNow(engine, nowMs)
                }

                EngineEvent.DiveDiscarded -> currentDive?.let { dive ->
                    dao.deleteDive(dive.id)
                    currentDive = null
                }
            }
        }
        if (nowMs - lastTissuePersistMs >= TISSUE_PERSIST_INTERVAL_MS) persistTissueNow(engine, nowMs)
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
