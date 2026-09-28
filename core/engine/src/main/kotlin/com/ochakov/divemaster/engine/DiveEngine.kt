package com.ochakov.divemaster.engine

import com.ochakov.divemaster.deco.Buhlmann
import com.ochakov.divemaster.deco.DepthConverter
import com.ochakov.divemaster.deco.Gas
import com.ochakov.divemaster.deco.Oxygen
import com.ochakov.divemaster.deco.TissueState
import kotlin.math.ceil
import kotlin.math.pow

/**
 * The dive computer's heart: a deterministic reducer fed 1 Hz pressure
 * samples. It owns the surface-pressure reference, the dive-detection state
 * machine (backdated start, merge-across-brief-surfacing, minimum-duration
 * discard), tissue and CNS physiology, and the live display state.
 *
 * Surface reference: an EMA of pressure while near the surface, frozen for
 * the dive. Because a third-party app can be killed and restarted underwater,
 * a cold start never blindly takes its first reading as the atmosphere — it
 * is judged against [SurfaceMemory] and an absolute ceiling (see
 * [resolveStartReference]), and a dive that turns out to have begun before
 * the engine did is flagged as started underwater. Mid-dive the reference can
 * only ever move *down*: a diver cannot be above the surface, so pressure
 * below the reference proves it was too high ([correctReference]).
 *
 * All timing derives from sample timestamps, never a wall clock, so the
 * engine is fully unit-testable and works identically with the simulator.
 * Not thread-safe: feed it from a single thread/coroutine.
 */
class DiveEngine(
    private val config: DiveEngineConfig,
    initialTissue: TissueState,
    initialCnsFraction: Double = 0.0,
    /** Exact reference to continue from (settings hot-reload on the surface); bypasses [surfaceMemory]. */
    initialSurfaceBar: Double? = null,
    /** Last confident atmospheric pressure the host remembered; consulted on a cold start only. */
    private val surfaceMemory: SurfaceMemory? = null,
) {
    private val converter = DepthConverter(config.waterType)

    var tissue: TissueState = initialTissue
        private set
    var cnsFraction: Double = initialCnsFraction
        private set
    var displayState: DiveDisplayState = DiveDisplayState(
        gasO2Fraction = config.gas.o2Fraction,
        surfacePressureBar = initialSurfaceBar
            ?: surfaceMemory?.pressureBar
            ?: DepthConverter.STANDARD_ATMOSPHERE_BAR,
    )
        private set

    private var phase = DivePhase.SURFACE
    private var lastTsMs: Long? = null
    private var lastPressureBar: Double? = null
    private var surfaceEmaBar: Double? = initialSurfaceBar
    private var frozenSurfaceBar: Double = initialSurfaceBar ?: DepthConverter.STANDARD_ATMOSPHERE_BAR

    // Cold-start reference decision (see resolveStartReference).
    private var startResolved = initialSurfaceBar != null
    private var startCheck: StartCheck? = null

    /** The next dive to start began before the engine did. */
    private var lateStartPending = false

    /** No fresh memory existed: the reference is the standard atmosphere — a guess. */
    private var referenceGuessed = false

    /** The active dive started underwater. */
    private var startedUnderwater = false

    private class StartCheck(val startTsMs: Long, var minBar: Double, var maxBar: Double)

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

    /** Re-descent timer while an end is pending (see handleDiving). */
    private var resubmergedSinceMs: Long? = null
    private var maxAscentRateMPerMin = 0.0

    // Safety stop.
    private var stopArmed = false
    private var stopRemainingSec = config.safetyStopSeconds.toDouble()

    // Vertical-rate window.
    private data class DepthAt(val tsMs: Long, val depthM: Double)

    private val rateWindow = ArrayDeque<DepthAt>()

    // Still-water watch (guessed reference only): pressure over the last minute.
    private data class PressureAt(val tsMs: Long, val bar: Double)

    private val stillWindow = ArrayDeque<PressureAt>()

    fun onSample(sample: PressureSample): List<EngineEvent> {
        val events = mutableListOf<EngineEvent>()
        val ts = sample.timestampMs
        val p = sample.pressureBar
        // Clock skew (e.g., a simulated dive ran timestamps ahead of the wall
        // clock): accept the sample but advance no physiology time.
        val dtSec = (lastTsMs?.let { (ts - it) / 1000.0 } ?: 0.0)
            .coerceIn(0.0, config.maxSampleGapSec)
        lastTsMs = ts
        lastPressureBar = p

        if (!startResolved) resolveStartReference(ts, p)
        startCheck?.let { advanceStartCheck(it, ts, p) }

        // Surface reference: EMA while near the surface (and while the reading
        // could be air at all), frozen during a dive and while a cold start is
        // still undecided.
        if (phase == DivePhase.SURFACE && startCheck == null) {
            val ref = surfaceEmaBar
            if (ref == null) {
                surfaceEmaBar = p
            } else if (dtSec > 0.0 && p <= config.atmosphericCeilingBar &&
                converter.depthMeters(p, ref) < config.surfaceRefFreezeDepthM
            ) {
                val alpha = 1.0 - 2.0.pow(-dtSec / config.surfaceEmaHalfLifeSec)
                surfaceEmaBar = ref + (p - ref) * alpha
            }
        }
        var surfaceBar = if (phase == DivePhase.DIVING) frozenSurfaceBar else (surfaceEmaBar ?: p)
        var depth = converter.depthMeters(p, surfaceBar)

        // A diver cannot be above the surface: pressure this far below the
        // frozen reference proves the reference was too high (a memory or a
        // guess after an underwater restart). Lower it to the observed minimum
        // and re-base the dive so far.
        if (phase == DivePhase.DIVING && depth < -config.referenceCorrectionDepthM) {
            correctReference(p, events)
            surfaceBar = frozenSurfaceBar
            depth = 0.0
        }

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
            DivePhase.DIVING -> handleDiving(ts, p, depth, rate, sample.tempC, dtSec, ndlSec, events)
        }
        // The still-water rule inside handleDiving may have moved the reference.
        if (phase == DivePhase.DIVING && surfaceBar != frozenSurfaceBar) {
            surfaceBar = frozenSurfaceBar
            depth = converter.depthMeters(p, surfaceBar)
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
            safetyStop = safetyStopState(depth),
            safetyStopRemainingSec = ceil(stopRemainingSec).toInt(),
            safetyStopMinDepthM = config.safetyStopMinDepthM,
            safetyStopMaxDepthM = config.safetyStopMaxDepthM,
            startedUnderwater = startedUnderwater,
            startCheckActive = startCheck != null,
            referenceTrusted = referenceTrusted(),
            endPending = phase == DivePhase.DIVING && shallowSinceMs != null,
        )
        return events
    }

    private fun referenceTrusted() =
        startResolved && startCheck == null && !lateStartPending && !referenceGuessed

    /**
     * Cold start — what does the first reading mean? Three cases:
     *  1. Above the atmospheric ceiling: water, whatever the memory says. The
     *     reference is a fresh memory if there is one, else the standard
     *     atmosphere — a guess the dive itself refines ([correctReference],
     *     [watchStillWater]).
     *  2. At least [DiveEngineConfig.underwaterStartExcessBar] above a fresh
     *     memory: probably water, but possibly a boat deck after a drive down
     *     from altitude. Take the memory provisionally and watch for a while —
     *     water moves the reading around, a deck does not ([advanceStartCheck]).
     *  3. Otherwise: calibrate to the reading, as a surface start always did.
     */
    private fun resolveStartReference(ts: Long, p: Double) {
        startResolved = true
        val fresh = surfaceMemory?.takeIf {
            it.ageSec <= config.surfaceMemoryMaxAgeSec && it.pressureBar <= config.atmosphericCeilingBar
        }
        when {
            p > config.atmosphericCeilingBar -> {
                referenceGuessed = fresh == null
                seedUnderwater(fresh?.pressureBar ?: DepthConverter.STANDARD_ATMOSPHERE_BAR)
            }

            fresh != null && p >= fresh.pressureBar + config.underwaterStartExcessBar -> {
                surfaceEmaBar = fresh.pressureBar
                frozenSurfaceBar = fresh.pressureBar
                startCheck = StartCheck(ts, p, p)
            }

            else -> {
                surfaceEmaBar = p
                frozenSurfaceBar = p
            }
        }
    }

    private fun advanceStartCheck(check: StartCheck, ts: Long, p: Double) {
        if (p < check.minBar) check.minBar = p
        if (p > check.maxBar) check.maxBar = p
        when {
            p > config.atmosphericCeilingBar || check.maxBar - check.minBar >= config.startCheckMotionBar ->
                // Water: keep the remembered reference and flag the coming dive.
                seedUnderwater(surfaceEmaBar ?: DepthConverter.STANDARD_ATMOSPHERE_BAR)

            ts - check.startTsMs >= (config.startCheckWindowSec * 1000).toLong() -> {
                // Dead still for the whole window: dry land. Calibrate here.
                startCheck = null
                surfaceEmaBar = p
                frozenSurfaceBar = p
                resetStartDetection()
            }
        }
    }

    private fun seedUnderwater(referenceBar: Double) {
        surfaceEmaBar = referenceBar
        frozenSurfaceBar = referenceBar
        lateStartPending = true
        startCheck = null
    }

    /** Move the frozen reference to [newRef] and re-base the dive's running statistics. */
    private fun correctReference(newRef: Double, events: MutableList<EngineEvent>) {
        val shiftM = converter.depthMeters(frozenSurfaceBar, newRef)
        frozenSurfaceBar = newRef
        surfaceEmaBar = newRef
        maxDepthM = (maxDepthM + shiftM).coerceAtLeast(0.0)
        // Seconds excluded from the average as "too shallow" under the old
        // reference stay excluded — a small, deliberate approximation.
        depthTimeSum = (depthTimeSum + shiftM * depthTimeDt).coerceAtLeast(0.0)
        rateWindow.clear()
        stillWindow.clear()
        events += EngineEvent.SurfaceReferenceCorrected(newRef, shiftM)
    }

    /**
     * The diver says "not underwater" (the notification's action after a
     * suspected-underwater start): drop any dive in progress — one measured
     * from a wrong reference is not worth keeping — take the current pressure
     * as the surface and clear all cold-start suspicion.
     */
    fun recalibrate(): List<EngineEvent> {
        val events = mutableListOf<EngineEvent>()
        if (phase == DivePhase.DIVING) {
            events += EngineEvent.DiveDiscarded
            phase = DivePhase.SURFACE
        }
        lastPressureBar?.let { p ->
            surfaceEmaBar = p
            frozenSurfaceBar = p
            startResolved = true
        }
        startCheck = null
        lateStartPending = false
        referenceGuessed = false
        startedUnderwater = false
        shallowSinceMs = null
        resubmergedSinceMs = null
        resetStartDetection()
        rateWindow.clear()
        stillWindow.clear()
        displayState = displayState.copy(
            phase = phase,
            depthM = 0.0,
            durationSec = 0,
            surfacePressureBar = frozenSurfaceBar,
            safetyStop = SafetyStopState.NONE,
            startedUnderwater = false,
            startCheckActive = false,
            referenceTrusted = referenceTrusted(),
            endPending = false,
        )
        return events
    }

    private fun safetyStopState(depth: Double): SafetyStopState = when {
        phase != DivePhase.DIVING || !stopArmed -> SafetyStopState.NONE
        stopRemainingSec <= 0.0 -> SafetyStopState.DONE
        depth in config.safetyStopMinDepthM..config.safetyStopMaxDepthM -> SafetyStopState.ACTIVE
        stopRemainingSec < config.safetyStopSeconds.toDouble() -> SafetyStopState.PAUSED
        else -> SafetyStopState.PENDING
    }

    /** Force-end the current dive (simulator stopped, service shutting down). */
    fun abortDive(): List<EngineEvent> {
        if (phase != DivePhase.DIVING) return emptyList()
        val events = mutableListOf<EngineEvent>()
        if (shallowSinceMs == null) shallowSinceMs = lastTsMs
        endDive(events)
        displayState = displayState.copy(
            phase = phase,
            durationSec = 0,
            safetyStop = SafetyStopState.NONE,
            startedUnderwater = false,
            endPending = false,
        )
        return events
    }

    private fun handleSurface(ts: Long, depth: Double, tempC: Double?, events: MutableList<EngineEvent>) {
        if (depth >= config.submersionEpsilonM) {
            if (submergedSinceMs == null) submergedSinceMs = ts
            pending.addLast(Pending(ts, depth, tempC))
            while (pending.size > 900) pending.removeFirst()
        } else {
            resetStartDetection()
            // At the (remembered) surface after all: nothing late is pending,
            // and from here the EMA tracks real surface readings.
            lateStartPending = false
            referenceGuessed = false
        }
        if (startCheck != null) return // undecided cold start: never start a dive yet
        if (depth >= config.startDepthM) {
            if (deepSinceMs == null) deepSinceMs = ts
            if (ts - deepSinceMs!! >= config.startHoldSec * 1000L) startDive(events)
        } else {
            deepSinceMs = null
        }
    }

    private fun resetStartDetection() {
        submergedSinceMs = null
        deepSinceMs = null
        pending.clear()
    }

    private fun startDive(events: MutableList<EngineEvent>) {
        phase = DivePhase.DIVING
        frozenSurfaceBar = surfaceEmaBar ?: frozenSurfaceBar
        startedUnderwater = lateStartPending
        lateStartPending = false
        diveStartMs = submergedSinceMs ?: deepSinceMs ?: lastTsMs!!
        maxDepthM = 0.0
        depthTimeSum = 0.0
        depthTimeDt = 0.0
        minTempC = null
        shallowSinceMs = null
        resubmergedSinceMs = null
        maxAscentRateMPerMin = 0.0
        stopArmed = false
        stopRemainingSec = config.safetyStopSeconds.toDouble()
        stillWindow.clear()
        events += EngineEvent.DiveStarted(diveStartMs, frozenSurfaceBar, startedUnderwater)
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
        p: Double,
        depth: Double,
        rateMPerMin: Double,
        tempC: Double?,
        dtSec: Double,
        ndlSec: Double,
        events: MutableList<EngineEvent>,
    ) {
        accumulate(depth, tempC, dtSec)
        if (rateMPerMin > maxAscentRateMPerMin) maxAscentRateMPerMin = rateMPerMin
        if (!stopArmed && maxDepthM >= config.safetyStopRequiredBelowM) stopArmed = true
        if (stopArmed && stopRemainingSec > 0.0 &&
            depth >= config.safetyStopMinDepthM && depth <= config.safetyStopMaxDepthM
        ) {
            stopRemainingSec = (stopRemainingSec - dtSec).coerceAtLeast(0.0)
        }
        events += EngineEvent.SampleRecorded(
            ((ts - diveStartMs) / 1000).toInt(),
            depth,
            tempC,
            if (ndlSec.isInfinite()) null else ndlSec / 60.0,
        )
        // End of dive: the first surface touch starts the hold and fixes the
        // end time. Splashes while climbing out (0.2–0.5 m for a few seconds)
        // don't reset it — only "diving again", past the start depth and
        // sustained, cancels it and the dive carries on.
        if (depth < config.endDepthM) {
            if (shallowSinceMs == null) shallowSinceMs = ts
            resubmergedSinceMs = null
        } else if (shallowSinceMs != null) {
            if (depth >= config.endCancelDepthM) {
                if (resubmergedSinceMs == null) resubmergedSinceMs = ts
                if (ts - resubmergedSinceMs!! >= config.endCancelHoldSec * 1000L) {
                    shallowSinceMs = null
                    resubmergedSinceMs = null
                }
            } else {
                resubmergedSinceMs = null
            }
        }
        val pendingSinceMs = shallowSinceMs
        if (pendingSinceMs != null && ts - pendingSinceMs >= config.endHoldSec * 1000L) endDive(events)
        if (phase == DivePhase.DIVING && referenceGuessed) watchStillWater(ts, p, depth, events)
    }

    /**
     * Guessed reference only: a dead-still reading in the shallow band for a
     * full minute is a diver standing at the real surface — which may be
     * *higher* pressure than the guess, a case [correctReference]'s downward
     * rule can never reach. Take it as the reference so the dive can end.
     */
    private fun watchStillWater(ts: Long, p: Double, depth: Double, events: MutableList<EngineEvent>) {
        if (depth >= config.stillWaterMaxDepthM) {
            stillWindow.clear()
            return
        }
        stillWindow.addLast(PressureAt(ts, p))
        val windowMs = (config.stillWaterWindowSec * 1000).toLong()
        while (ts - stillWindow.first().tsMs > windowMs) stillWindow.removeFirst()
        if (ts - stillWindow.first().tsMs < windowMs - 1500) return // not a full minute yet
        val min = stillWindow.minOf { it.bar }
        val max = stillWindow.maxOf { it.bar }
        if (max - min <= config.stillWaterMotionBar) {
            correctReference(p, events)
            referenceGuessed = false
        }
    }

    private fun endDive(events: MutableList<EngineEvent>) {
        val endMs = shallowSinceMs ?: lastTsMs!!
        val durationSec = ((endMs - diveStartMs) / 1000).toInt()
        if (durationSec < config.minDiveDurationSec) {
            events += EngineEvent.DiveDiscarded
        } else {
            val avg = if (depthTimeDt > 0) depthTimeSum / depthTimeDt else maxDepthM
            val stopResult = when {
                !stopArmed -> SafetyStopResult.NOT_REQUIRED
                stopRemainingSec <= 0.0 -> SafetyStopResult.DONE
                else -> SafetyStopResult.INCOMPLETE
            }
            events += EngineEvent.DiveEnded(
                endMs, durationSec, maxDepthM, avg, minTempC,
                maxAscentRateMPerMin, stopResult, cnsFraction,
            )
        }
        phase = DivePhase.SURFACE
        shallowSinceMs = null
        resubmergedSinceMs = null
        submergedSinceMs = null
        deepSinceMs = null
        pending.clear()
        startedUnderwater = false
        referenceGuessed = false
        stillWindow.clear()
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
