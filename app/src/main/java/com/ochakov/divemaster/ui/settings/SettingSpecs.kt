package com.ochakov.divemaster.ui.settings

import com.ochakov.divemaster.data.settings.DiveSettings
import com.ochakov.divemaster.data.settings.SettingsRepository
import com.ochakov.divemaster.ui.Units
import kotlin.math.roundToInt

/** Every numeric setting editable via the full-screen stepper. */
enum class NumberSettingId {
    GAS_O2,
    MAX_PPO2,
    GF_LOW,
    GF_HIGH,
    STOP_MINUTES,
    STOP_UPPER,
    STOP_LOWER,
    ASCENT_LIMIT,
    DESCENT_LIMIT,
    NDL_MINUTES,
}

class NumberSettingSpec(
    val title: String,
    val progression: IntProgression,
    /** value + metric flag -> display text */
    val display: (Int, Boolean) -> String,
    val get: (DiveSettings) -> Int,
    val save: suspend (SettingsRepository, Int) -> Unit,
)

object NumberSettings {
    val specs: Map<NumberSettingId, NumberSettingSpec> = mapOf(
        NumberSettingId.GAS_O2 to NumberSettingSpec(
            title = "Gas O₂",
            progression = 21..40,
            display = { v, _ -> if (v == 21) "Air (21%)" else "EAN$v" },
            get = { (it.o2Fraction * 100).roundToInt() },
            save = { repo, v -> repo.setGasO2Fraction(v / 100.0) },
        ),
        NumberSettingId.MAX_PPO2 to NumberSettingSpec(
            title = "Max ppO₂",
            progression = 120..160 step 5,
            display = { v, _ -> "%.2f bar".format(v / 100.0) },
            get = { (it.maxPpO2Bar * 100).roundToInt() },
            save = { repo, v -> repo.setMaxPpO2Bar(v / 100.0) },
        ),
        NumberSettingId.GF_LOW to NumberSettingSpec(
            title = "GF low",
            progression = 10..100 step 5,
            display = { v, _ -> "$v%" },
            get = { (it.gradientFactors.low * 100).roundToInt() },
            save = { repo, v -> repo.setGfLowPercent(v) },
        ),
        NumberSettingId.GF_HIGH to NumberSettingSpec(
            title = "GF high",
            progression = 10..100 step 5,
            display = { v, _ -> "$v%" },
            get = { (it.gradientFactors.high * 100).roundToInt() },
            save = { repo, v -> repo.setGfHighPercent(v) },
        ),
        NumberSettingId.STOP_MINUTES to NumberSettingSpec(
            title = "Safety stop",
            progression = 1..5,
            display = { v, _ -> "$v min" },
            get = { it.safetyStopMinutes },
            save = { repo, v -> repo.setSafetyStopMinutes(v) },
        ),
        NumberSettingId.STOP_UPPER to NumberSettingSpec(
            title = "Stop window top",
            progression = 3..5,
            display = { v, metric -> Units.wholeDepthWithUnit(v.toDouble(), metric) },
            get = { it.safetyStopMinDepthM.roundToInt() },
            save = { repo, v -> repo.setSafetyStopMinDepthM(v.toDouble()) },
        ),
        NumberSettingId.STOP_LOWER to NumberSettingSpec(
            title = "Stop window bottom",
            progression = 5..8,
            display = { v, metric -> Units.wholeDepthWithUnit(v.toDouble(), metric) },
            get = { it.safetyStopMaxDepthM.roundToInt() },
            save = { repo, v -> repo.setSafetyStopMaxDepthM(v.toDouble()) },
        ),
        NumberSettingId.ASCENT_LIMIT to NumberSettingSpec(
            title = "Ascent limit",
            progression = 6..15,
            display = { v, metric -> Units.rate(v.toDouble(), metric) },
            get = { it.ascentAlertMPerMin.roundToInt() },
            save = { repo, v -> repo.setAscentAlertMPerMin(v.toDouble()) },
        ),
        NumberSettingId.DESCENT_LIMIT to NumberSettingSpec(
            title = "Descent limit",
            progression = 10..30,
            display = { v, metric -> Units.rate(v.toDouble(), metric) },
            get = { it.descentAlertMPerMin.roundToInt() },
            save = { repo, v -> repo.setDescentAlertMPerMin(v.toDouble()) },
        ),
        NumberSettingId.NDL_MINUTES to NumberSettingSpec(
            title = "Low-NDL alert at",
            progression = 1..10,
            display = { v, _ -> "$v min" },
            get = { it.ndlAlertMinutes },
            save = { repo, v -> repo.setNdlAlertMinutes(v) },
        ),
    )
}
