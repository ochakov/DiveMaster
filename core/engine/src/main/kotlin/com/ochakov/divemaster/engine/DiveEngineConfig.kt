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
    /**
     * Dive confirmed at this depth, held [startHoldSec]. Kept shallow (0.3 m)
     * so dive mode engages almost immediately — like a dedicated dive computer
     * — and, crucially, freezes the surface reference before a sustained
     * shallow swim (finning to the entry at ~0.5 m for minutes) can drift it.
     * Brief accidental dips are still filtered by [minDiveDurationSec].
     */
    val startDepthM: Double = 0.3,
    /** ...held this long. */
    val startHoldSec: Int = 3,
    /** Dive ends after rising above this depth... */
    val endDepthM: Double = 0.2,
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
    val submersionEpsilonM: Double = 0.1,
    /** Below this depth the diver is assumed breathing surface air, not the configured gas. */
    val gasSwitchDepthM: Double = 0.15,
    /** Half-life of the rolling surface-pressure reference. */
    val surfaceEmaHalfLifeSec: Double = 90.0,
    /**
     * The surface reference stops tracking (freezes) once measured depth
     * exceeds this, so it can't chase a real descent back to zero. Must sit
     * below the dive-start depth; kept well above surface chop for production.
     */
    val surfaceRefFreezeDepthM: Double = 0.2,
    /** Samples shallower than this are excluded from the average-depth statistic. */
    val avgDepthMinM: Double = 0.3,
    /** Window for the vertical-speed estimate. */
    val rateWindowSec: Double = 8.0,
    /** Cap on tissue-integration step across sample gaps (sensor stalls). */
    val maxSampleGapSec: Double = 60.0,

    // --- Cold-start surface reference (an app restarted underwater must not
    // --- take ambient pressure at depth for the atmosphere; Ev, 2026-09-27).

    /**
     * Highest pressure that can still be air. No place on Earth's surface
     * exceeds ~1.085 bar (all-time record ≈ 1.084; the Dead Sea shore sits
     * near 1.065), so a cold-start reading above this is water regardless of
     * memory, and the surface reference is never allowed above it.
     */
    val atmosphericCeilingBar: Double = 1.100,
    /**
     * A first reading at least this far above a fresh [SurfaceMemory] is
     * suspected to be underwater: 0.3 m of water, the dive-start depth. Weather
     * moves a few hPa per hour, so this stays clear of drift within
     * [surfaceMemoryMaxAgeSec].
     */
    val underwaterStartExcessBar: Double = 0.030,
    /** A surface memory older than this no longer vetoes calibration. */
    val surfaceMemoryMaxAgeSec: Double = 6.0 * 3600.0,
    /**
     * Suspected-underwater cold start: how long to watch before deciding.
     * Water swings the reading around (a swimming wrist moves decimetres); a
     * boat deck after a drive down from altitude — the false-positive case —
     * is static.
     */
    val startCheckWindowSec: Double = 20.0,
    /** Peak-to-peak pressure swing inside that window that proves water (0.1 m). */
    val startCheckMotionBar: Double = 0.010,
    /**
     * Mid-dive: a reading this far "above the surface" proves the frozen
     * reference was too high (a memory or a guess after an underwater
     * restart). The reference drops to the observed minimum and the dive so
     * far is re-based.
     */
    val referenceCorrectionDepthM: Double = 0.2,
    /**
     * Guessed reference only (underwater start with no fresh memory): the
     * standard atmosphere may sit *below* the real surface pressure, which
     * would leave a surfaced diver at a permanent "depth" and the dive unable
     * to end. A reading shallower than [stillWaterMaxDepthM] that stays within
     * [stillWaterMotionBar] peak-to-peak for [stillWaterWindowSec] is a diver
     * standing at the real surface, which then becomes the reference.
     */
    val stillWaterMaxDepthM: Double = 0.6,
    val stillWaterWindowSec: Double = 60.0,
    val stillWaterMotionBar: Double = 0.005,
)
