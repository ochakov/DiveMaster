package com.ochakov.divemaster.data.export

import com.ochakov.divemaster.data.db.DiveEntity
import com.ochakov.divemaster.data.db.SampleEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Subsurface-importable CSV, shared by the watch (file in its app-specific
 * dir, pulled over adb) and the phone (share sheet / ZIP): comment headers
 * with the dive's metadata, then `time_sec,depth_m,temp_c,ndl_min` rows.
 * Locale.US keeps decimal points valid regardless of device locale; absent
 * metadata is written as `-`.
 */
object DiveCsv {
    private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")

    /** `DiveMaster_dive<id>_<yyyyMMdd_HHmm>.csv`, stamped with the dive's local start time. */
    fun fileName(dive: DiveEntity, zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = FILE_STAMP.format(Instant.ofEpochMilli(dive.startEpochMs).atZone(zone))
        return "DiveMaster_dive${dive.id}_$stamp.csv"
    }

    fun write(dive: DiveEntity, samples: List<SampleEntity>, out: Appendable) {
        out.appendLine("# DiveMaster dive ${dive.id}")
        out.appendLine(
            String.format(
                Locale.US,
                "# startEpochMs=%d endEpochMs=%d maxDepthM=%.2f avgDepthM=%.2f gasO2=%.2f gf=%d/%d water=%s surfaceMbar=%.1f lateStart=%d",
                dive.startEpochMs, dive.endEpochMs, dive.maxDepthM, dive.avgDepthM,
                dive.gasO2Fraction, dive.gfLow, dive.gfHigh, dive.waterType, dive.surfacePressureMbar,
                if (dive.startedUnderwater) 1 else 0,
            ),
        )
        out.appendLine(
            "# batteryStart=${dive.batteryStartPct ?: "-"} batteryEnd=${dive.batteryEndPct ?: "-"}" +
                " safetyStop=${dive.safetyStopResult ?: "-"}" +
                " maxAscentMPerMin=${dive.maxAscentRateMPerMin?.let { String.format(Locale.US, "%.1f", it) } ?: "-"}" +
                " cnsEnd=${dive.cnsEndFraction?.let { String.format(Locale.US, "%.3f", it) } ?: "-"}" +
                " app=${dive.appVersion ?: "-"}",
        )
        out.appendLine(
            "# entryLat=${coord(dive.entryLat)} entryLon=${coord(dive.entryLon)} entryFixEpochMs=${dive.entryFixEpochMs ?: "-"}" +
                " exitLat=${coord(dive.exitLat)} exitLon=${coord(dive.exitLon)} exitFixEpochMs=${dive.exitFixEpochMs ?: "-"}",
        )
        out.appendLine("time_sec,depth_m,temp_c,ndl_min")
        for (sample in samples) {
            val temp = sample.tempC?.let { String.format(Locale.US, "%.1f", it) } ?: ""
            val ndl = sample.ndlMin?.let { String.format(Locale.US, "%.1f", it) } ?: ""
            out.appendLine(String.format(Locale.US, "%d,%.2f,%s,%s", sample.tOffsetSec, sample.depthM, temp, ndl))
        }
    }

    fun render(dive: DiveEntity, samples: List<SampleEntity>): String =
        StringBuilder(samples.size * 24 + 512).also { write(dive, samples, it) }.toString()

    private fun coord(value: Double?): String = value?.let { String.format(Locale.US, "%.6f", it) } ?: "-"
}
