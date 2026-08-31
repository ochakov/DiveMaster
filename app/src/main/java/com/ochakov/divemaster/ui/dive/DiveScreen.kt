package com.ochakov.divemaster.ui.dive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.ochakov.divemaster.engine.DiveDisplayState
import com.ochakov.divemaster.engine.DivePhase
import com.ochakov.divemaster.data.settings.DiveSettings
import com.ochakov.divemaster.data.settings.SettingsRepository
import com.ochakov.divemaster.engine.SafetyStopState
import com.ochakov.divemaster.service.DiveService
import com.ochakov.divemaster.ui.Units
import com.ochakov.divemaster.ui.theme.DiveAmber
import com.ochakov.divemaster.ui.theme.DiveCyan
import com.ochakov.divemaster.ui.theme.DiveGreen
import com.ochakov.divemaster.ui.theme.DiveRed
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The underwater screen. Live once the engine reports an active dive
 * (real or simulated); otherwise a static preview of the layout.
 * Auto-switching, touch lock, and always-on handling arrive in Phase 4.
 */
@Composable
fun DiveScreen() {
    val context = LocalContext.current
    val settings by remember { SettingsRepository(context) }.settings.collectAsState(initial = DiveSettings())
    val state by DiveService.displayState.collectAsState()
    val live = state?.takeIf { it.phase == DivePhase.DIVING }
    if (live != null) DiveContent(live, settings.metricUnits) else PreviewContent()
}

@Composable
private fun DiveContent(state: DiveDisplayState, metric: Boolean) {
    val o2Percent = (state.gasO2Fraction * 100).roundToInt()
    val gasLabel = if (o2Percent == 21) "AIR" else "EAN$o2Percent"

    val ndlText: String
    val ndlColor: Color
    when {
        state.ndlMin.isInfinite() -> {
            ndlText = "99+"
            ndlColor = DiveGreen
        }
        state.ndlMin <= 0.0 -> {
            ndlText = "DECO"
            ndlColor = DiveRed
        }
        else -> {
            val minutes = state.ndlMin.toInt()
            ndlText = "$minutes'"
            ndlColor = if (minutes < 5) DiveRed else if (minutes < 10) DiveAmber else DiveGreen
        }
    }

    // The stop panel renders in place of the MAX/TEMP row so the vertically
    // centered layout keeps a constant height on the round screen; DONE is a
    // badge in the bottom row rather than a row of its own.
    val stopPanelVisible = when (state.safetyStop) {
        SafetyStopState.NONE, SafetyStopState.DONE -> false
        SafetyStopState.PENDING -> state.depthM < state.safetyStopMaxDepthM + 3.0
        SafetyStopState.ACTIVE, SafetyStopState.PAUSED -> true
    }

    // Locked decisions: screen fully on for the whole dive, touch locked out.
    // The overlay consumes every pointer event at the initial pass; swipe-to-
    // dismiss is separately disabled at the nav host while diving. Unlocks
    // automatically when the dive ends.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        if (state.simulated) {
            Text(
                "SIM",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = DiveAmber,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            )
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (!stopPanelVisible) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LabeledValue("MAX", Units.depth(state.maxDepthM, metric))
                    LabeledValue("TEMP", state.tempC?.let { Units.tempShort(it, metric) } ?: "--")
                }
            } else {
                SafetyStopPanel(state, metric)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Units.depth(state.depthM, metric), fontSize = 58.sp, fontWeight = FontWeight.Bold, color = DiveCyan)
                Text(
                    Units.depthUnit(metric),
                    fontSize = 16.sp,
                    color = DiveCyan.copy(alpha = 0.8f),
                    modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LabeledValue("NDL", ndlText, valueColor = ndlColor)
                LabeledValue("TIME", "%d:%02d".format(state.durationSec / 60, state.durationSec % 60))
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(gasLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DiveGreen)
                Text("ppO₂ %.2f".format(state.ppO2Bar), fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
                Text("CNS ${(state.cnsFraction * 100).roundToInt()}%", fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
                if (state.safetyStop == SafetyStopState.DONE) {
                    Text("STOP ✓", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DiveGreen)
                }
            }
        }
        RateBar(state.verticalRateMPerMin, Modifier.align(Alignment.CenterEnd).padding(end = 10.dp))
    }
}

/**
 * Safety-stop countdown, rendered in the MAX/TEMP row's slot — the caller
 * gates visibility so the centered layout keeps a constant height. Quiet
 * while approaching the window, bold green countdown inside it, amber with
 * a direction hint while paused; DONE is a badge in the bottom row instead.
 */
@Composable
private fun SafetyStopPanel(state: DiveDisplayState, metric: Boolean) {
    val remaining = state.safetyStopRemainingSec
    val time = "%d:%02d".format(remaining / 60, remaining % 60)
    val windowText = Units.windowText(state.safetyStopMinDepthM, state.safetyStopMaxDepthM, metric)
    when (state.safetyStop) {
        SafetyStopState.PENDING -> Text(
            "STOP $time at $windowText",
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.7f),
        )

        SafetyStopState.ACTIVE -> Text(
            "STOP $time",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            modifier = Modifier
                .background(DiveGreen, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 2.dp),
        )

        SafetyStopState.PAUSED -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "STOP $time",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                modifier = Modifier
                    .background(DiveAmber, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 1.dp),
            )
            Text(
                if (state.depthM > state.safetyStopMaxDepthM) "PAUSED · ascend to $windowText" else "PAUSED · descend to $windowText",
                fontSize = 10.sp,
                color = DiveAmber,
            )
        }

        SafetyStopState.NONE, SafetyStopState.DONE -> Unit // not rendered here
    }
}

@Composable
private fun RateBar(rateMPerMin: Double, modifier: Modifier = Modifier) {
    val ascending = rateMPerMin > 0
    val magnitude = abs(rateMPerMin)
    val lit = minOf(4, (magnitude / 3.0).toInt())
    val color = when {
        ascending && magnitude > 10 -> DiveRed
        ascending -> DiveGreen
        else -> DiveCyan
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (magnitude < 0.5) " " else if (ascending) "↑" else "↓", fontSize = 10.sp, color = color)
        repeat(4) { index ->
            Box(
                Modifier
                    .size(width = 6.dp, height = 10.dp)
                    .background(color.copy(alpha = if (index < lit) 1f else 0.2f)),
            )
        }
    }
}

@Composable
private fun PreviewContent() {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LabeledValue("MAX", "16.8")
                LabeledValue("TEMP", "12.4°")
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("14.5", fontSize = 58.sp, fontWeight = FontWeight.Bold, color = DiveCyan)
                Text(
                    "m",
                    fontSize = 16.sp,
                    color = DiveCyan.copy(alpha = 0.8f),
                    modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LabeledValue("NDL", "26'", valueColor = DiveGreen)
                LabeledValue("TIME", "37:40")
            }
            Spacer(Modifier.height(6.dp))
            Text("AIR", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DiveGreen)
            Spacer(Modifier.height(4.dp))
            Text("PREVIEW — no active dive", fontSize = 9.sp, color = Color.White.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String, valueColor: Color = Color.White) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}
