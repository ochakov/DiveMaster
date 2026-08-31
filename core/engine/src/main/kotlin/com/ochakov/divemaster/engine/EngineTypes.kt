package com.ochakov.divemaster.engine

import com.ochakov.divemaster.deco.DepthConverter

/** One filtered 1 Hz pressure reading. Pressure is bar absolute. */
data class PressureSample(
    val timestampMs: Long,
    val pressureBar: Double,
    val tempC: Double? = null,
)

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
)

/** Storage-relevant things that happened while processing one sample. */
sealed interface EngineEvent {
    data class DiveStarted(val startEpochMs: Long, val surfacePressureBar: Double) : EngineEvent

    data class SampleRecorded(
        val tOffsetSec: Int,
        val depthM: Double,
        val tempC: Double?,
        /** Null for backfilled pre-confirmation samples and while NDL is unlimited. */
        val ndlMin: Double?,
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
