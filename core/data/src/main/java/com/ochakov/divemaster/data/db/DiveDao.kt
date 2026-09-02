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

    @Query("SELECT * FROM dives WHERE id = :diveId")
    suspend fun dive(diveId: Long): DiveEntity?

    @Query("SELECT * FROM samples WHERE diveId = :diveId ORDER BY tOffsetSec")
    suspend fun samplesFor(diveId: Long): List<SampleEntity>

    /** Drops the surface-interval tail recorded while waiting out the end-of-dive hold. */
    @Query("DELETE FROM samples WHERE diveId = :diveId AND tOffsetSec > :offsetSec")
    suspend fun trimSamplesAfter(diveId: Long, offsetSec: Int)

    @Query("DELETE FROM dives WHERE id = :diveId")
    suspend fun deleteDive(diveId: Long)

    @Query("SELECT * FROM tissue_state WHERE id = 0")
    suspend fun tissueState(): TissueStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTissueState(state: TissueStateEntity)
}
