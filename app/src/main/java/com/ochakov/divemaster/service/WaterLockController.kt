package com.ochakov.divemaster.service

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Best-effort control of Samsung's Water Lock (Galaxy Watch). When engaged,
 * the OS keeps the display usable and disables the capacitive touchscreen
 * underwater — without it, water contact makes the watch fire repeated
 * "sleep_button" screen-offs that background the app.
 *
 * There is no public API for this. We attempt the mechanisms the stock
 * behavior implies, each fully guarded: on a normal (non-privileged) install
 * these throw SecurityException or are simply ignored, so this is purely an
 * enhancement on top of the wake-lock monitoring path, which already keeps
 * dive detection and logging alive with the screen off. **Unverified on
 * device — needs a real dunk to confirm which mechanism (if any) works.**
 *
 * [attempted] records that we tried; [engaged] is only set true if a call
 * returned without error — treat it as "believed engaged", not a guarantee.
 */
class WaterLockController(private val context: Context) {

    @Volatile var attempted = false
        private set
    @Volatile var engaged = false
        private set

    fun engage() {
        attempted = true
        engaged = setWaterLock(true)
    }

    fun disengage() {
        if (!attempted) return
        setWaterLock(false)
        engaged = false
    }

    private fun setWaterLock(on: Boolean): Boolean {
        // Broadcast to Samsung's clockwork settings — the most plausible
        // non-reflective path. Harmless no-op if no receiver / not permitted.
        val ok = runCatching {
            context.sendBroadcast(
                Intent(ACTION_WATER_LOCK).putExtra(EXTRA_ENABLE, on).setPackage(CLOCKWORK_SETTINGS_PKG),
            )
            true
        }.getOrElse {
            Log.i(TAG, "Water lock broadcast unavailable: ${it.message}")
            false
        }
        return ok
    }

    private companion object {
        const val TAG = "WaterLock"
        const val CLOCKWORK_SETTINGS_PKG = "com.samsung.android.clockwork.settings"
        const val ACTION_WATER_LOCK = "com.samsung.android.clockwork.settings.WATER_LOCK"
        const val EXTRA_ENABLE = "enable"
    }
}
