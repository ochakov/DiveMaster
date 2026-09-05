package com.ochakov.divemaster.engine

import com.ochakov.divemaster.deco.Gas
import com.ochakov.divemaster.deco.GradientFactors
import com.ochakov.divemaster.deco.WaterType

/**
 * Engine configuration. Detection thresholds are the locked design values;
 * gas, water type, and gradient factors come from user settings at engine
 * construction (config changes apply from the next surface interval, never
 * mid-dive).
 */
data class DiveEngineConfig(
    val waterType: WaterType,
    val gas: Gas,
    val gradientFactors: GradientFactors,
    /** Dive confirmed at this depth... */
    val startDepthM: Double = 1.2,
    /** ...held this long. */
    val startHoldSec: Int = 3,
    /** Dive ends after rising above this depth... */
    val endDepthM: Double = 0.8,
    /** ...for this long (re-descending sooner continues the same dive). */
    val endHoldSec: Int = 60,
    /** Shorter dives are discarded. */
    val minDiveDurationSec: Int = 60,
    /** Safety-stop countdown length. */
    val safetyStopSeconds: Int = 180,
    /** Safety-stop depth window (countdown runs only inside it). */
    val safetyStopMinDepthM: Double = 4.0,
    val safetyStopMaxDepthM: Double = 6.0,
    /** The stop arms once the dive has been at least this deep. */
    val safetyStopRequiredBelowM: Double = 10.0,
    /** Depth at which the diver counts as submerged (dive clock backdates to here). */
    val submersionEpsilonM: Double = 0.3,
    /** Below this depth the diver is assumed breathing surface air, not the configured gas. */
    val gasSwitchDepthM: Double = 0.5,
    /** Half-life of the rolling surface-pressure reference. */
    val surfaceEmaHalfLifeSec: Double = 90.0,
    /**
     * The surface reference stops tracking (freezes) once measured depth
     * exceeds this, so it can't chase a real descent back to zero. Must sit
     * below the dive-start depth; kept well above surface chop for production.
     */
    val surfaceRefFreezeDepthM: Double = 1.0,
    /** Samples shallower than this are excluded from the average-depth statistic. */
    val avgDepthMinM: Double = 0.3,
    /** Window for the vertical-speed estimate. */
    val rateWindowSec: Double = 8.0,
    /** Cap on tissue-integration step across sample gaps (sensor stalls). */
    val maxSampleGapSec: Double = 60.0,
)
