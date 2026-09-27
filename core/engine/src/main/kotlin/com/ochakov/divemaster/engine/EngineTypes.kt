package com.ochakov.divemaster.engine

import com.ochakov.divemaster.deco.DepthConverter

/** One filtered 1 Hz pressure reading. Pressure is bar absolute. */
data class PressureSample(
    val timestampMs: Long,
    val pressureBar: Double,
    val tempC: Double? = null,
)

/**
 * The last atmospheric pressure the host was confident about, and how old it
 * is at engine construction. A cold-started engine judges its first reading
 * against it so a restart underwater is recognised as such (see
 * [DiveEngine]'s cold-start rules).
 */
data class SurfaceMemory(val pressureBar: Double, val ageSec: Double)

enum class DivePhase { SURFACE, DIVING }

/**
 * Safety-stop lifecycle. Armed once the dive passes the required depth;
 * the countdown runs only inside the depth window, pausing (not resetting)
 * outside it, per Ev's spec.
 */
enum class SafetyStopState {
    /** Not armed (dive never went deep enough) or no dive active. */
    NONE,

    /** Armed, countdown untouched — the diver hasn't reached the window yet. */
    PENDING,

    /** In the window, counting down. */
    ACTIVE,

    /** Countdown started but the diver is outside the window; time is held. */
    PAUSED,

    /** Countdown reached zero. */
    DONE,
}

/** Everything the UI needs, refreshed once per sample. */
data class DiveDisplayState(
    val phase: DivePhase = DivePhase.SURFACE,
    val depthM: Double = 0.0,
    val maxDepthM: Double = 0.0,
    val durationSec: Long = 0,
    val ndlMin: Double = Double.POSITIVE_INFINITY,
    val ceilingM: Double = 0.0,
    val tempC: Double? = null,
    /** Positive while ascending, negative while descending, m/min. */
    val verticalRateMPerMin: Double = 0.0,
    val ppO2Bar: Double = 0.0,
    val cnsFraction: Double = 0.0,
    val gasO2Fraction: Double = 0.21,
    val surfacePressureBar: Double = DepthConverter.STANDARD_ATMOSPHERE_BAR,
    val simulated: Boolean = false,
    val safetyStop: SafetyStopState = SafetyStopState.NONE,
    val safetyStopRemainingSec: Int = 0,
    val safetyStopMinDepthM: Double = 4.0,
    val safetyStopMaxDepthM: Double = 6.0,
    /**
     * The active dive began before the engine did (app restarted underwater):
     * its clock and tissue loading start at the restart, so the NDL is
     * optimistic by however long the diver was down before it.
     */
    val startedUnderwater: Boolean = false,
    /** Cold start undecided: watching whether the reading moves like water. */
    val startCheckActive: Boolean = false,
    /**
     * The surface reference came from a real surface reading or a fresh
     * memory, with no cold-start suspicion pending — safe to remember as
     * atmospheric pressure and safe to rebuild the engine on.
     */
    val referenceTrusted: Boolean = true,
)

/** Storage-relevant things that happened while processing one sample. */
sealed interface EngineEvent {
    data class DiveStarted(
        val startEpochMs: Long,
        val surfacePressureBar: Double,
        /** See [DiveDisplayState.startedUnderwater]. */
        val startedUnderwater: Boolean = false,
    ) : EngineEvent

    data class SampleRecorded(
        val tOffsetSec: Int,
        val depthM: Double,
        val tempC: Double?,
        /** Null for backfilled pre-confirmation samples and while NDL is unlimited. */
        val ndlMin: Double?,
    ) : EngineEvent

    /**
     * The frozen reference moved mid-dive (see [DiveEngine]); every sample
     * recorded so far for this dive is [depthShiftM] deeper than written
     * (negative = shallower) and the dive's surface pressure is now
     * [surfacePressureBar].
     */
    data class SurfaceReferenceCorrected(
        val surfacePressureBar: Double,
        val depthShiftM: Double,
    ) : EngineEvent

    data class DiveEnded(
        val endEpochMs: Long,
        val durationSec: Int,
        val maxDepthM: Double,
        val avgDepthM: Double,
        val minTempC: Double?,
    ) : EngineEvent

    /** The submersion never met the minimum dive duration; delete anything recorded. */
    data object DiveDiscarded : EngineEvent
}
