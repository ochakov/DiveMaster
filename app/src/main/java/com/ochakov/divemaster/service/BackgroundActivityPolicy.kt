package com.ochakov.divemaster.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * The one watch setting DiveMaster cannot work without.
 *
 * On the Galaxy Watch the per-app switch "Allow background activity"
 * (Galaxy Wearable app on the phone › Watch settings › Apps › DiveMaster ›
 * Battery) decides whether the OS keeps delivering barometer readings to the
 * service once the screen sleeps — which it does within seconds of the watch
 * touching water. With the switch off, Ev's dives of 2026-10-16 reached the
 * app with 1 % and 21 % of their samples, in bursts that coincided with
 * button glances; with it on, the three dives of 2026-10-17 recorded every
 * second. A fresh install (e.g. from Google Play) can leave the switch off.
 *
 * Android exposes the state ([ActivityManager.isBackgroundRestricted]) but
 * no way to request the permission, so the app detects it, blocks the surface
 * screen until it is fixed (with a way through for a diver who knows), shows
 * it in the ongoing notification and the probe, and logs it with every arm.
 */
object BackgroundActivityPolicy {

    /** True when the user (or a fresh install) has background activity switched off for this app. */
    fun isRestricted(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted
    }.getOrDefault(false)

    /** One-line state for logs and the probe. */
    fun label(context: Context): String =
        if (isRestricted(context)) "background activity RESTRICTED" else "background activity allowed"

    /** Where the switch lives, in the user's words. */
    const val FIX_PATH =
        "Galaxy Wearable app on the phone › Watch settings › Apps › DiveMaster › Battery › Allow background activity"

    /**
     * Opens this app's App info page on the watch itself (some watches expose
     * the switch there). Returns false when the watch has no such page, in
     * which case the phone's Galaxy Wearable app is the only way.
     */
    fun openAppInfo(context: Context): Boolean {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (context.packageManager.resolveActivity(intent, 0) == null) return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
