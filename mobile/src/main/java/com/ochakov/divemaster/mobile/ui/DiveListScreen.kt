package com.ochakov.divemaster.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ochakov.divemaster.data.db.DiveEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val LIST_DATE_FMT = DateTimeFormatter.ofPattern("EEE, d MMM yyyy · HH:mm")

@Composable
fun DiveListScreen(
    dives: List<DiveEntity>,
    metric: Boolean,
    syncStatus: String?,
    onOpen: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        syncStatus?.let { status ->
            item {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
        if (dives.isEmpty()) {
            item {
                Column {
                    Text("No dives yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Open DiveMaster on the watch with Bluetooth connected — logged dives sync here automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }
        } else {
            items(dives.size) { index ->
                val dive = dives[index]
                val o2 = (dive.gasO2Fraction * 100).roundToInt()
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(dive.id) },
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            Instant.ofEpochMilli(dive.startEpochMs).atZone(ZoneId.systemDefault())
                                .format(LIST_DATE_FMT),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "${Units.depthWithUnit(dive.maxDepthM, metric)} max · " +
                                "${dive.durationSec / 60} min · ${if (o2 == 21) "Air" else "EAN$o2"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
