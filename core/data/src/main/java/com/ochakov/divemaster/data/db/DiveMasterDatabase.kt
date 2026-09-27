package com.ochakov.divemaster.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DiveEntity::class, SampleEntity::class, TissueStateEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class DiveMasterDatabase : RoomDatabase() {
    abstract fun diveDao(): DiveDao

    companion object {
        @Volatile
        private var instance: DiveMasterDatabase? = null

        /** v2: dives learn whether they started underwater (app restarted mid-dive). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dives ADD COLUMN startedUnderwater INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): DiveMasterDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DiveMasterDatabase::class.java,
                    "divemaster.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
