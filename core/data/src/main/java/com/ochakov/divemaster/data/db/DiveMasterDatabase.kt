package com.ochakov.divemaster.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DiveEntity::class, SampleEntity::class, TissueStateEntity::class],
    version = 3,
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

        /** v3: battery, safety-stop result, max ascent rate, CNS, app version, entry/exit position. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (column in listOf(
                    "batteryStartPct INTEGER", "batteryEndPct INTEGER",
                    "safetyStopResult TEXT", "maxAscentRateMPerMin REAL",
                    "cnsEndFraction REAL", "appVersion TEXT",
                    "entryLat REAL", "entryLon REAL", "entryAccuracyM REAL", "entryFixEpochMs INTEGER",
                    "exitLat REAL", "exitLon REAL", "exitAccuracyM REAL", "exitFixEpochMs INTEGER",
                )) {
                    db.execSQL("ALTER TABLE dives ADD COLUMN $column")
                }
            }
        }

        fun get(context: Context): DiveMasterDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DiveMasterDatabase::class.java,
                    "divemaster.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
