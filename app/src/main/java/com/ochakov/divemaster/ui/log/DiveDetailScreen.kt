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
private val FILE_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")

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
                val rows = listOf(
                    "Duration" to "%d:%02d".format(currentDive.durationSec / 60, currentDive.durationSec % 60),
                    "Max depth" to Units.depthWithUnit(currentDive.maxDepthM, metric),
                    "Avg depth" to Units.depthWithUnit(currentDive.avgDepthM, metric),
                    "Min temp" to (currentDive.minTempC?.let { Units.temp(it, metric) } ?: "—"),
                    "Gas" to if (o2 == 21) "Air" else "EAN$o2",
                    "GF" to "${currentDive.gfLow}/${currentDive.gfHigh}",
                    "Water" to currentDive.waterType,
                    "Surface" to "%.0f mbar".format(currentDive.surfacePressureMbar),
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
 * Subsurface-importable CSV: comment header with dive metadata, then
 * time_sec,depth_m,temp_c,ndl_min rows. Locale.US keeps decimal points
 * valid regardless of device locale. Written to the app-specific external
 * dir (no permissions needed): pull via
 * `adb pull /sdcard/Android/data/com.ochakov.divemaster/files/`.
 */
private fun writeCsv(context: android.content.Context, dive: DiveEntity, samples: List<SampleEntity>): String {
    val stamp = FILE_DATE_FMT.format(Instant.ofEpochMilli(dive.startEpochMs).atZone(ZoneId.systemDefault()))
    val dir = context.getExternalFilesDir(null) ?: context.filesDir
    val file = File(dir, "DiveMaster_dive${dive.id}_$stamp.csv")
    file.bufferedWriter().use { writer ->
        writer.appendLine("# DiveMaster dive ${dive.id}")
        writer.appendLine(
            String.format(
                Locale.US,
                "# startEpochMs=%d endEpochMs=%d maxDepthM=%.2f avgDepthM=%.2f gasO2=%.2f gf=%d/%d water=%s surfaceMbar=%.1f",
                dive.startEpochMs, dive.endEpochMs, dive.maxDepthM, dive.avgDepthM,
                dive.gasO2Fraction, dive.gfLow, dive.gfHigh, dive.waterType, dive.surfacePressureMbar,
            ),
        )
        writer.appendLine("time_sec,depth_m,temp_c,ndl_min")
        for (sample in samples) {
            val temp = sample.tempC?.let { String.format(Locale.US, "%.1f", it) } ?: ""
            val ndl = sample.ndlMin?.let { String.format(Locale.US, "%.1f", it) } ?: ""
            writer.appendLine(
                String.format(Locale.US, "%d,%.2f,%s,%s", sample.tOffsetSec, sample.depthM, temp, ndl),
            )
        }
    }
    return file.absolutePath
}
