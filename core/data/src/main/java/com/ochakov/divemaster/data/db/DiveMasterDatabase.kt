package com.ochakov.divemaster.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DiveEntity::class, SampleEntity::class, TissueStateEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class DiveMasterDatabase : RoomDatabase() {
    abstract fun diveDao(): DiveDao

    companion object {
        @Volatile
        private var instance: DiveMasterDatabase? = null

        fun get(context: Context): DiveMasterDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DiveMasterDatabase::class.java,
                    "divemaster.db",
                ).build().also { instance = it }
            }
    }
}
