package com.ochakov.divemaster.service

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.ochakov.divemaster.data.db.DiveDao
import com.ochakov.divemaster.data.db.DiveEntity
import com.ochakov.divemaster.data.transfer.DiveSyncKeys
import com.ochakov.divemaster.data.transfer.DiveTransferCodec
import kotlinx.coroutines.tasks.await

/**
 * Publishes finalized dives to the Wearable Data Layer so the phone
 * companion (same applicationId + signature) can import them whenever the
 * devices sync — the phone does not need to be reachable at publish time.
 * Every call is best-effort: watches without Google Play services (or an
 * unpaired watch) simply keep their local log.
 */
class DiveSyncPublisher(context: Context, private val dao: DiveDao) {

    private val dataClient = Wearable.getDataClient(context.applicationContext)

    /** Publish every finalized dive not yet in the Data Layer. */
    suspend fun reconcileAll() {
        runCatching {
            val published = mutableSetOf<String>()
            val buffer = dataClient.dataItems.await()
            try {
                for (item in buffer) {
                    item.uri.path?.takeIf { it.startsWith(DiveSyncKeys.PATH_PREFIX) }?.let { published += it }
                }
            } finally {
                buffer.release()
            }
            for (dive in dao.allFinalized()) {
                if ("${DiveSyncKeys.PATH_PREFIX}${dive.startEpochMs}" !in published) publish(dive)
            }
        }
    }

    suspend fun publish(dive: DiveEntity) {
        runCatching {
            val samples = dao.samplesFor(dive.id)
            val request = PutDataMapRequest.create("${DiveSyncKeys.PATH_PREFIX}${dive.startEpochMs}").apply {
                dataMap.putLong(DiveSyncKeys.KEY_START, dive.startEpochMs)
                dataMap.putLong(DiveSyncKeys.KEY_END, dive.endEpochMs)
                dataMap.putDouble(DiveSyncKeys.KEY_MAX_DEPTH, dive.maxDepthM)
                dataMap.putDouble(DiveSyncKeys.KEY_AVG_DEPTH, dive.avgDepthM)
                dataMap.putDouble(DiveSyncKeys.KEY_MIN_TEMP, dive.minTempC ?: Double.NaN)
                dataMap.putDouble(DiveSyncKeys.KEY_GAS_O2, dive.gasO2Fraction)
                dataMap.putString(DiveSyncKeys.KEY_WATER, dive.waterType)
                dataMap.putDouble(DiveSyncKeys.KEY_SURFACE_MBAR, dive.surfacePressureMbar)
                dataMap.putInt(DiveSyncKeys.KEY_GF_LOW, dive.gfLow)
                dataMap.putInt(DiveSyncKeys.KEY_GF_HIGH, dive.gfHigh)
                dataMap.putAsset(
                    DiveSyncKeys.KEY_SAMPLES,
                    Asset.createFromBytes(DiveTransferCodec.encodeSamples(samples)),
                )
            }.asPutDataRequest()
            dataClient.putDataItem(request).await()
        }
    }

    /** Remove a deleted dive from the Data Layer (phone archives keep their copy). */
    suspend fun unpublish(startEpochMs: Long) {
        runCatching {
            dataClient.deleteDataItems(
                Uri.parse("wear://*${DiveSyncKeys.PATH_PREFIX}$startEpochMs"),
            ).await()
        }
    }
}
