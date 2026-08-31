package com.ochakov.divemaster.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.ochakov.divemaster.data.settings.DiveSettings
import com.ochakov.divemaster.data.settings.SettingsRepository
import com.ochakov.divemaster.deco.WaterType
import com.ochakov.divemaster.ui.theme.DiveAmber
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context) }
    val settings by repository.settings.collectAsState(initial = DiveSettings())
    val scope = rememberCoroutineScope()

    val rows = buildList {
        val o2Percent = (settings.o2Fraction * 100).roundToInt()
        add("Gas" to if (o2Percent == 21) "Air (21% O₂)" else "EAN$o2Percent")
        add("Max ppO₂" to "%.2f bar".format(settings.maxPpO2Bar))
        add(
            "Gradient factors" to
                "${(settings.gradientFactors.low * 100).roundToInt()} / ${(settings.gradientFactors.high * 100).roundToInt()}",
        )
        add(
            "Water type" to when (settings.waterType) {
                WaterType.EN13319 -> "EN 13319"
                WaterType.SALT -> "Salt"
                WaterType.FRESH -> "Fresh"
            },
        )
        add("Units" to if (settings.metricUnits) "Metric (m, °C)" else "Imperial (ft, °F)")
        add(
            "Safety stop" to "%d min at %.0f–%.0f m"
                .format(settings.safetyStopMinutes, settings.safetyStopMinDepthM, settings.safetyStopMaxDepthM),
        )
        add("Ascent alert" to "> %.0f m/min".format(settings.ascentAlertMPerMin))
        add("Descent alert" to "> %.0f m/min".format(settings.descentAlertMPerMin))
        add("Low-NDL alert" to "< ${settings.ndlAlertMinutes} min")
        add(
            "Alerts" to listOfNotNull(
                "vibrate".takeIf { settings.vibrateEnabled },
                "beep".takeIf { settings.beepEnabled },
            ).joinToString(" + ").ifEmpty { "off" },
        )
    }

    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item { Text("SETTINGS", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.secondary) }
            items(rows.size) { i ->
                val (label, value) = rows[i]
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(label, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f))
                    Text(value, style = MaterialTheme.typography.body1)
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
                    "Values are the agreed defaults — editing arrives in Phase 5",
                    fontSize = 10.sp,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
