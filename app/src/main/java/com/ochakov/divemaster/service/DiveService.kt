package com.ochakov.divemaster.service

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.util.Log
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.ochakov.divemaster.MainActivity
import com.ochakov.divemaster.R
import com.ochakov.divemaster.data.db.DiveMasterDatabase
import com.ochakov.divemaster.data.settings.DiveSettings
import com.ochakov.divemaster.data.settings.SettingsRepository
import com.ochakov.divemaster.data.surface.SurfaceMemoryStore
import com.ochakov.divemaster.deco.DepthConverter
import com.ochakov.divemaster.deco.TissueState
import com.ochakov.divemaster.engine.AlertConfig
import com.ochakov.divemaster.engine.AlertEvaluator
import com.ochakov.divemaster.engine.DiveDisplayState
import com.ochakov.divemaster.engine.DiveEngine
import com.ochakov.divemaster.engine.DiveEngineConfig
import com.ochakov.divemaster.engine.DivePhase
import com.ochakov.divemaster.engine.EngineEvent
import com.ochakov.divemaster.engine.PressureSample
import com.ochakov.divemaster.engine.SensorPipeline
import com.ochakov.divemaster.engine.SimulatorProfile
import com.ochakov.divemaster.engine.SurfaceMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Foreground service hosting the dive engine. Runs while the app is visible,
 * keeps itself (plus a wake lock) alive for the whole dive once submerged,
 * and stops on the surface when the app is gone. All engine access happens on
 * a single processing coroutine fed through a channel.
 *
 * Surface memory: while the engine trusts its surface reference, the service
 * remembers it (DataStore, ~once a minute). A cold-started engine is seeded
 * with that memory so an app restarted *underwater* — a third-party app can
 * always be killed — recognises depth for what it is instead of calibrating
 * to it (Ev's 2026-09-25 dive logged a "surface" of 1487 mbar that way).
 */
class DiveService : Service() {

    private sealed interface Input {
        data class Sample(val sample: PressureSample) : Input
        data class NativeDepth(val timestampMs: Long, val depthM: Double) : Input
        data object Abort : Input
        data object Recalibrate : Input
        data class ExitLocation(val fix: LocationFix) : Input
        data class SettingsChanged(val settings: DiveSettings) : Input
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inputs = Channel<Input>(Channel.UNLIMITED)
    private val pipeline = SensorPipeline()
    private val nativeDepthPipeline = SensorPipeline()
    private var samsungSource: SamsungDepthSource? = null
    @Volatile private var engine: DiveEngine? = null
    private var recorder: DiveSessionRecorder? = null
    private var surfaceMemoryStore: SurfaceMemoryStore? = null
    private var sensorManager: SensorManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var simJob: Job? = null
    private var alertSounder: AlertSounder? = null
    private var diveModeActive = false
    private var checkNotified = false
    private var endPendingSeen = false
    private val location by lazy { LocationCapture(this) }
    private val appVersion: String by lazy {
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
    }

    @Volatile private var lastSampleWallMs = 0L
    @Volatile private var lastForegroundWallMs = 0L
    @Volatile private var lastDepthLogMs = 0L
    @Volatile private var lastSurfaceMemoryWriteMs = 0L
    @Volatile private var lastWakeLockRefreshMs = 0L
    @Volatile private var lastArmLogMs = 0L
    private var batteryWarned = false

    @Volatile private var ambientTempC: Double? = null
    @Volatile private var skinTempC: Double? = null
    @Volatile private var otherTempC: Double? = null
    @Volatile private var lastNativeDepthWallMs = 0L
    @Volatile private var waterTempC: Double? = null
    @Volatile private var waterTempWallMs = 0L

    /**
     * Samsung's real water thermometer first (while fresh), then ambient,
     * then skin temperature, then any other vendor source.
     */
    private fun currentTempC(): Double? {
        val water = waterTempC
        if (water != null && System.currentTimeMillis() - waterTempWallMs < WATER_TEMP_FRESH_MS) {
            return water
        }
        return ambientTempC ?: skinTempC ?: otherTempC
    }

    private val pressureListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (simulatorRunning.value) return // the simulator owns the stream
            if (System.currentTimeMillis() - lastNativeDepthWallMs < NATIVE_DEPTH_FRESH_MS) {
                return // Samsung's dedicated depth sensor owns the stream
            }
            val bar = event.values[0].toDouble() * DepthConverter.BAR_PER_HPA
            val sample = pipeline.onRaw(System.currentTimeMillis(), bar) ?: return
            inputs.trySend(Input.Sample(sample.copy(tempC = currentTempC())))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val tempListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val value = event.values[0].toDouble()
            when {
                event.sensor.type == Sensor.TYPE_AMBIENT_TEMPERATURE -> ambientTempC = value
                event.sensor.name.contains("skin", ignoreCase = true) -> skinTempC = value
                else -> otherTempC = value
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(buildNotification("Surface monitoring"))
        serviceRunning.value = true
        alertSounder = AlertSounder(this)
        lastForegroundWallMs = System.currentTimeMillis()
        LifecycleLog.append(this, "service created")
        captureExitHistory()
        // Held for the whole monitoring lifetime (not just during a dive): a
        // watch that sleeps its screen on water contact would otherwise suspend
        // the CPU and stop barometer sampling before a dive is ever detected.
        acquireMonitorWakeLock()
        val dao = DiveMasterDatabase.get(this).diveDao()
        scope.launch {
            val repository = SettingsRepository(this@DiveService)
            var settings = repository.settings.first()
            val rec = DiveSessionRecorder(dao, settings, appVersion)
            val restored = rec.restore()
            recorder = rec
            val syncPublisher = DiveSyncPublisher(this@DiveService, dao)
            launch { syncPublisher.reconcileAll() }

            val memoryStore = SurfaceMemoryStore(this@DiveService)
            surfaceMemoryStore = memoryStore
            val remembered = memoryStore.read()
            rememberedSurface.value = remembered?.let { it.pressureBar to it.epochMs }
            val memory = remembered?.let {
                SurfaceMemory(
                    it.pressureBar,
                    ((System.currentTimeMillis() - it.epochMs) / 1000.0).coerceAtLeast(0.0),
                )
            }
            Log.i(
                TAG,
                "Surface memory: " + (
                    memory?.let { "%.1f hPa, %.0f min old".format(it.pressureBar * 1000, it.ageSec / 60) }
                        ?: "none"
                    ),
            )

            var config = buildConfig(settings)
            var eng = buildEngine(settings, restored.tissue, restored.cnsFraction, null, memory)
            engine = eng
            var evaluator = buildEvaluator(settings)
            var pendingSettings: DiveSettings? = null
            var converter = DepthConverter(settings.waterType)

            // Settings edits arrive through the same channel as samples so the
            // engine is only ever touched from this coroutine.
            launch {
                repository.settings.collect { inputs.trySend(Input.SettingsChanged(it)) }
            }

            fun rememberSurface(bar: Double, nowWall: Long) {
                lastSurfaceMemoryWriteMs = nowWall
                rememberedSurface.value = bar to nowWall
                launch { memoryStore.write(bar, nowWall) }
            }

            suspend fun processEngineSample(sample: PressureSample, fromNativeDepth: Boolean) {
                lastSampleWallMs = System.currentTimeMillis()
                nativeDepthDriving.value = fromNativeDepth
                val events = eng.onSample(sample)
                rec.handle(events, eng, System.currentTimeMillis(), batteryPct.value ?: readBatteryPct()) {
                    if (simulatorRunning.value) null else location.lastKnown()
                }
                val state = eng.displayState
                displayState.value = state.copy(simulated = simulatorRunning.value)
                updateDiveMode(state.phase == DivePhase.DIVING)
                val alerts = evaluator.evaluate(state, sample.timestampMs)
                if (alerts.isNotEmpty()) {
                    alertSounder?.play(alerts, settings.vibrateEnabled, settings.beepEnabled)
                }

                // Remember atmospheric pressure — only a reference the engine
                // itself trusts (a real surface reading or a fresh memory with no
                // cold-start suspicion pending), never a provisional or guessed
                // one, and never the simulator's synthetic surface.
                val started = events.firstOrNull { it is EngineEvent.DiveStarted } as? EngineEvent.DiveStarted
                if (!simulatorRunning.value && state.referenceTrusted &&
                    state.surfacePressureBar <= config.atmosphericCeilingBar
                ) {
                    val nowWall = System.currentTimeMillis()
                    val dueOnSurface = state.phase == DivePhase.SURFACE &&
                        nowWall - lastSurfaceMemoryWriteMs >= SURFACE_MEMORY_WRITE_MS
                    val dueOnDiveStart = started != null && !started.startedUnderwater
                    if (dueOnSurface || dueOnDiveStart) rememberSurface(state.surfacePressureBar, nowWall)
                }
                // The notification mirrors an undecided cold start (and offers recalibrate).
                if (state.startCheckActive != checkNotified) {
                    checkNotified = state.startCheckActive
                    notify(currentStatusText())
                }
                // Exit position: one fresh fix per surfacing, asked for the moment
                // the wrist first touches the surface — GPS needs sky, and the
                // 60 s end hold gives it time. Delivered through the channel so
                // the recorder is only ever touched from this coroutine.
                if (state.endPending && !endPendingSeen && !simulatorRunning.value) {
                    location.requestFix { fix -> inputs.trySend(Input.ExitLocation(fix)) }
                }
                endPendingSeen = state.endPending

                // Diagnostics: prove headless sampling/detection works even with
                // the screen forced off in water. Events always logged; depth
                // throttled to ~5 s so logcat stays readable.
                for (event in events) {
                    when (event) {
                        is EngineEvent.DiveStarted -> {
                            Log.i(
                                TAG,
                                "DIVE STARTED (source=%s, lateStart=%b, surface=%.1f hPa)".format(
                                    if (fromNativeDepth) "native" else "baro",
                                    event.startedUnderwater,
                                    event.surfacePressureBar * 1000,
                                ),
                            )
                            LifecycleLog.append(
                                this@DiveService,
                                if (event.startedUnderwater) {
                                    "DIVE STARTED UNDERWATER (late start, surface %.0f hPa from memory)"
                                        .format(event.surfacePressureBar * 1000)
                                } else {
                                    "dive started (surface %.0f hPa)".format(event.surfacePressureBar * 1000)
                                },
                            )
                        }

                        is EngineEvent.SurfaceReferenceCorrected -> Log.i(
                            TAG,
                            "surface reference corrected to %.1f hPa (profile shifted %.2f m)".format(
                                event.surfacePressureBar * 1000,
                                event.depthShiftM,
                            ),
                        )

                        is EngineEvent.DiveEnded -> {
                            Log.i(TAG, "DIVE ENDED: ${event.durationSec}s max=%.1fm".format(event.maxDepthM))
                            LifecycleLog.append(
                                this@DiveService,
                                "dive ended: ${event.durationSec / 60} min, max %.1f m".format(event.maxDepthM),
                            )
                        }

                        EngineEvent.DiveDiscarded -> Log.i(TAG, "dive discarded (<60 s)")
                        else -> Unit
                    }
                }
                val nowWall = System.currentTimeMillis()
                if (nowWall - lastDepthLogMs >= 5_000) {
                    lastDepthLogMs = nowWall
                    Log.d(
                        TAG,
                        "depth=%.2fm phase=%s src=%s screenOff-ok".format(
                            state.depthM,
                            state.phase,
                            if (fromNativeDepth) "native" else "baro",
                        ),
                    )
                }
                if (events.any { it is EngineEvent.DiveEnded }) {
                    scope.launch { syncPublisher.reconcileAll() }
                }
            }

            for (input in inputs) {
                when (input) {
                    is Input.SettingsChanged ->
                        if (input.settings != settings) pendingSettings = input.settings

                    is Input.Sample -> processEngineSample(input.sample, fromNativeDepth = false)

                    is Input.NativeDepth -> {
                        // Samsung reports depth in meters; synthesize ambient
                        // pressure from the engine's own surface reference so
                        // engine depth equals sensor depth exactly, and every
                        // downstream consumer stays unchanged.
                        val bar = eng.displayState.surfacePressureBar +
                            input.depthM * converter.barPerMeter
                        nativeDepthPipeline.onRaw(input.timestampMs, bar)?.let { sample ->
                            processEngineSample(sample.copy(tempC = currentTempC()), fromNativeDepth = true)
                        }
                    }

                    Input.Abort -> {
                        val events = eng.abortDive()
                        rec.handle(events, eng, System.currentTimeMillis())
                        displayState.value = eng.displayState.copy(simulated = simulatorRunning.value)
                        updateDiveMode(false)
                    }

                    is Input.ExitLocation -> {
                        val fix = input.fix
                        val republish = rec.recordExitLocation(fix)
                        Log.i(TAG, "exit position %.5f, %.5f (+/-%.0f m)".format(fix.lat, fix.lon, fix.accuracyM))
                        // Landed after the dive was finalized and published: publish again.
                        if (republish != null) {
                            dao.dive(republish)?.let { dive -> scope.launch { syncPublisher.publish(dive) } }
                        }
                    }

                    Input.Recalibrate -> {
                        val events = eng.recalibrate()
                        rec.handle(events, eng, System.currentTimeMillis())
                        displayState.value = eng.displayState.copy(simulated = simulatorRunning.value)
                        updateDiveMode(false)
                        checkNotified = false
                        Log.i(
                            TAG,
                            "Recalibrated by user: surface=%.1f hPa".format(eng.displayState.surfacePressureBar * 1000),
                        )
                        LifecycleLog.append(this@DiveService, "recalibrated by user (not underwater)")
                        notify(currentStatusText())
                    }
                }

                // Apply edited settings only on the surface, and only once the
                // engine trusts its reference (a rebuild would launder a
                // provisional one) — never mid-dive. Tissue and CNS state carry
                // over into the rebuilt engine.
                val pending = pendingSettings
                if (pending != null && eng.displayState.phase == DivePhase.SURFACE && eng.displayState.referenceTrusted) {
                    settings = pending
                    pendingSettings = null
                    config = buildConfig(settings)
                    eng = buildEngine(settings, eng.tissue, eng.cnsFraction, eng.displayState.surfacePressureBar)
                    engine = eng
                    evaluator = buildEvaluator(settings)
                    converter = DepthConverter(settings.waterType)
                    rec.updateSettings(settings)
                }
            }
        }
        registerSensors()
        startHealthWatchdog()
    }

    /**
     * Device-health guard. A frozen depth display is the most dangerous
     * silent failure a dive computer can have, so a stalled sensor mid-dive
     * flags the UI and buzzes; low battery during a dive warns once. These
     * warnings vibrate regardless of the alert toggles.
     */
    private fun startHealthWatchdog() {
        scope.launch {
            val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            var lastBatteryPollMs = 0L
            while (true) {
                delay(2_000)
                val now = System.currentTimeMillis()
                val diving = engine?.displayState?.phase == DivePhase.DIVING
                val stale = diving && lastSampleWallMs > 0 && now - lastSampleWallMs > SENSOR_STALE_MS
                if (stale && !sensorStale.value) warnBuzz(longArrayOf(0, 500, 200, 500))
                sensorStale.value = stale
                if (now - lastBatteryPollMs >= 30_000) {
                    lastBatteryPollMs = now
                    val pct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    batteryPct.value = if (pct in 1..100) pct else null
                    if (diving && (batteryPct.value ?: 100) <= LOW_BATTERY_PCT && !batteryWarned) {
                        batteryWarned = true
                        warnBuzz(longArrayOf(0, 150, 100, 150, 100, 150))
                    }
                }
                if (!diving) batteryWarned = false
                if (diving) lastForegroundWallMs = now
                // The wake lock is acquired with a timeout as a safety net; keep
                // it topped up so a long dive day never outlives it.
                if (now - lastWakeLockRefreshMs >= WAKELOCK_REFRESH_MS) {
                    lastWakeLockRefreshMs = now
                    acquireMonitorWakeLock()
                }

                // Battery guard: if the user left monitoring running (auto-armed
                // while the app was open) and then walked away — screen off, not
                // diving — stand down after a long idle rather than holding the
                // wake lock forever. Long enough (3 h) to cover gearing up and a
                // normal surface interval: the old 20 min stood the service down
                // on the boat before Ev's 2026-09-25 afternoon dive.
                if (!diving && simJob == null && !activityVisible &&
                    now - lastForegroundWallMs > MONITOR_IDLE_STOP_MS
                ) {
                    Log.i(TAG, "Surface idle ${MONITOR_IDLE_STOP_MS / 60000} min — standing down")
                    LifecycleLog.append(
                        this@DiveService,
                        "stood down: ${MONITOR_IDLE_STOP_MS / 3_600_000} h idle on the surface with the app closed",
                    )
                    stopSelf()
                    return@launch
                }
            }
        }
    }

    private fun warnBuzz(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SIM -> startSimulation()
            ACTION_STOP_SIM -> stopSimulation()
            ACTION_RECALIBRATE -> inputs.trySend(Input.Recalibrate)
            else -> { // ACTION_MONITOR (app opened / foregrounded) or system restart
                val now = System.currentTimeMillis()
                lastForegroundWallMs = now
                acquireMonitorWakeLock()
                // Re-declares the foreground types too, so a location permission
                // granted after the service started takes effect.
                startForegroundCompat(buildNotification(currentStatusText()))
                Log.i(TAG, "Monitoring armed; wakeLock held=${wakeLock?.isHeld == true}")
                if (now - lastArmLogMs >= ARM_LOG_THROTTLE_MS) { // button glances would flood the log
                    lastArmLogMs = now
                    LifecycleLog.append(this, "armed (app opened)")
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        val diving = engine?.displayState?.phase == DivePhase.DIVING
        Log.i(TAG, "Service destroyed (diving=$diving, activityVisible=$activityVisible)")
        LifecycleLog.append(this, "service destroyed (diving=$diving, appVisible=$activityVisible)")
        sensorManager?.unregisterListener(pressureListener)
        sensorManager?.unregisterListener(tempListener)
        samsungSource?.stop()
        samsungSource = null
        location.cancel()
        nativeDepthDriving.value = false
        simJob?.cancel()
        val eng = engine
        val rec = recorder
        if (eng != null && rec != null) runBlocking { rec.persistTissueNow(eng) }
        scope.cancel()
        alertSounder?.release()
        alertSounder = null
        wakeLock?.release()
        wakeLock = null
        serviceRunning.value = false
        simulatorRunning.value = false
        sensorStale.value = false
        displayState.value = null
        super.onDestroy()
    }

    /** Why did earlier instances of this process die? Shown in the probe and logged. */
    private fun captureExitHistory() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val lines = runCatching { am.getHistoricalProcessExitReasons(packageName, 0, 6) }
            .getOrDefault(emptyList())
            .map { info ->
                val at = STAMP_FMT.format(Instant.ofEpochMilli(info.timestamp).atZone(ZoneId.systemDefault()))
                val desc = info.description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                "$at ${exitReasonName(info.reason)}$desc"
            }
        processExitHistory.value = lines
        lines.forEach { Log.i(TAG, "process exit history: $it") }
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited itself"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW-MEMORY kill"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE CRASH"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE-RESOURCE kill"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "force-stopped by user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_OTHER -> "killed by system (other)"
        ApplicationExitInfo.REASON_FREEZER -> "frozen by system"
        15 -> "package state change"
        16 -> "package updated"
        else -> "reason $reason"
    }

    private fun readBatteryPct(): Int? {
        val pct = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (pct in 1..100) pct else null
    }

    /**
     * Foreground types: specialUse always (API 34+), location only while the
     * runtime permission is granted — declaring it without the permission
     * throws on API 34+. Any failure falls back to the location-less form.
     */
    private fun startForegroundCompat(notification: Notification) {
        val locationGranted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        val base = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val withLocation = base or (if (locationGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        if (withLocation != 0) {
            try {
                startForeground(NOTIFICATION_ID, notification, withLocation)
                return
            } catch (e: Exception) {
                Log.w(TAG, "startForeground(types=$withLocation) failed; retrying without location", e)
            }
        }
        if (base != 0) startForeground(NOTIFICATION_ID, notification, base) else startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildEngine(
        settings: DiveSettings,
        tissue: TissueState,
        cnsFraction: Double,
        surfaceBar: Double?,
        memory: SurfaceMemory? = null,
    ) = DiveEngine(buildConfig(settings), tissue, cnsFraction, surfaceBar, memory)

    private fun buildConfig(settings: DiveSettings): DiveEngineConfig =
        // Detection thresholds are the engine defaults (0.3 m start, shallow and
        // fast — see DiveEngineConfig); production and bench testing share them,
        // so no dev-only shallowing is needed.
        DiveEngineConfig(
            settings.waterType,
            settings.gas,
            settings.gradientFactors,
            safetyStopSeconds = settings.safetyStopMinutes * 60,
            safetyStopMinDepthM = settings.safetyStopMinDepthM,
            safetyStopMaxDepthM = settings.safetyStopMaxDepthM,
        )

    private fun buildEvaluator(settings: DiveSettings) = AlertEvaluator(
        AlertConfig(
            rateAlertsEnabled = settings.rateAlertsEnabled,
            ascentRateMPerMin = settings.ascentAlertMPerMin,
            descentRateMPerMin = settings.descentAlertMPerMin,
            ndlAlertEnabled = settings.ndlAlertEnabled,
            ndlAlertMinutes = settings.ndlAlertMinutes,
            maxPpO2Bar = settings.maxPpO2Bar,
        ),
    )

    private fun registerSensors() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        // Samsung private depth/water-temp sensors: present only on
        // platform-signed/privileged installs; null otherwise → barometer path.
        samsungSource = SamsungDepthSource(
            sm,
            onLiveSample = { timestampMs, depthM ->
                if (!simulatorRunning.value) {
                    lastNativeDepthWallMs = System.currentTimeMillis()
                    inputs.trySend(Input.NativeDepth(timestampMs, depthM))
                }
            },
            onWaterTemp = { celsius ->
                waterTempC = celsius
                waterTempWallMs = System.currentTimeMillis()
            },
        ).also {
            nativeDepthAvailable.value = it.available
            it.start()
        }
        sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sm.registerListener(pressureListener, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        sm.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)?.let {
            sm.registerListener(tempListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sm.getSensorList(Sensor.TYPE_ALL)
            .filter {
                it.type != Sensor.TYPE_PRESSURE &&
                    it.type != Sensor.TYPE_AMBIENT_TEMPERATURE &&
                    (it.name.contains("temp", true) || it.name.contains("thermo", true))
            }
            .forEach { sm.registerListener(tempListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    /** Creates the monitoring wake lock on first use; re-acquiring refreshes its timeout. */
    private fun acquireMonitorWakeLock() {
        val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DiveMaster:monitor")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire(MAX_WAKELOCK_MS)
    }

    private fun updateDiveMode(diving: Boolean) {
        if (diving) lastForegroundWallMs = System.currentTimeMillis() // never idle-stop mid-dive
        if (diving && !diveModeActive) {
            diveModeActive = true
            acquireMonitorWakeLock() // ensure held even if a long idle had released it
            notify(currentStatusText())
            if (!activityVisible) {
                // Best effort: recent-foreground grace often allows this; when the
                // OS blocks it the ongoing notification is the way back in.
                runCatching {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        } else if (!diving && diveModeActive) {
            diveModeActive = false
            notify(currentStatusText())
        }
    }

    private fun startSimulation() {
        if (simJob != null) return
        simulatorRunning.value = true
        notify(currentStatusText())
        simJob = scope.launch {
            val settings = SettingsRepository(this@DiveService).settings.first()
            val converter = DepthConverter(settings.waterType)
            val surfaceBar = engine?.displayState?.surfacePressureBar
                ?: DepthConverter.STANDARD_ATMOSPHERE_BAR
            val startMs = System.currentTimeMillis()
            var simSec = 0
            while (simSec <= SimulatorProfile.totalDurationSec.toInt()) {
                val depth = SimulatorProfile.depthAt(simSec.toDouble())
                inputs.trySend(
                    Input.Sample(
                        PressureSample(
                            startMs + simSec * 1000L,
                            converter.ambientBar(depth, surfaceBar),
                            skinTempC ?: SIM_WATER_TEMP_C,
                        ),
                    ),
                )
                simSec++
                delay(1000L / SIM_TIME_SCALE)
            }
            simulatorRunning.value = false
            notify(currentStatusText())
        }.also { job -> job.invokeOnCompletion { simJob = null } }
    }

    private fun stopSimulation() {
        val job = simJob
        simJob = null
        job?.cancel()
        if (simulatorRunning.value) {
            simulatorRunning.value = false
            inputs.trySend(Input.Abort)
            notify(currentStatusText())
        }
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Dive engine", NotificationManager.IMPORTANCE_LOW),
        )
    }

    /** What the ongoing notification should say right now. */
    private fun currentStatusText(): String {
        val state = engine?.displayState
        return when {
            state?.phase == DivePhase.DIVING ->
                if (state.startedUnderwater) "Dive in progress · LATE START" else "Dive in progress"

            simulatorRunning.value -> "Simulated dive running"
            state?.startCheckActive == true -> "Checking whether underwater…"
            else -> {
                val until = Instant.ofEpochMilli(lastForegroundWallMs + MONITOR_IDLE_STOP_MS)
                    .atZone(ZoneId.systemDefault())
                "Watching for a dive · auto-off ${TIME_FMT.format(until)} if idle"
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dive)
            .setContentTitle("DiveMaster")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        val state = engine?.displayState
        if (state != null && (state.startedUnderwater || state.startCheckActive)) {
            // Escape hatch for the one false positive the cold-start rules can
            // produce (a boat deck after a drive down from altitude). Lives in
            // the notification stream — not under the touch-locked dive screen —
            // so water on the display cannot trigger it.
            builder.addAction(
                0,
                "Not underwater — recalibrate",
                PendingIntent.getForegroundService(
                    this,
                    1,
                    Intent(this, DiveService::class.java).setAction(ACTION_RECALIBRATE),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        return builder.build()
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "dive_engine"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "DiveService"
        private const val SIM_TIME_SCALE = 4L
        private const val SIM_WATER_TEMP_C = 24.0
        private const val MAX_WAKELOCK_MS = 6L * 60 * 60 * 1000
        private const val WAKELOCK_REFRESH_MS = 60_000L
        private const val MONITOR_IDLE_STOP_MS = 3L * 60 * 60 * 1000
        private const val SENSOR_STALE_MS = 5_000L
        private const val LOW_BATTERY_PCT = 15
        private const val NATIVE_DEPTH_FRESH_MS = 3_000L
        private const val WATER_TEMP_FRESH_MS = 60_000L
        private const val SURFACE_MEMORY_WRITE_MS = 60_000L
        private const val ARM_LOG_THROTTLE_MS = 5L * 60 * 1000
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
        private val STAMP_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm")

        const val ACTION_MONITOR = "com.ochakov.divemaster.MONITOR"
        const val ACTION_START_SIM = "com.ochakov.divemaster.START_SIM"
        const val ACTION_STOP_SIM = "com.ochakov.divemaster.STOP_SIM"
        const val ACTION_RECALIBRATE = "com.ochakov.divemaster.RECALIBRATE"

        val displayState = MutableStateFlow<DiveDisplayState?>(null)
        val simulatorRunning = MutableStateFlow(false)
        val serviceRunning = MutableStateFlow(false)
        val sensorStale = MutableStateFlow(false)
        val batteryPct = MutableStateFlow<Int?>(null)

        /** Samsung private depth sensor readable on this install. */
        val nativeDepthAvailable = MutableStateFlow(false)

        /** True while the native depth sensor (not the barometer) feeds the engine. */
        val nativeDepthDriving = MutableStateFlow(false)

        /** Last remembered atmospheric pressure (bar) and when it was written (epoch ms). */
        val rememberedSurface = MutableStateFlow<Pair<Double, Long>?>(null)

        /** The OS's record of why earlier instances of this process died, newest first. */
        val processExitHistory = MutableStateFlow<List<String>>(emptyList())

        @Volatile
        var activityVisible = false

        fun start(context: Context, action: String = ACTION_MONITOR) {
            context.startForegroundService(Intent(context, DiveService::class.java).setAction(action))
        }
    }
}
