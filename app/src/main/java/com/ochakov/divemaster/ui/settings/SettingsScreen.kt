package com.ochakov.divemaster.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import com.ochakov.divemaster.data.settings.DiveSettings
import com.ochakov.divemaster.data.settings.SettingsRepository
import com.ochakov.divemaster.deco.WaterType
import com.ochakov.divemaster.ui.Units
import com.ochakov.divemaster.ui.theme.DiveAmber
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(onEditNumber: (NumberSettingId) -> Unit) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context) }
    val settings by repository.settings.collectAsState(initial = DiveSettings())
    val scope = rememberCoroutineScope()
    val metric = settings.metricUnits

    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item { Text("SETTINGS", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.secondary) }

            item { SectionHeader("GAS") }
            item {
                val o2 = (settings.o2Fraction * 100).roundToInt()
                SettingChip("Gas", if (o2 == 21) "Air (21%)" else "EAN$o2") { onEditNumber(NumberSettingId.GAS_O2) }
            }
            item {
                SettingChip("Max ppO₂", "%.2f bar".format(settings.maxPpO2Bar)) { onEditNumber(NumberSettingId.MAX_PPO2) }
            }

            item { SectionHeader("DECO") }
            item {
                SettingChip("GF low", "${(settings.gradientFactors.low * 100).roundToInt()}%") {
                    onEditNumber(NumberSettingId.GF_LOW)
                }
            }
            item {
                SettingChip("GF high", "${(settings.gradientFactors.high * 100).roundToInt()}%") {
                    onEditNumber(NumberSettingId.GF_HIGH)
                }
            }
            item {
                val label = when (settings.waterType) {
                    WaterType.EN13319 -> "EN 13319"
                    WaterType.SALT -> "Salt"
                    WaterType.FRESH -> "Fresh"
                }
                SettingChip("Water type", label) {
                    val next = when (settings.waterType) {
                        WaterType.EN13319 -> WaterType.SALT
                        WaterType.SALT -> WaterType.FRESH
                        WaterType.FRESH -> WaterType.EN13319
                    }
                    scope.launch { repository.setWaterType(next) }
                }
            }

            item { SectionHeader("SAFETY STOP") }
            item {
                SettingChip("Duration", "${settings.safetyStopMinutes} min") { onEditNumber(NumberSettingId.STOP_MINUTES) }
            }
            item {
                SettingChip("Window top", Units.wholeDepthWithUnit(settings.safetyStopMinDepthM, metric)) {
                    onEditNumber(NumberSettingId.STOP_UPPER)
                }
            }
            item {
                SettingChip("Window bottom", Units.wholeDepthWithUnit(settings.safetyStopMaxDepthM, metric)) {
                    onEditNumber(NumberSettingId.STOP_LOWER)
                }
            }

            item { SectionHeader("ALERTS") }
            item {
                SettingToggle("Rate alerts", settings.rateAlertsEnabled) {
                    scope.launch { repository.setRateAlertsEnabled(it) }
                }
            }
            item {
                SettingChip("Ascent limit", Units.rate(settings.ascentAlertMPerMin, metric)) {
                    onEditNumber(NumberSettingId.ASCENT_LIMIT)
                }
            }
            item {
                SettingChip("Descent limit", Units.rate(settings.descentAlertMPerMin, metric)) {
                    onEditNumber(NumberSettingId.DESCENT_LIMIT)
                }
            }
            item {
                SettingToggle("Low-NDL alert", settings.ndlAlertEnabled) {
                    scope.launch { repository.setNdlAlertEnabled(it) }
                }
            }
            item {
                SettingChip("Low-NDL at", "${settings.ndlAlertMinutes} min") { onEditNumber(NumberSettingId.NDL_MINUTES) }
            }
            item {
                SettingToggle("Vibrate", settings.vibrateEnabled) {
                    scope.launch { repository.setVibrateEnabled(it) }
                }
            }
            item {
                SettingToggle("Beep", settings.beepEnabled) {
                    scope.launch { repository.setBeepEnabled(it) }
                }
            }

            item { SectionHeader("DISPLAY") }
            item {
                SettingToggle(if (metric) "Units: metric" else "Units: imperial", metric) {
                    scope.launch { repository.setMetricUnits(it) }
                }
            }

            item {
                // Hidden dev entry: five taps toggle the dive simulator.
                var taps by remember { mutableStateOf(0) }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            taps += 1
                            if (taps >= 5) {
                                taps = 0
                                scope.launch { repository.setSimulatorEnabled(!settings.simulatorEnabled) }
                            }
                        },
                ) {
                    Text("Simulator", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f))
                    Text(
                        if (settings.simulatorEnabled) "On" else "Off",
                        style = MaterialTheme.typography.body1,
                        color = if (settings.simulatorEnabled) DiveAmber else MaterialTheme.colors.onBackground,
                    )
                    if (taps in 1..4) {
                        Text(
                            "${5 - taps} taps to toggle",
                            fontSize = 9.sp,
                            color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                        )
                    }
                }
            }
            item {
                Text(
                    "Changes apply on the surface — never mid-dive",
                    fontSize = 10.sp,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.caption2,
        color = MaterialTheme.colors.secondary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun SettingChip(label: String, value: String, onClick: () -> Unit) {
    Chip(
        label = { Text(label) },
        secondaryLabel = { Text(value) },
        onClick = onClick,
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ToggleChip(
        checked = checked,
        onCheckedChange = onChange,
        label = { Text(label) },
        toggleControl = { Switch(checked = checked) },
        modifier = Modifier.fillMaxWidth(),
    )
}
