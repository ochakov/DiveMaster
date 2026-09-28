package com.ochakov.divemaster.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DiveDao {
    @Insert
    suspend fun insertDive(dive: DiveEntity): Long

    @Update
    suspend fun updateDive(dive: DiveEntity)

    @Insert
    suspend fun insertSamples(samples: List<SampleEntity>)

    @Query("SELECT * FROM dives WHERE endEpochMs > 0 ORDER BY startEpochMs DESC LIMIT 1")
    fun observeLatest(): Flow<DiveEntity?>

    @Query("SELECT * FROM dives WHERE endEpochMs > 0 ORDER BY startEpochMs DESC")
    fun observeAll(): Flow<List<DiveEntity>>

    /** Dives whose end was never written — the app died mid-dive. */
    @Query("SELECT * FROM dives WHERE endEpochMs = 0")
    suspend fun openDives(): List<DiveEntity>

    @Query("SELECT * FROM dives WHERE endEpochMs > 0 ORDER BY startEpochMs DESC")
    suspend fun allFinalized(): List<DiveEntity>

    /** Start time is the stable cross-device identity of a dive. */
    @Query("SELECT * FROM dives WHERE startEpochMs = :startEpochMs LIMIT 1")
    suspend fun diveByStart(startEpochMs: Long): DiveEntity?

    @Query("SELECT * FROM dives WHERE id = :diveId")
    suspend fun dive(diveId: Long): DiveEntity?

    @Query("SELECT * FROM samples WHERE diveId = :diveId ORDER BY tOffsetSec")
    suspend fun samplesFor(diveId: Long): List<SampleEntity>

    /** Drops the surface-interval tail recorded while waiting out the end-of-dive hold. */
    @Query("DELETE FROM samples WHERE diveId = :diveId AND tOffsetSec > :offsetSec")
    suspend fun trimSamplesAfter(diveId: Long, offsetSec: Int)

    /** Re-bases a dive's profile after the engine corrected its surface reference mid-dive. */
    @Query("UPDATE samples SET depthM = depthM + :shiftM WHERE diveId = :diveId")
    suspend fun shiftSampleDepths(diveId: Long, shiftM: Double)

    /** The exit fix often lands after the dive is already finalized. */
    @Query(
        "UPDATE dives SET exitLat = :lat, exitLon = :lon, exitAccuracyM = :accuracyM, " +
            "exitFixEpochMs = :fixEpochMs WHERE id = :diveId",
    )
    suspend fun updateExitLocation(diveId: Long, lat: Double, lon: Double, accuracyM: Double, fixEpochMs: Long)

    @Query("DELETE FROM dives WHERE id = :diveId")
    suspend fun deleteDive(diveId: Long)

    @Query("SELECT * FROM tissue_state WHERE id = 0")
    suspend fun tissueState(): TissueStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTissueState(state: TissueStateEntity)
}
