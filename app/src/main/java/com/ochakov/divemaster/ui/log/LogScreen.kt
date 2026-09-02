package com.ochakov.divemaster.ui.log

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.ochakov.divemaster.data.db.DiveMasterDatabase
import com.ochakov.divemaster.data.settings.DiveSettings
import com.ochakov.divemaster.data.settings.SettingsRepository
import com.ochakov.divemaster.ui.Units
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LogViewModel(application: Application) : AndroidViewModel(application) {
    val dives = DiveMasterDatabase.get(application).diveDao().observeAll()
}

private val LOG_DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm")

@Composable
fun LogScreen(onOpenDive: (Long) -> Unit, viewModel: LogViewModel = viewModel()) {
    val dives by viewModel.dives.collectAsState(initial = emptyList())
    val context = LocalContext.current
    val settings by remember { SettingsRepository(context) }.settings.collectAsState(initial = DiveSettings())

    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item { Text("DIVE LOG", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.secondary) }
            if (dives.isEmpty()) {
                item {
                    Text(
                        "No dives yet.\nDives are detected and logged automatically once you submerge.",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                items(dives.size) { i ->
                    val dive = dives[i]
                    Card(onClick = { onOpenDive(dive.id) }) {
                        Text(
                            "${Units.depthWithUnit(dive.maxDepthM, settings.metricUnits)} · ${dive.durationSec / 60} min",
                            style = MaterialTheme.typography.title3,
                        )
                        Text(
                            Instant.ofEpochMilli(dive.startEpochMs).atZone(ZoneId.systemDefault()).format(LOG_DATE_FMT),
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}
