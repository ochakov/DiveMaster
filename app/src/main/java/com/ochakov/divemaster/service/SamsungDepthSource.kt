package com.ochakov.divemaster.service

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Samsung's private water-depth and water-temperature sensors (Galaxy Watch
 * Ultra family), read through the ordinary SensorManager API by vendor type
 * id. Event layout (reverse-engineered from Samsung's stock depth app):
 *
 *  - depth (69705, requested as wake-up): values[0] is a state flag
 *    (1.0 = entered water, 2.0 = exited); any other value is a live sample
 *    with values[1] = depth in meters. The enter/exit markers are ignored
 *    here — DiveMaster's own state machine derives dive sessions from the
 *    samples. The event's pressure field (values[2]) is ignored too: its
 *    units are undocumented, while meters are unambiguous.
 *  - temperature (69686): only valid when values[0] == 1.0; values[2] = °C.
 *
 * Access is gated by the signature|privileged permission
 * com.samsung.permission.SSENSOR: on a normal install getDefaultSensor()
 * returns null and [available] is false — callers fall back to the
 * barometer. Never assume availability implies a Samsung watch only;
 * anything non-null simply gets used.
 */
class SamsungDepthSource(
    private val sensorManager: SensorManager,
    private val onLiveSample: (timestampMs: Long, depthM: Double) -> Unit,
    private val onWaterTemp: (celsius: Double) -> Unit,
) : SensorEventListener {

    private val depthSensor: Sensor? =
        runCatching { sensorManager.getDefaultSensor(TYPE_WATER_DEPTH, true) }.getOrNull()
    private val tempSensor: Sensor? =
        runCatching { sensorManager.getDefaultSensor(TYPE_WATER_TEMPERATURE) }.getOrNull()

    /** The sensor is visible; whether it delivers events is only provable in water. */
    val available: Boolean get() = depthSensor != null

    val tempAvailable: Boolean get() = tempSensor != null

    val depthSensorLabel: String? get() = depthSensor?.let { "${it.name} (type ${it.type})" }

    /** False when the framework rejected the listener (a delivery-side permission gate). */
    var depthRegistered = false
        private set

    fun start() {
        depthRegistered = depthSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        } ?: false
        tempSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            TYPE_WATER_DEPTH -> {
                val state = event.values.getOrNull(0) ?: return
                if (state == ENTERED_WATER || state == EXITED_WATER) return
                val depthM = event.values.getOrNull(1) ?: return
                onLiveSample(System.currentTimeMillis(), depthM.toDouble())
            }

            TYPE_WATER_TEMPERATURE -> {
                if (event.values.getOrNull(0) == VALID) {
                    event.values.getOrNull(2)?.let { onWaterTemp(it.toDouble()) }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** Samsung private vendor sensor ids (SENSOR_TYPE_DEVICE_PRIVATE_BASE range). */
        const val TYPE_WATER_DEPTH = 69705
        const val TYPE_WATER_TEMPERATURE = 69686
        private const val ENTERED_WATER = 1.0f
        private const val EXITED_WATER = 2.0f
        private const val VALID = 1.0f
    }
}
