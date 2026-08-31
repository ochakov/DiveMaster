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
import androidx.compose.ui.platform.LocalContext
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
import com.ochakov.divemaster.data.settings.DiveSettings
import com.ochakov.divemaster.data.settings.SettingsRepository
import com.ochakov.divemaster.engine.DivePhase
import com.ochakov.divemaster.service.DiveService
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
    onOpenDive: () -> Unit,
    viewModel: SurfaceViewModel = viewModel(),
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val settings by settingsRepository.settings.collectAsState(initial = DiveSettings())
    val engineState by DiveService.displayState.collectAsState()
    val simRunning by DiveService.simulatorRunning.collectAsState()
    val diving = engineState?.phase == DivePhase.DIVING

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
            if (diving) {
                item {
                    Chip(
                        label = { Text(if (simRunning) "Dive in progress (SIM)" else "Dive in progress") },
                        onClick = onOpenDive,
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
                if (!diving) {
                    item {
                        val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val intervalSec = ((nowMs - dive.endEpochMs) / 1000).coerceAtLeast(0)
                        val text = if (intervalSec < 3600) {
                            "%d min".format(intervalSec / 60)
                        } else {
                            "%dh %02dm".format(intervalSec / 3600, (intervalSec % 3600) / 60)
                        }
                        Text(
                            "Surface interval $text",
                            style = MaterialTheme.typography.caption1,
                            color = MaterialTheme.colors.primary.copy(alpha = 0.9f),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(6.dp)) }
            if (settings.simulatorEnabled && !simRunning) {
                item {
                    Chip(
                        label = { Text("Start simulated dive") },
                        onClick = {
                            DiveService.start(context, DiveService.ACTION_START_SIM)
                            onOpenDive()
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (simRunning) {
                item {
                    Chip(
                        label = { Text("Stop simulation") },
                        onClick = { DiveService.start(context, DiveService.ACTION_STOP_SIM) },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
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
                    onClick = onOpenDive,
                    colors = ChipDefaults.childChipColors(),
                )
            }
        }
    }
}
