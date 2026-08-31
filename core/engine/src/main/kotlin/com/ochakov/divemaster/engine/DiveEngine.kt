package com.ochakov.divemaster.engine

import com.ochakov.divemaster.deco.Buhlmann
import com.ochakov.divemaster.deco.DepthConverter
import com.ochakov.divemaster.deco.Gas
import com.ochakov.divemaster.deco.Oxygen
import com.ochakov.divemaster.deco.TissueState
import kotlin.math.pow

/**
 * The dive computer's heart: a deterministic reducer fed 1 Hz pressure
 * samples. It owns the surface-pressure reference, the dive-detection state
 * machine (backdated start, merge-across-brief-surfacing, minimum-duration
 * discard), tissue and CNS physiology, and the live display state.
 *
 * All timing derives from sample timestamps, never a wall clock, so the
 * engine is fully unit-testable and works identically with the simulator.
 * Not thread-safe: feed it from a single thread/coroutine.
 */
class DiveEngine(
    private val config: DiveEngineConfig,
    initialTissue: TissueState,
    initialCnsFraction: Double = 0.0,
    initialSurfaceBar: Double? = null,
) {
    private val converter = DepthConverter(config.waterType)

    var tissue: TissueState = initialTissue
        private set
    var cnsFraction: Double = initialCnsFraction
        private set
    var displayState: DiveDisplayState = DiveDisplayState(
        gasO2Fraction = config.gas.o2Fraction,
        surfacePressureBar = initialSurfaceBar ?: DepthConverter.STANDARD_ATMOSPHERE_BAR,
    )
        private set

    private var phase = DivePhase.SURFACE
    private var lastTsMs: Long? = null
    private var surfaceEmaBar: Double? = initialSurfaceBar
    private var frozenSurfaceBar: Double = initialSurfaceBar ?: DepthConverter.STANDARD_ATMOSPHERE_BAR

    // Start detection: continuous submersion tracking plus threshold hold.
    private var submergedSinceMs: Long? = null
    private var deepSinceMs: Long? = null

    private data class Pending(val tsMs: Long, val depthM: Double, val tempC: Double?)

    private val pending = ArrayDeque<Pending>()

    // Active dive accumulators.
    private var diveStartMs = 0L
    private var maxDepthM = 0.0
    private var depthTimeSum = 0.0
    private var depthTimeDt = 0.0
    private var minTempC: Double? = null
    private var shallowSinceMs: Long? = null

    // Vertical-rate window.
    private data class DepthAt(val tsMs: Long, val depthM: Double)

    private val rateWindow = ArrayDeque<DepthAt>()

    fun onSample(sample: PressureSample): List<EngineEvent> {
        val events = mutableListOf<EngineEvent>()
        val ts = sample.timestampMs
        val p = sample.pressureBar
        // Clock skew (e.g., a simulated dive ran timestamps ahead of the wall
        // clock): accept the sample but advance no physiology time.
        val dtSec = (lastTsMs?.let { (ts - it) / 1000.0 } ?: 0.0)
            .coerceIn(0.0, config.maxSampleGapSec)
        lastTsMs = ts

        // Surface reference: EMA while near the surface, frozen during a dive.
        if (phase == DivePhase.SURFACE) {
            val ref = surfaceEmaBar
            if (ref == null) {
                surfaceEmaBar = p
            } else if (dtSec > 0.0 && converter.depthMeters(p, ref) < 1.0) {
                val alpha = 1.0 - 2.0.pow(-dtSec / config.surfaceEmaHalfLifeSec)
                surfaceEmaBar = ref + (p - ref) * alpha
            }
        }
        val surfaceBar = if (phase == DivePhase.DIVING) frozenSurfaceBar else (surfaceEmaBar ?: p)
        val depth = converter.depthMeters(p, surfaceBar)

        // Physiology always tracks true ambient pressure, dive or not — that is
        // what makes the backdated start exact with no retroactive correction.
        val breathing = if (depth > config.gasSwitchDepthM) config.gas else Gas.AIR
        if (dtSec > 0.0) {
            tissue = Buhlmann.loadConstant(tissue, p, breathing, dtSec)
            val ppO2Now = Oxygen.ppO2Bar(p, breathing)
            cnsFraction = if (ppO2Now >= 0.5) {
                Oxygen.addExposure(cnsFraction, ppO2Now, dtSec)
            } else {
                Oxygen.surfaceDecay(cnsFraction, dtSec)
            }
        }

        // Vertical speed over a short trailing window (positive = ascending).
        rateWindow.addLast(DepthAt(ts, depth))
        while (rateWindow.size > 1 && ts - rateWindow.first().tsMs > (config.rateWindowSec * 1000).toLong()) {
            rateWindow.removeFirst()
        }
        val oldest = rateWindow.first()
        val spanSec = (ts - oldest.tsMs) / 1000.0
        val rate = if (spanSec >= 3.0) (oldest.depthM - depth) / spanSec * 60.0 else 0.0

        val ndlSec = Buhlmann.ndlSeconds(tissue, p, surfaceBar, config.gas, config.gradientFactors.high)

        when (phase) {
            DivePhase.SURFACE -> handleSurface(ts, depth, sample.tempC, events)
            DivePhase.DIVING -> handleDiving(ts, depth, sample.tempC, dtSec, ndlSec, events)
        }

        val ceilingBar = Buhlmann.toleratedAmbientBar(tissue, config.gradientFactors.low)
        displayState = DiveDisplayState(
            phase = phase,
            depthM = depth.coerceAtLeast(0.0),
            maxDepthM = maxDepthM,
            durationSec = if (phase == DivePhase.DIVING) (ts - diveStartMs) / 1000 else 0,
            ndlMin = ndlSec / 60.0,
            ceilingM = converter.depthMeters(ceilingBar, surfaceBar).coerceAtLeast(0.0),
            tempC = sample.tempC,
            verticalRateMPerMin = rate,
            ppO2Bar = Oxygen.ppO2Bar(p, breathing),
            cnsFraction = cnsFraction,
            gasO2Fraction = config.gas.o2Fraction,
            surfacePressureBar = surfaceBar,
        )
        return events
    }

    /** Force-end the current dive (simulator stopped, service shutting down). */
    fun abortDive(): List<EngineEvent> {
        if (phase != DivePhase.DIVING) return emptyList()
        val events = mutableListOf<EngineEvent>()
        if (shallowSinceMs == null) shallowSinceMs = lastTsMs
        endDive(events)
        displayState = displayState.copy(phase = phase, durationSec = 0)
        return events
    }

    private fun handleSurface(ts: Long, depth: Double, tempC: Double?, events: MutableList<EngineEvent>) {
        if (depth >= config.submersionEpsilonM) {
            if (submergedSinceMs == null) submergedSinceMs = ts
            pending.addLast(Pending(ts, depth, tempC))
            while (pending.size > 900) pending.removeFirst()
        } else {
            submergedSinceMs = null
            deepSinceMs = null
            pending.clear()
        }
        if (depth >= config.startDepthM) {
            if (deepSinceMs == null) deepSinceMs = ts
            if (ts - deepSinceMs!! >= config.startHoldSec * 1000L) startDive(events)
        } else {
            deepSinceMs = null
        }
    }

    private fun startDive(events: MutableList<EngineEvent>) {
        phase = DivePhase.DIVING
        frozenSurfaceBar = surfaceEmaBar ?: frozenSurfaceBar
        diveStartMs = submergedSinceMs ?: deepSinceMs ?: lastTsMs!!
        maxDepthM = 0.0
        depthTimeSum = 0.0
        depthTimeDt = 0.0
        minTempC = null
        shallowSinceMs = null
        events += EngineEvent.DiveStarted(diveStartMs, frozenSurfaceBar)
        var prevTs = diveStartMs
        for (s in pending) {
            if (s.tsMs < diveStartMs) continue
            val dt = (s.tsMs - prevTs) / 1000.0
            prevTs = s.tsMs
            accumulate(s.depthM, s.tempC, dt)
            events += EngineEvent.SampleRecorded(((s.tsMs - diveStartMs) / 1000).toInt(), s.depthM, s.tempC, null)
        }
        pending.clear()
        submergedSinceMs = null
        deepSinceMs = null
    }

    private fun handleDiving(
        ts: Long,
        depth: Double,
        tempC: Double?,
        dtSec: Double,
        ndlSec: Double,
        events: MutableList<EngineEvent>,
    ) {
        accumulate(depth, tempC, dtSec)
        events += EngineEvent.SampleRecorded(
            ((ts - diveStartMs) / 1000).toInt(),
            depth,
            tempC,
            if (ndlSec.isInfinite()) null else ndlSec / 60.0,
        )
        if (depth < config.endDepthM) {
            if (shallowSinceMs == null) shallowSinceMs = ts
            if (ts - shallowSinceMs!! >= config.endHoldSec * 1000L) endDive(events)
        } else {
            shallowSinceMs = null
        }
    }

    private fun endDive(events: MutableList<EngineEvent>) {
        val endMs = shallowSinceMs ?: lastTsMs!!
        val durationSec = ((endMs - diveStartMs) / 1000).toInt()
        if (durationSec < config.minDiveDurationSec) {
            events += EngineEvent.DiveDiscarded
        } else {
            val avg = if (depthTimeDt > 0) depthTimeSum / depthTimeDt else maxDepthM
            events += EngineEvent.DiveEnded(endMs, durationSec, maxDepthM, avg, minTempC)
        }
        phase = DivePhase.SURFACE
        shallowSinceMs = null
        submergedSinceMs = null
        deepSinceMs = null
        pending.clear()
    }

    private fun accumulate(depth: Double, tempC: Double?, dtSec: Double) {
        if (depth > maxDepthM) maxDepthM = depth
        if (tempC != null && (minTempC == null || tempC < minTempC!!)) minTempC = tempC
        if (depth >= config.avgDepthMinM && dtSec > 0.0) {
            depthTimeSum += depth * dtSec
            depthTimeDt += dtSec
        }
    }
}
