package com.ochakov.divemaster.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "dives")
data class DiveEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val maxDepthM: Double,
    val avgDepthM: Double,
    val minTempC: Double?,
    val gasO2Fraction: Double,
    val waterType: String,
    val surfacePressureMbar: Double,
    val gfLow: Int,
    val gfHigh: Int,
    /**
     * The app was (re)started underwater and this dive's clock began then:
     * its first minutes are missing and its NDL trace is optimistic.
     */
    @ColumnInfo(defaultValue = "0") val startedUnderwater: Boolean = false,

    // --- v3 (2026-09-28, Ev): richer records. All nullable — dives recorded
    // --- earlier, or finalized after a crash, simply lack them.

    /** Watch battery when the dive started / ended. */
    val batteryStartPct: Int? = null,
    val batteryEndPct: Int? = null,
    /** Engine SafetyStopResult name: NOT_REQUIRED / DONE / INCOMPLETE. */
    val safetyStopResult: String? = null,
    /** Worst ascent rate of the dive (engine's 8 s window), m/min. */
    val maxAscentRateMPerMin: Double? = null,
    /** NOAA CNS clock at surfacing, as a fraction (0.5 = 50 %). */
    val cnsEndFraction: Double? = null,
    /** versionName of the app that recorded the dive. */
    val appVersion: String? = null,
    /** Last known position when the dive started — may predate the dive; see [entryFixEpochMs]. */
    val entryLat: Double? = null,
    val entryLon: Double? = null,
    val entryAccuracyM: Double? = null,
    val entryFixEpochMs: Long? = null,
    /** Fresh fix requested at the first surface touch after the dive. */
    val exitLat: Double? = null,
    val exitLon: Double? = null,
    val exitAccuracyM: Double? = null,
    val exitFixEpochMs: Long? = null,
) {
    val durationSec: Long get() = (endEpochMs - startEpochMs) / 1000
}

@Entity(
    tableName = "samples",
    foreignKeys = [
        ForeignKey(
            entity = DiveEntity::class,
            parentColumns = ["id"],
            childColumns = ["diveId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("diveId")],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val diveId: Long,
    val tOffsetSec: Int,
    val depthM: Double,
    val tempC: Double?,
    val ndlMin: Double?,
)

/**
 * Single-row table (id = 0) holding the latest tissue tensions and CNS clock,
 * so repetitive dives stay correct across app restarts and reboots.
 */
@Entity(tableName = "tissue_state")
data class TissueStateEntity(
    @PrimaryKey val id: Int = 0,
    val updatedEpochMs: Long,
    /** 16 comma-separated nitrogen tensions in bar. */
    val n2BarCsv: String,
    val cnsFraction: Double = 0.0,
)
