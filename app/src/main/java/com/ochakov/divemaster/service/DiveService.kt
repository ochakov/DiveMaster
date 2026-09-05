package com.ochakov.divemaster.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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

/**
 * Foreground service hosting the dive engine. Runs while the app is visible,
 * keeps itself (plus a wake lock) alive for the whole dive once submerged,
 * and stops on the surface when the app is gone. All engine access happens on
 * a single processing coroutine fed through a channel.
 */
class DiveService : Service() {

    private sealed interface Input {
        data class Sample(val sample: PressureSample) : Input
        data class NativeDepth(val timestampMs: Long, val depthM: Double) : Input
        data object Abort : Input
        data class SettingsChanged(val settings: DiveSettings) : Input
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inputs = Channel<Input>(Channel.UNLIMITED)
    private val pipeline = SensorPipeline()
    private val nativeDepthPipeline = SensorPipeline()
    private var samsungSource: SamsungDepthSource? = null
    @Volatile private var engine: DiveEngine? = null
    private var recorder: DiveSessionRecorder? = null
    private var sensorManager: SensorManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var simJob: Job? = null
    private var alertSounder: AlertSounder? = null
    private var diveModeActive = false

    @Volatile private var lastSampleWallMs = 0L
    @Volatile private var lastForegroundWallMs = 0L
    @Volatile private var lastDepthLogMs = 0L
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
        startForeground(NOTIFICATION_ID, buildNotification("Surface monitoring"))
        serviceRunning.value = true
        alertSounder = AlertSounder(this)
        lastForegroundWallMs = System.currentTimeMillis()
        // Held for the whole monitoring lifetime (not just during a dive): a
        // watch that sleeps its screen on water contact would otherwise suspend
        // the CPU and stop barometer sampling before a dive is ever detected.
        acquireMonitorWakeLock()
        val dao = DiveMasterDatabase.get(this).diveDao()
        scope.launch {
            val repository = SettingsRepository(this@DiveService)
            var settings = repository.settings.first()
            val rec = DiveSessionRecorder(dao, settings)
            val restored = rec.restore()
            recorder = rec
            val syncPublisher = DiveSyncPublisher(this@DiveService, dao)
            launch { syncPublisher.reconcileAll() }
            var eng = buildEngine(settings, restored.tissue, restored.cnsFraction, null)
            engine = eng
            var evaluator = buildEvaluator(settings)
            var pendingSettings: DiveSettings? = null
            var converter = DepthConverter(settings.waterType)

            // Settings edits arrive through the same channel as samples so the
            // engine is only ever touched from this coroutine.
            launch {
                repository.settings.collect { inputs.trySend(Input.SettingsChanged(it)) }
            }

            suspend fun processEngineSample(sample: PressureSample, fromNativeDepth: Boolean) {
                lastSampleWallMs = System.currentTimeMillis()
                nativeDepthDriving.value = fromNativeDepth
                val events = eng.onSample(sample)
                rec.handle(events, eng, System.currentTimeMillis())
                displayState.value = eng.displayState.copy(simulated = simulatorRunning.value)
                updateDiveMode(eng.displayState.phase == DivePhase.DIVING)
                val alerts = evaluator.evaluate(eng.displayState, sample.timestampMs)
                if (alerts.isNotEmpty()) {
                    alertSounder?.play(alerts, settings.vibrateEnabled, settings.beepEnabled)
                }
                // Diagnostics: prove headless sampling/detection works even with
                // the screen forced off in water. Events always logged; depth
                // throttled to ~5 s so logcat stays readable.
                for (event in events) {
                    when (event) {
                        is EngineEvent.DiveStarted -> Log.i(TAG, "DIVE STARTED (source=${if (fromNativeDepth) "native" else "baro"})")
                        is EngineEvent.DiveEnded -> Log.i(TAG, "DIVE ENDED: ${event.durationSec}s max=%.1fm".format(event.maxDepthM))
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
                            eng.displayState.depthM,
                            eng.displayState.phase,
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
                }

                // Apply edited settings only on the surface — never mid-dive.
                // Tissue and CNS state carry over into the rebuilt engine.
                val pending = pendingSettings
                if (pending != null && eng.displayState.phase == DivePhase.SURFACE) {
                    settings = pending
                    pendingSettings = null
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

                // Battery guard: if the user left monitoring running (auto-armed
                // while the app was open) and then walked away — screen off, not
                // diving — stand down after a long idle rather than holding the
                // wake lock forever.
                if (!diving && simJob == null && !activityVisible &&
                    now - lastForegroundWallMs > MONITOR_IDLE_STOP_MS
                ) {
                    Log.i(TAG, "Surface idle ${MONITOR_IDLE_STOP_MS / 60000} min — standing down")
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
            else -> { // ACTION_MONITOR (app opened / foregrounded) or system restart
                lastForegroundWallMs = System.currentTimeMillis()
                acquireMonitorWakeLock()
                Log.i(TAG, "Monitoring armed; wakeLock held=${wakeLock?.isHeld == true}")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed (diving=${engine?.displayState?.phase == DivePhase.DIVING}, activityVisible=$activityVisible)")
        sensorManager?.unregisterListener(pressureListener)
        sensorManager?.unregisterListener(tempListener)
        samsungSource?.stop()
        samsungSource = null
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

    private fun buildEngine(
        settings: DiveSettings,
        tissue: TissueState,
        cnsFraction: Double,
        surfaceBar: Double?,
    ) = DiveEngine(
        DiveEngineConfig(
            settings.waterType,
            settings.gas,
            settings.gradientFactors,
            safetyStopSeconds = settings.safetyStopMinutes * 60,
            safetyStopMinDepthM = settings.safetyStopMinDepthM,
            safetyStopMaxDepthM = settings.safetyStopMaxDepthM,
        ),
        tissue,
        cnsFraction,
        surfaceBar,
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

    private fun acquireMonitorWakeLock() {
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DiveMaster:monitor")
                .apply {
                    setReferenceCounted(false)
                    acquire(MAX_WAKELOCK_MS)
                }
        }
    }

    private fun updateDiveMode(diving: Boolean) {
        if (diving) lastForegroundWallMs = System.currentTimeMillis() // never idle-stop mid-dive
        if (diving && !diveModeActive) {
            diveModeActive = true
            acquireMonitorWakeLock() // ensure held even if a long idle had released it
            notify("Dive in progress")
            if (!activityVisible) {
                // Best effort: recent-foreground grace often allows this; when the
                // OS blocks it the ongoing notification is the way back in.
                runCatching {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        } else if (!diving && diveModeActive) {
            diveModeActive = false
            notify(if (simulatorRunning.value) "Simulated dive running" else "Surface monitoring")
        }
    }

    private fun startSimulation() {
        if (simJob != null) return
        simulatorRunning.value = true
        notify("Simulated dive running")
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
            notify("Surface monitoring")
        }.also { job -> job.invokeOnCompletion { simJob = null } }
    }

    private fun stopSimulation() {
        val job = simJob
        simJob = null
        job?.cancel()
        if (simulatorRunning.value) {
            simulatorRunning.value = false
            inputs.trySend(Input.Abort)
            notify("Surface monitoring")
        }
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Dive engine", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
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
            .build()

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
        private const val MONITOR_IDLE_STOP_MS = 20L * 60 * 1000
        private const val SENSOR_STALE_MS = 5_000L
        private const val LOW_BATTERY_PCT = 15
        private const val NATIVE_DEPTH_FRESH_MS = 3_000L
        private const val WATER_TEMP_FRESH_MS = 60_000L

        const val ACTION_MONITOR = "com.ochakov.divemaster.MONITOR"
        const val ACTION_START_SIM = "com.ochakov.divemaster.START_SIM"
        const val ACTION_STOP_SIM = "com.ochakov.divemaster.STOP_SIM"

        val displayState = MutableStateFlow<DiveDisplayState?>(null)
        val simulatorRunning = MutableStateFlow(false)
        val serviceRunning = MutableStateFlow(false)
        val sensorStale = MutableStateFlow(false)
        val batteryPct = MutableStateFlow<Int?>(null)

        /** Samsung private depth sensor readable on this install. */
        val nativeDepthAvailable = MutableStateFlow(false)

        /** True while the native depth sensor (not the barometer) feeds the engine. */
        val nativeDepthDriving = MutableStateFlow(false)

        @Volatile
        var activityVisible = false

        fun start(context: Context, action: String = ACTION_MONITOR) {
            context.startForegroundService(Intent(context, DiveService::class.java).setAction(action))
        }
    }
}
