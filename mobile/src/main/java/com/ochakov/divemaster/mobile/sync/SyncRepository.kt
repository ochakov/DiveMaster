package com.ochakov.divemaster.mobile.sync

import android.content.Context
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
 * logbook even if it is later deleted on the watch. Returns the number of
 * newly imported dives, or -1 when the Data Layer is unreachable.
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
                if (start <= 0 || dao.diveByStart(start) != null) continue
                val asset = map.getAsset(DiveSyncKeys.KEY_SAMPLES) ?: continue

                val minTemp = map.getDouble(DiveSyncKeys.KEY_MIN_TEMP, Double.NaN)
                val diveId = dao.insertDive(
                    DiveEntity(
                        startEpochMs = start,
                        endEpochMs = map.getLong(DiveSyncKeys.KEY_END),
                        maxDepthM = map.getDouble(DiveSyncKeys.KEY_MAX_DEPTH),
                        avgDepthM = map.getDouble(DiveSyncKeys.KEY_AVG_DEPTH),
                        minTempC = minTemp.takeIf { !it.isNaN() },
                        gasO2Fraction = map.getDouble(DiveSyncKeys.KEY_GAS_O2, 0.21),
                        waterType = map.getString(DiveSyncKeys.KEY_WATER) ?: "EN13319",
                        surfacePressureMbar = map.getDouble(DiveSyncKeys.KEY_SURFACE_MBAR, 1013.25),
                        gfLow = map.getInt(DiveSyncKeys.KEY_GF_LOW, 40),
                        gfHigh = map.getInt(DiveSyncKeys.KEY_GF_HIGH, 85),
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
}
