package com.ochakov.divemaster.ui.log

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LogViewModel(application: Application) : AndroidViewModel(application) {
    val dives = DiveMasterDatabase.get(application).diveDao().observeAll()
}

private val LOG_DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm")

@Composable
fun LogScreen(viewModel: LogViewModel = viewModel()) {
    val dives by viewModel.dives.collectAsState(initial = emptyList())

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
                    Card(onClick = { /* dive detail arrives in Phase 7 */ }) {
                        Text(
                            "%.1f m · %d min".format(dive.maxDepthM, dive.durationSec / 60),
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
