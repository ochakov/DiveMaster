package com.ochakov.divemaster.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.ochakov.divemaster.data.settings.SettingsRepository
import com.ochakov.divemaster.ui.theme.DiveAmber
import kotlinx.coroutines.launch

/**
 * First-run safety gate. Blocks the whole app until accepted once;
 * acceptance is persisted and the screen never returns.
 */
@Composable
fun DisclaimerScreen() {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    val listState = rememberScalingLazyListState()
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    "READ BEFORE USE",
                    style = MaterialTheme.typography.title3,
                    color = DiveAmber,
                )
            }
            item {
                Paragraph(
                    "DiveMaster is EXPERIMENTAL software. It is NOT a certified dive computer.",
                )
            }
            item {
                Paragraph(
                    "Use it only as a secondary instrument alongside a certified dive computer, never instead of one.",
                )
            }
            item {
                Paragraph(
                    "Depth readings and decompression calculations may be wrong. The pressure sensor's true depth limit on this watch is unverified.",
                )
            }
            item {
                Paragraph(
                    "Your watch must be dive-rated by its manufacturer (10 ATM or better). Most smartwatches are not safe to dive with.",
                )
            }
            item {
                Paragraph(
                    "Scuba diving requires training and certification. Never let this app drive decompression, oxygen, or ascent decisions.",
                )
            }
            item {
                Paragraph("By continuing you accept all risk of using this app.")
            }
            item {
                Chip(
                    label = {
                        Text(
                            "I understand and accept",
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    onClick = { scope.launch { repository.setDisclaimerAccepted() } },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Paragraph(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colors.onBackground,
        modifier = Modifier.fillMaxWidth(),
    )
}
