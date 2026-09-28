package com.ochakov.divemaster.ui.log

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.ochakov.divemaster.data.db.DiveEntity
import com.ochakov.divemaster.data.db.DiveMasterDatabase
import com.ochakov.divemaster.data.db.SampleEntity
import com.ochakov.divemaster.data.export.DiveCsv
import com.ochakov.divemaster.data.settings.DiveSettings
import com.ochakov.divemaster.data.settings.SettingsRepository
import com.ochakov.divemaster.service.DiveSyncPublisher
import com.ochakov.divemaster.ui.Units
import com.ochakov.divemaster.ui.theme.DiveCyan
import com.ochakov.divemaster.ui.theme.DiveRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val DETAIL_DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm")

private fun stopResultText(name: String): String = when (name) {
    "DONE" -> "done"
    "INCOMPLETE" -> "incomplete"
    "NOT_REQUIRED" -> "not required"
    else -> name.lowercase()
}

/** Exit fix preferred; the entry position is a last-known one and says so. */
private fun positionText(dive: DiveEntity): String? {
    fun fmt(lat: Double, lon: Double, acc: Double?, suffix: String) =
        String.format(Locale.US, "%.5f, %.5f", lat, lon) +
            (acc?.takeIf { it >= 0 }?.let { String.format(Locale.US, " (±%.0f m)", it) } ?: "") + suffix
    val exitLat = dive.exitLat
    val exitLon = dive.exitLon
    if (exitLat != null && exitLon != null) return fmt(exitLat, exitLon, dive.exitAccuracyM, "")
    val entryLat = dive.entryLat
    val entryLon = dive.entryLon
    if (entryLat != null && entryLon != null) return fmt(entryLat, entryLon, dive.entryAccuracyM, " (entry)")
    return null
}

@Composable
fun DiveDetailScreen(diveId: Long, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val settings by remember { SettingsRepository(context) }.settings.collectAsState(initial = DiveSettings())
    val metric = settings.metricUnits
    val dao = remember { DiveMasterDatabase.get(context).diveDao() }
    val scope = rememberCoroutineScope()

    var dive by remember { mutableStateOf<DiveEntity?>(null) }
    var samples by remember { mutableStateOf<List<SampleEntity>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var exportedPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(diveId) {
        dive = dao.dive(diveId)
        samples = dao.samplesFor(diveId)
    }

    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            val currentDive = dive
            if (currentDive == null) {
                item { Text("Dive not found", style = MaterialTheme.typography.body2) }
            } else {
                item {
                    Text(
                        Instant.ofEpochMilli(currentDive.startEpochMs).atZone(ZoneId.systemDefault())
                            .format(DETAIL_DATE_FMT),
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.secondary,
                    )
                }
                item {
                    DepthChart(
                        samples = samples,
                        maxDepthM = currentDive.maxDepthM,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(90.dp)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                item {
                    Text(
                        "0–${Units.depthWithUnit(currentDive.maxDepthM, metric)} · ${samples.size} samples",
                        fontSize = 9.sp,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                    )
                }

                val o2 = (currentDive.gasO2Fraction * 100).roundToInt()
                val rows = listOfNotNull(
                    ("Late start" to "began underwater").takeIf { currentDive.startedUnderwater },
                    "Duration" to "%d:%02d".format(currentDive.durationSec / 60, currentDive.durationSec % 60),
                    "Max depth" to Units.depthWithUnit(currentDive.maxDepthM, metric),
                    "Avg depth" to Units.depthWithUnit(currentDive.avgDepthM, metric),
                    "Min temp" to (currentDive.minTempC?.let { Units.temp(it, metric) } ?: "—"),
                    "Gas" to if (o2 == 21) "Air" else "EAN$o2",
                    "GF" to "${currentDive.gfLow}/${currentDive.gfHigh}",
                    "Water" to currentDive.waterType,
                    "Surface" to "%.0f mbar".format(currentDive.surfacePressureMbar),
                    ("Battery" to "${currentDive.batteryStartPct?.let { "$it%" } ?: "?"} → ${currentDive.batteryEndPct?.let { "$it%" } ?: "?"}")
                        .takeIf { currentDive.batteryStartPct != null || currentDive.batteryEndPct != null },
                    currentDive.safetyStopResult?.let { "Safety stop" to stopResultText(it) },
                    currentDive.maxAscentRateMPerMin?.let { "Max ascent" to Units.rate(it, metric) },
                    currentDive.cnsEndFraction?.let { "CNS at end" to "%.0f%%".format(it * 100) },
                    positionText(currentDive)?.let { "Position" to it },
                    currentDive.appVersion?.let { "App" to it },
                )
                items(rows.size) { i ->
                    val (label, value) = rows[i]
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(label, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f))
                        Text(value, style = MaterialTheme.typography.body1)
                    }
                }

                item { Spacer(Modifier.height(4.dp)) }
                item {
                    Chip(
                        label = { Text("Export CSV") },
                        onClick = {
                            scope.launch {
                                val path = withContext(Dispatchers.IO) { writeCsv(context, currentDive, samples) }
                                exportedPath = path
                            }
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val path = exportedPath
                if (path != null) {
                    item {
                        Text(
                            "Saved:\n$path\nPull with adb, import into Subsurface (CSV).",
                            fontSize = 9.sp,
                            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                item {
                    if (!confirmDelete) {
                        Chip(
                            label = { Text("Delete dive") },
                            onClick = { confirmDelete = true },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Chip(
                            label = { Text("Confirm delete") },
                            onClick = {
                                scope.launch {
                                    dao.deleteDive(diveId)
                                    // Stop it re-syncing; phone archives keep their copy.
                                    DiveSyncPublisher(context, dao).unpublish(currentDive.startEpochMs)
                                    onDeleted()
                                }
                            },
                            colors = ChipDefaults.primaryChipColors(
                                backgroundColor = DiveRed,
                                contentColor = Color.Black,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** Depth profile: surface at the top, depth increasing downward. */
@Composable
private fun DepthChart(samples: List<SampleEntity>, maxDepthM: Double, modifier: Modifier = Modifier) {
    if (samples.size < 2) {
        Text("No profile samples", style = MaterialTheme.typography.caption2)
        return
    }
    val gridColor = Color.White.copy(alpha = 0.15f)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val tMax = samples.last().tOffsetSec.toFloat().coerceAtLeast(1f)
        val dMax = (maxDepthM * 1.08).coerceAtLeast(1.0).toFloat()

        val stepM = if (dMax > 25f) 10f else 5f
        var grid = stepM
        while (grid < dMax) {
            val y = grid / dMax * h
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            grid += stepM
        }

        val line = Path()
        line.moveTo(0f, 0f)
        for (sample in samples) {
            line.lineTo(sample.tOffsetSec / tMax * w, (sample.depthM / dMax).toFloat() * h)
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(w, 0f)
            close()
        }
        drawPath(fill, DiveCyan.copy(alpha = 0.15f))
        drawPath(line, DiveCyan, style = Stroke(width = 3f))
    }
}

/**
 * Subsurface-importable CSV (format shared with the phone app in
 * `DiveCsv`), written to the app-specific external dir (no permissions
 * needed): pull via `adb pull /sdcard/Android/data/com.ochakov.divemaster/files/`.
 */
private fun writeCsv(context: android.content.Context, dive: DiveEntity, samples: List<SampleEntity>): String {
    val dir = context.getExternalFilesDir(null) ?: context.filesDir
    val file = File(dir, DiveCsv.fileName(dive))
    file.bufferedWriter().use { DiveCsv.write(dive, samples, it) }
    return file.absolutePath
}
