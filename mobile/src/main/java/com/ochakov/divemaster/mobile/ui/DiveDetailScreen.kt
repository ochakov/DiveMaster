package com.ochakov.divemaster.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ochakov.divemaster.data.db.DiveEntity
import com.ochakov.divemaster.data.db.DiveMasterDatabase
import com.ochakov.divemaster.data.db.SampleEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val DETAIL_DATE_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy · HH:mm")

@Composable
fun DiveDetailScreen(diveId: Long, metric: Boolean) {
    val context = LocalContext.current
    val dao = remember { DiveMasterDatabase.get(context).diveDao() }
    var dive by remember { mutableStateOf<DiveEntity?>(null) }
    var samples by remember { mutableStateOf<List<SampleEntity>>(emptyList()) }
    LaunchedEffect(diveId) {
        dive = dao.dive(diveId)
        samples = dao.samplesFor(diveId)
    }
    val currentDive = dive ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            Instant.ofEpochMilli(currentDive.startEpochMs).atZone(ZoneId.systemDefault())
                .format(DETAIL_DATE_FMT),
            style = MaterialTheme.typography.titleLarge,
        )
        DepthChart(
            samples = samples,
            maxDepthM = currentDive.maxDepthM,
            durationSec = currentDive.durationSec,
            metric = metric,
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
        )

        val o2 = (currentDive.gasO2Fraction * 100).roundToInt()
        val minNdl = samples.mapNotNull { it.ndlMin }.minOrNull()
        val stats = listOf(
            "Duration" to "%d:%02d min".format(currentDive.durationSec / 60, currentDive.durationSec % 60),
            "Max depth" to Units.depthWithUnit(currentDive.maxDepthM, metric),
            "Avg depth" to Units.depthWithUnit(currentDive.avgDepthM, metric),
            "Min temp" to (currentDive.minTempC?.let { Units.temp(it, metric) } ?: "—"),
            "Gas" to if (o2 == 21) "Air" else "EAN$o2",
            "Gradient factors" to "${currentDive.gfLow}/${currentDive.gfHigh}",
            "Lowest NDL" to (minNdl?.let { "${it.roundToInt()} min" } ?: "—"),
            "Water type" to currentDive.waterType,
            "Surface pressure" to "%.0f mbar".format(currentDive.surfacePressureMbar),
            "Samples" to "${samples.size}",
        )
        stats.chunked(2).forEach { rowPair ->
            Row(Modifier.fillMaxWidth()) {
                rowPair.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Text(value, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

/** Depth profile: surface at the top, depth increasing downward. */
@Composable
private fun DepthChart(
    samples: List<SampleEntity>,
    maxDepthM: Double,
    durationSec: Long,
    metric: Boolean,
    modifier: Modifier = Modifier,
) {
    if (samples.size < 2) {
        Text("No profile samples synced for this dive.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val gridColor = Color.White.copy(alpha = 0.12f)
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val tMax = samples.last().tOffsetSec.toFloat().coerceAtLeast(1f)
            val dMax = (maxDepthM * 1.08).coerceAtLeast(1.0).toFloat()

            val depthStep = if (dMax > 25f) 10f else 5f
            var gridDepth = depthStep
            while (gridDepth < dMax) {
                val y = gridDepth / dMax * h
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                gridDepth += depthStep
            }
            val timeStepSec = 600f
            var gridTime = timeStepSec
            while (gridTime < tMax) {
                val x = gridTime / tMax * w
                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                gridTime += timeStepSec
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
            drawPath(fill, DiveCyan.copy(alpha = 0.14f))
            drawPath(line, DiveCyan, style = Stroke(width = 4f))
        }
        Text(
            "0 ${Units.depthUnit(metric)}",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
        )
        Text(
            Units.depthWithUnit(maxDepthM, metric),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
        )
        Text(
            "%d:%02d".format(durationSec / 60, durationSec % 60),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
        )
    }
}
