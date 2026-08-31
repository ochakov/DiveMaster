package com.ochakov.divemaster.ui.surface

import android.app.Application
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import com.ochakov.divemaster.data.db.DiveMasterDatabase
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SurfaceViewModel(application: Application) : AndroidViewModel(application) {
    val lastDive = DiveMasterDatabase.get(application).diveDao().observeLatest()
}

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DATE_FMT = DateTimeFormatter.ofPattern("EEE, d MMM")
private val DIVE_DATE_FMT = DateTimeFormatter.ofPattern("d MMM · HH:mm")

@Composable
fun SurfaceScreen(
    onOpenLog: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProbe: () -> Unit,
    onOpenDivePreview: () -> Unit,
    viewModel: SurfaceViewModel = viewModel(),
) {
    val lastDive by viewModel.lastDive.collectAsState(initial = null)
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }

    val listState = rememberScalingLazyListState()
    Scaffold(
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    now.format(TIME_FMT),
                    style = MaterialTheme.typography.display2,
                    color = MaterialTheme.colors.primary,
                )
            }
            item {
                Text(
                    now.format(DATE_FMT),
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                )
            }
            item { Spacer(Modifier.height(6.dp)) }
            item {
                Text(
                    "LAST DIVE",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.secondary,
                )
            }
            val dive = lastDive
            if (dive == null) {
                item {
                    Text(
                        "No dives yet",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                    )
                }
            } else {
                item {
                    Text(
                        "%.1f m max · %d min".format(dive.maxDepthM, dive.durationSec / 60),
                        style = MaterialTheme.typography.body1,
                    )
                }
                item {
                    val temp = dive.minTempC?.let { " · %.0f°C".format(it) } ?: ""
                    Text(
                        "%.1f m avg%s".format(dive.avgDepthM, temp),
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.8f),
                    )
                }
                item {
                    Text(
                        Instant.ofEpochMilli(dive.startEpochMs).atZone(ZoneId.systemDefault()).format(DIVE_DATE_FMT),
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                    )
                }
            }
            item { Spacer(Modifier.height(6.dp)) }
            item {
                Chip(
                    label = { Text("Dive log") },
                    onClick = onOpenLog,
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Chip(
                    label = { Text("Settings") },
                    onClick = onOpenSettings,
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Chip(
                    label = { Text("Sensor probe") },
                    onClick = onOpenProbe,
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                CompactChip(
                    label = { Text("Dive view preview") },
                    onClick = onOpenDivePreview,
                    colors = ChipDefaults.childChipColors(),
                )
            }
        }
    }
}
