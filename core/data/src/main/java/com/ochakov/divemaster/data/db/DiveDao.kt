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

    @Query("SELECT * FROM dives ORDER BY startEpochMs DESC LIMIT 1")
    fun observeLatest(): Flow<DiveEntity?>

    @Query("SELECT * FROM dives ORDER BY startEpochMs DESC")
    fun observeAll(): Flow<List<DiveEntity>>

    @Query("SELECT * FROM samples WHERE diveId = :diveId ORDER BY tOffsetSec")
    suspend fun samplesFor(diveId: Long): List<SampleEntity>

    @Query("DELETE FROM dives WHERE id = :diveId")
    suspend fun deleteDive(diveId: Long)

    @Query("SELECT * FROM tissue_state WHERE id = 0")
    suspend fun tissueState(): TissueStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTissueState(state: TissueStateEntity)
}
