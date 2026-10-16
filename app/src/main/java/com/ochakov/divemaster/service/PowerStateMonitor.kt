package com.ochakov.divemaster.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

/**
 * Keeps a short, wall-clock-stamped history of what the OS did to the
 * device's power state — screen on/off, light and deep Doze, power saver —
 * so a stall of the barometer stream can be lined up against it. "The
 * readings stopped at 12:15:03, the screen went off at 12:14:58 and came back
 * at 12:27:20 with the readings" is the difference between a power-policy
 * problem and a sensor fault, and it is what the 2026-10-16 dives (1 % and
 * 21 % of their samples) could not tell us.
 *
 * Only protected system broadcasts are registered, so the receiver is not
 * exported. [start] in the service's onCreate, [stop] in onDestroy.
 */
class PowerStateMonitor(
    private val context: Context,
    /** Called with the newest-first history after every transition. */
    private val onChange: (List<String>) -> Unit = {},
) {
    private data class Transition(val wallMs: Long, val what: String)

    private val lock = Any()
    private val transitions = ArrayDeque<Transition>()
    private var registered = false

    /** Last screen transition seen (epoch ms) and its direction; 0 until one is seen. */
    @Volatile private var screenChangedWallMs = 0L
    @Volatile private var screenOn = powerManager.isInteractive

    private val powerManager: PowerManager
        get() = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val pm = powerManager
            val now = System.currentTimeMillis()
            val what = when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    screenChangedWallMs = now
                    "screen on"
                }

                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    screenChangedWallMs = now
                    "screen off"
                }

                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED ->
                    if (pm.isDeviceIdleMode) "DEEP DOZE on (wake locks ignored)" else "deep doze off"

                PowerManager.ACTION_LIGHT_DEVICE_IDLE_MODE_CHANGED ->
                    if (Build.VERSION.SDK_INT >= 33 && pm.isLightDeviceIdleMode) "light doze on" else "light doze off"

                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED ->
                    if (pm.isPowerSaveMode) "power saver on" else "power saver off"

                else -> return
            }
            record(now, what)
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) addAction(PowerManager.ACTION_LIGHT_DEVICE_IDLE_MODE_CHANGED)
        }
        // System broadcasts reach a non-exported receiver; nothing else needs to.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        record(System.currentTimeMillis(), "monitor started, screen ${if (powerManager.isInteractive) "on" else "off"}")
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    private fun record(wallMs: Long, what: String) {
        val snapshot: List<String>
        synchronized(lock) {
            transitions.addLast(Transition(wallMs, what))
            while (transitions.size > MAX_TRANSITIONS) transitions.removeFirst()
            snapshot = transitions.reversed().map { it.format() }
        }
        onChange(snapshot)
    }

    /** Newest first, "HH:mm:ss what". */
    fun recent(limit: Int = MAX_TRANSITIONS): List<String> = synchronized(lock) {
        transitions.reversed().take(limit).map { it.format() }
    }

    /** Transitions stamped inside [fromWallMs, toWallMs], oldest first — what happened during a stall. */
    fun between(fromWallMs: Long, toWallMs: Long): List<String> = synchronized(lock) {
        transitions.filter { it.wallMs in fromWallMs..toWallMs }.map { it.format() }
    }

    /** "screen on since 12:27:20" / "screen off since 12:14:58" / "screen on (no change seen)". */
    fun screenSummary(): String {
        val now = powerManager.isInteractive
        val changed = screenChangedWallMs
        return if (changed == 0L) {
            "screen ${if (now) "on" else "off"} (no change seen)"
        } else {
            "screen ${if (screenOn) "on" else "off"} since ${TIME_FMT.format(Instant.ofEpochMilli(changed).atZone(ZoneId.systemDefault()))}"
        }
    }

    /** One line of the device's current power policy as it applies to this app. */
    fun policySummary(): String {
        val pm = powerManager
        val doze = when {
            pm.isDeviceIdleMode -> "DEEP"
            Build.VERSION.SDK_INT >= 33 && pm.isLightDeviceIdleMode -> "light"
            else -> "no"
        }
        return buildString {
            append(screenSummary())
            append(" · doze ").append(doze)
            if (pm.isPowerSaveMode) append(" · power saver ON")
            append(" · battery optimization ")
            append(if (pm.isIgnoringBatteryOptimizations(context.packageName)) "exempt" else "NOT exempt")
        }
    }

    private fun Transition.format(): String =
        "${TIME_FMT.format(Instant.ofEpochMilli(wallMs).atZone(ZoneId.systemDefault()))} $what"

    private companion object {
        const val MAX_TRANSITIONS = 24
        val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
