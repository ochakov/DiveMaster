package com.ochakov.divemaster.mobile.sync

import android.content.Context
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.ochakov.divemaster.data.db.DiveEntity
import com.ochakov.divemaster.data.db.DiveMasterDatabase
import com.ochakov.divemaster.data.transfer.DiveSyncKeys
import com.ochakov.divemaster.data.transfer.DiveTransferCodec
import kotlinx.coroutines.tasks.await

/**
 * Pulls dives out of the Wearable Data Layer into the phone's own Room
 * database. Archive semantics: once imported, a dive stays in the phone
 * logbook even if it is later deleted on the watch. An already-imported dive
 * is only touched to back-fill an exit position that arrived on the watch
 * after the first sync. Returns the number of newly imported dives, or -1
 * when the Data Layer is unreachable.
 */
class SyncRepository(private val context: Context) {

    private val dao = DiveMasterDatabase.get(context).diveDao()

    suspend fun importFromDataLayer(): Int = runCatching {
        val dataClient = Wearable.getDataClient(context)
        var imported = 0
        val buffer = dataClient.dataItems.await()
        try {
            for (item in buffer) {
                val path = item.uri.path ?: continue
                if (!path.startsWith(DiveSyncKeys.PATH_PREFIX)) continue
                val map = DataMapItem.fromDataItem(item).dataMap
                val start = map.getLong(DiveSyncKeys.KEY_START)
                if (start <= 0) continue

                val existing = dao.diveByStart(start)
                if (existing != null) {
                    val lat = map.optDouble(DiveSyncKeys.KEY_EXIT_LAT)
                    val lon = map.optDouble(DiveSyncKeys.KEY_EXIT_LON)
                    if (existing.exitLat == null && lat != null && lon != null) {
                        dao.updateExitLocation(
                            existing.id, lat, lon,
                            map.optDouble(DiveSyncKeys.KEY_EXIT_ACC) ?: -1.0,
                            map.optLong(DiveSyncKeys.KEY_EXIT_AT) ?: 0L,
                        )
                    }
                    continue
                }
                val asset = map.getAsset(DiveSyncKeys.KEY_SAMPLES) ?: continue
                val diveId = dao.insertDive(
                    DiveEntity(
                        startEpochMs = start,
                        endEpochMs = map.getLong(DiveSyncKeys.KEY_END),
                        maxDepthM = map.getDouble(DiveSyncKeys.KEY_MAX_DEPTH),
                        avgDepthM = map.getDouble(DiveSyncKeys.KEY_AVG_DEPTH),
                        minTempC = map.optDouble(DiveSyncKeys.KEY_MIN_TEMP),
                        gasO2Fraction = map.getDouble(DiveSyncKeys.KEY_GAS_O2, 0.21),
                        waterType = map.getString(DiveSyncKeys.KEY_WATER) ?: "EN13319",
                        surfacePressureMbar = map.getDouble(DiveSyncKeys.KEY_SURFACE_MBAR, 1013.25),
                        gfLow = map.getInt(DiveSyncKeys.KEY_GF_LOW, 40),
                        gfHigh = map.getInt(DiveSyncKeys.KEY_GF_HIGH, 85),
                        startedUnderwater = map.getBoolean(DiveSyncKeys.KEY_STARTED_UNDERWATER, false),
                        batteryStartPct = map.optInt(DiveSyncKeys.KEY_BATTERY_START),
                        batteryEndPct = map.optInt(DiveSyncKeys.KEY_BATTERY_END),
                        safetyStopResult = map.optString(DiveSyncKeys.KEY_STOP_RESULT),
                        maxAscentRateMPerMin = map.optDouble(DiveSyncKeys.KEY_MAX_ASCENT),
                        cnsEndFraction = map.optDouble(DiveSyncKeys.KEY_CNS_END),
                        appVersion = map.optString(DiveSyncKeys.KEY_APP_VERSION),
                        entryLat = map.optDouble(DiveSyncKeys.KEY_ENTRY_LAT),
                        entryLon = map.optDouble(DiveSyncKeys.KEY_ENTRY_LON),
                        entryAccuracyM = map.optDouble(DiveSyncKeys.KEY_ENTRY_ACC),
                        entryFixEpochMs = map.optLong(DiveSyncKeys.KEY_ENTRY_AT),
                        exitLat = map.optDouble(DiveSyncKeys.KEY_EXIT_LAT),
                        exitLon = map.optDouble(DiveSyncKeys.KEY_EXIT_LON),
                        exitAccuracyM = map.optDouble(DiveSyncKeys.KEY_EXIT_ACC),
                        exitFixEpochMs = map.optLong(DiveSyncKeys.KEY_EXIT_AT),
                    ),
                )
                val bytes = dataClient.getFdForAsset(asset).await().inputStream.use { it.readBytes() }
                dao.insertSamples(DiveTransferCodec.decodeSamples(bytes, diveId))
                imported++
            }
        } finally {
            buffer.release()
        }
        imported
    }.getOrDefault(-1)

    // The publisher encodes "absent" as NaN / -1 / 0 / "" (DataMap has no nulls).
    private fun DataMap.optDouble(key: String): Double? = getDouble(key, Double.NaN).takeIf { !it.isNaN() }
    private fun DataMap.optInt(key: String): Int? = getInt(key, -1).takeIf { it >= 0 }
    private fun DataMap.optLong(key: String): Long? = getLong(key, 0L).takeIf { it > 0L }
    private fun DataMap.optString(key: String): String? = getString(key)?.takeIf { it.isNotEmpty() }
}
