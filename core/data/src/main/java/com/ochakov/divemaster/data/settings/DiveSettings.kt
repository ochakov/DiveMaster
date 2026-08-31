package com.ochakov.divemaster.data.settings

import com.ochakov.divemaster.deco.Gas
import com.ochakov.divemaster.deco.GradientFactors
import com.ochakov.divemaster.deco.WaterType

/** All user-adjustable configuration with the agreed v1 defaults. */
data class DiveSettings(
    val o2Fraction: Double = Gas.AIR_O2_FRACTION,
    val waterType: WaterType = WaterType.EN13319,
    val gradientFactors: GradientFactors = GradientFactors.DEFAULT,
    val metricUnits: Boolean = true,
    val rateAlertsEnabled: Boolean = true,
    val ascentAlertMPerMin: Double = 10.0,
    val descentAlertMPerMin: Double = 20.0,
    val ndlAlertEnabled: Boolean = true,
    val ndlAlertMinutes: Int = 5,
    val safetyStopMinutes: Int = 3,
    val safetyStopMinDepthM: Double = 4.0,
    val safetyStopMaxDepthM: Double = 6.0,
    val maxPpO2Bar: Double = 1.4,
    val beepEnabled: Boolean = true,
    val vibrateEnabled: Boolean = true,
    val simulatorEnabled: Boolean = false,
) {
    val gas: Gas get() = Gas(o2Fraction)
}
