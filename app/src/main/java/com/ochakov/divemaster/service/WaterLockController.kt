package com.ochakov.divemaster.service

import android.content.Context
import android.content.Intent

/**
 * Best-effort launcher for Samsung's Water Lock (Galaxy Watch). Engaging it
 * before a dive disables the wet touchscreen, which stops the phantom-touch
 * screen flicker — so a physical-button press underwater wakes to a stable
 * dive-screen glance instead of the screen churning on and off.
 *
 * There is no public API to toggle it, and programmatic engage needs a
 * privileged permission, so this only *opens* the water-lock UI for the user
 * to confirm. The exact activity/action isn't documented; we try a few
 * candidates and report failure so the UI can fall back to telling the diver
 * to use the quick-settings water tile.
 */
object WaterLockController {

    fun launch(context: Context): Boolean {
        val candidates = listOf(
            Intent("com.samsung.android.clockwork.settings.WATER_LOCK"),
            Intent().setClassName(
                "com.samsung.android.clockwork.settings",
                "com.samsung.android.clockwork.settings.waterlock.WaterLockActivity",
            ),
            Intent("com.samsung.android.wear.WATER_LOCK"),
        )
        for (intent in candidates) {
            val launched = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            if (launched) return true
        }
        return false
    }
}
