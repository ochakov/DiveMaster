package com.ochakov.divemaster.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.ochakov.divemaster.R
import com.ochakov.divemaster.data.db.DiveMasterDatabase
import com.ochakov.divemaster.data.settings.SettingsRepository
import com.ochakov.divemaster.deco.DepthConverter
import com.ochakov.divemaster.engine.DiveDisplayState
import com.ochakov.divemaster.engine.DiveEngine
import com.ochakov.divemaster.engine.DiveEngineConfig
import com.ochakov.divemaster.engine.DivePhase
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
        data object Abort : Input
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inputs = Channel<Input>(Channel.UNLIMITED)
    private val pipeline = SensorPipeline()
    @Volatile private var engine: DiveEngine? = null
    private var recorder: DiveSessionRecorder? = null
    private var sensorManager: SensorManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var simJob: Job? = null

    @Volatile private var ambientTempC: Double? = null
    @Volatile private var skinTempC: Double? = null
    @Volatile private var otherTempC: Double? = null

    /** Ambient sensor first, then skin temperature, then any other vendor source. */
    private fun currentTempC(): Double? = ambientTempC ?: skinTempC ?: otherTempC

    private val pressureListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (simulatorRunning.value) return // the simulator owns the stream
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
        val dao = DiveMasterDatabase.get(this).diveDao()
        scope.launch {
            val settings = SettingsRepository(this@DiveService).settings.first()
            val rec = DiveSessionRecorder(dao, settings)
            val restored = rec.restore()
            recorder = rec
            val eng = DiveEngine(
                DiveEngineConfig(settings.waterType, settings.gas, settings.gradientFactors),
                restored.tissue,
                restored.cnsFraction,
            )
            engine = eng
            for (input in inputs) {
                val events = when (input) {
                    is Input.Sample -> eng.onSample(input.sample)
                    Input.Abort -> eng.abortDive()
                }
                rec.handle(events, eng, System.currentTimeMillis())
                displayState.value = eng.displayState.copy(simulated = simulatorRunning.value)
                updateDiveMode(eng.displayState.phase == DivePhase.DIVING)
            }
        }
        registerSensors()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SIM -> startSimulation()
            ACTION_STOP_SIM -> stopSimulation()
            ACTION_MAYBE_STOP -> maybeStop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(pressureListener)
        sensorManager?.unregisterListener(tempListener)
        simJob?.cancel()
        val eng = engine
        val rec = recorder
        if (eng != null && rec != null) runBlocking { rec.persistTissueNow(eng) }
        scope.cancel()
        wakeLock?.release()
        wakeLock = null
        serviceRunning.value = false
        simulatorRunning.value = false
        displayState.value = null
        super.onDestroy()
    }

    private fun registerSensors() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = sm
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

    private fun updateDiveMode(diving: Boolean) {
        if (diving && wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DiveMaster:dive")
                .apply {
                    setReferenceCounted(false)
                    acquire(MAX_DIVE_WAKELOCK_MS)
                }
            notify("Dive in progress")
        } else if (!diving && wakeLock != null) {
            wakeLock?.release()
            wakeLock = null
            notify(if (simulatorRunning.value) "Simulated dive running" else "Surface monitoring")
            maybeStop()
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
            maybeStop()
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

    private fun maybeStop() {
        val diving = engine?.displayState?.phase == DivePhase.DIVING
        if (!activityVisible && !diving && simJob == null) stopSelf()
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
            .build()

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "dive_engine"
        private const val NOTIFICATION_ID = 1
        private const val SIM_TIME_SCALE = 4L
        private const val SIM_WATER_TEMP_C = 24.0
        private const val MAX_DIVE_WAKELOCK_MS = 6L * 60 * 60 * 1000

        const val ACTION_MONITOR = "com.ochakov.divemaster.MONITOR"
        const val ACTION_START_SIM = "com.ochakov.divemaster.START_SIM"
        const val ACTION_STOP_SIM = "com.ochakov.divemaster.STOP_SIM"
        const val ACTION_MAYBE_STOP = "com.ochakov.divemaster.MAYBE_STOP"

        val displayState = MutableStateFlow<DiveDisplayState?>(null)
        val simulatorRunning = MutableStateFlow(false)
        val serviceRunning = MutableStateFlow(false)

        @Volatile
        var activityVisible = false

        fun start(context: Context, action: String = ACTION_MONITOR) {
            context.startForegroundService(Intent(context, DiveService::class.java).setAction(action))
        }
    }
}
