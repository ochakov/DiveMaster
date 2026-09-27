package com.ochakov.divemaster.data.surface

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.surfaceMemoryDataStore by preferencesDataStore(name = "surface_memory")

/**
 * The last atmospheric pressure the dive service was confident about, kept
 * in its own tiny DataStore (written about once a minute on the surface, so
 * it stays out of the settings flow). Read at service start: an app
 * restarted underwater judges its first reading against this instead of
 * calibrating to it — see DiveEngine's cold-start rules.
 */
class SurfaceMemoryStore(context: Context) {
    private val dataStore = context.applicationContext.surfaceMemoryDataStore

    data class Remembered(val pressureBar: Double, val epochMs: Long)

    suspend fun read(): Remembered? {
        val prefs = dataStore.data.first()
        val bar = prefs[BAR] ?: return null
        val at = prefs[AT] ?: return null
        return Remembered(bar, at)
    }

    suspend fun write(pressureBar: Double, epochMs: Long) {
        dataStore.edit {
            it[BAR] = pressureBar
            it[AT] = epochMs
        }
    }

    private companion object {
        val BAR = doublePreferencesKey("surface_bar")
        val AT = longPreferencesKey("surface_epoch_ms")
    }
}
