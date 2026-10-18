package com.ochakov.divemaster.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.ochakov.divemaster.service.BackgroundActivityPolicy
import com.ochakov.divemaster.ui.theme.DiveAmber
import com.ochakov.divemaster.ui.theme.DiveRed

/**
 * Shown at every app start while the watch has "Allow background activity"
 * switched off for DiveMaster: with it off, barometer readings stop reaching
 * the app as soon as the screen sleeps in water, and the dive records only
 * the seconds the diver spends looking at the watch (2026-10-16: 1 % and
 * 21 % of two dives). The screen disappears by itself once the switch is on;
 * "Continue anyway" lets a diver who knows what they are doing through for
 * this launch only. Never shown while a dive is in progress — a glance must
 * always reach the dive screen.
 */
@Composable
fun BackgroundRestrictedScreen(onContinueAnyway: () -> Unit) {
    val context = LocalContext.current
    var noSettingsPage by remember { mutableStateOf(false) }

    val listState = rememberScalingLazyListState()
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    "BACKGROUND ACTIVITY IS OFF",
                    style = MaterialTheme.typography.title3,
                    color = DiveRed,
                    textAlign = TextAlign.Center,
                )
            }
            item {
                Paragraph(
                    "This watch has background activity switched off for DiveMaster. " +
                        "The moment the screen sleeps underwater, depth readings stop reaching the app " +
                        "and the dive records only the seconds you look at the watch.",
                )
            }
            item {
                Paragraph("Switch it on in the ${BackgroundActivityPolicy.FIX_PATH}.", DiveAmber)
            }
            item {
                Paragraph("This screen goes away by itself once the switch is on.")
            }
            item {
                Chip(
                    label = {
                        Text("Open app settings on watch", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    },
                    onClick = { noSettingsPage = !BackgroundActivityPolicy.openAppInfo(context) },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (noSettingsPage) {
                item {
                    Paragraph("No settings page on this watch — use the Galaxy Wearable app on the phone.", DiveAmber)
                }
            }
            item {
                Chip(
                    label = {
                        Text("Continue anyway (not for diving)", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    },
                    onClick = onContinueAnyway,
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Paragraph(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colors.onBackground) {
    Text(
        text,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        color = color,
        modifier = Modifier.fillMaxWidth(),
    )
}
