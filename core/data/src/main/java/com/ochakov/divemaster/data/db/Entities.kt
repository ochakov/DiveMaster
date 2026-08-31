package com.ochakov.divemaster.data.db

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
