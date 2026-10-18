package com.ochakov.divemaster.service

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
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
 * second. A fresh install (e.g. from Google Play) leaves the switch off.
 *
 * Android has no API to request the permission, only to read restrictions —
 * and which of its public signals Samsung's switch moves is not documented,
 * so [signals] reads all three and the probe prints them raw. Policy aside,
 * the service also recognises the *symptom* (a stall that ends the moment
 * the screen comes on, see `DiveService.StallStats.screenOffStalls`) and
 * warns on that evidence alone.
 */
object BackgroundActivityPolicy {

    /** The public signals that can carry a per-app background restriction. */
    data class Signals(
        /** AOSP background restriction: App info › Battery › "Restricted" / "Allow background usage" off. */
        val backgroundRestricted: Boolean,
        /** App Standby bucket; RESTRICTED is the user-imposed one. */
        val standbyBucket: Int,
        /** Doze allow-list ("Unrestricted"). Not a restriction by itself — most apps are "Optimized". */
        val batteryOptimizationExempt: Boolean,
    ) {
        val restricted: Boolean
            get() = backgroundRestricted || standbyBucket >= UsageStatsManager.STANDBY_BUCKET_RESTRICTED

        fun bucketName(): String = when (standbyBucket) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED"
            else -> "bucket $standbyBucket"
        }

        /** Raw values for the probe and the stall line: a screenshot with the switch off tells which one it moves. */
        fun summary(): String =
            "isBackgroundRestricted=$backgroundRestricted · standby ${bucketName()} · " +
                "battery optimization ${if (batteryOptimizationExempt) "exempt" else "not exempt"}"
    }

    fun signals(context: Context): Signals = Signals(
        backgroundRestricted = runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted
        }.getOrDefault(false),
        standbyBucket = runCatching {
            (context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).appStandbyBucket
        }.getOrDefault(UsageStatsManager.STANDBY_BUCKET_ACTIVE),
        batteryOptimizationExempt = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false),
    )

    /** True when any public signal says background activity is switched off for this app. */
    fun isRestricted(context: Context): Boolean = signals(context).restricted

    /** One line for logs and the probe, raw signals included. */
    fun label(context: Context): String {
        val s = signals(context)
        return (if (s.restricted) "background activity RESTRICTED" else "background activity allowed") +
            " (" + s.summary() + ")"
    }

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
