package com.ochakov.divemaster.ui.probe

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.ochakov.divemaster.service.SamsungDepthSource
import com.ochakov.divemaster.ui.theme.DiveAmber
import com.ochakov.divemaster.ui.theme.DiveGreen
import com.ochakov.divemaster.ui.theme.DiveRed

/**
 * Phase 0 hardware probe. Answers, on real hardware, the questions the whole
 * project depends on: how far the pressure sensor actually reads (spec range
 * and max value seen while submerged), how fast it samples, and whether any
 * usable temperature source exists.
 */
@Composable
fun ProbeScreen() {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var pressureSensor by remember { mutableStateOf<Sensor?>(null) }
    var pressureHpa by remember { mutableStateOf(Float.NaN) }
    var maxSeenHpa by remember { mutableStateOf(Float.NaN) }
    var surfaceRefHpa by remember { mutableStateOf(Float.NaN) }
    var measuredHz by remember { mutableStateOf(0.0) }
    var ambientSensorName by remember { mutableStateOf<String?>(null) }
    var ambientTempC by remember { mutableStateOf<Float?>(null) }
    var vendorTempNames by remember { mutableStateOf(listOf<String>()) }
    val vendorTemps = remember { mutableStateMapOf<String, Float>() }
    var allSensors by remember { mutableStateOf(listOf<String>()) }
    var nativeDepthAvailable by remember { mutableStateOf(false) }
    var nativeDepthM by remember { mutableStateOf<Double?>(null) }
    var nativeWaterTempC by remember { mutableStateOf<Double?>(null) }
    var nativeDepthSamples by remember { mutableStateOf(0) }
    var nativeDepthLabel by remember { mutableStateOf<String?>(null) }
    var nativeDepthRegistered by remember { mutableStateOf(false) }
    var nativeTempPresent by remember { mutableStateOf(false) }
    // Raw values of every depth-named sensor (69705 standard, 69709 Mares, …)
    // so a dunk reveals which one delivers and in which index the depth sits.
    val depthRaw = remember { mutableStateMapOf<String, String>() }
    val depthMax = remember { mutableStateMapOf<String, Float>() }

    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val all = sensorManager.getSensorList(Sensor.TYPE_ALL)
        allSensors = all.map { "${it.name} — type ${it.type}" }

        val pressure = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        pressureSensor = pressure
        var refCount = 0
        var refSum = 0f
        var lastTimestampNs = 0L
        var emaDtNs = 0.0
        val pressureListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val hPa = event.values[0]
                pressureHpa = hPa
                if (maxSeenHpa.isNaN() || hPa > maxSeenHpa) maxSeenHpa = hPa
                if (refCount < 25) {
                    refSum += hPa
                    refCount++
                    if (refCount == 25) surfaceRefHpa = refSum / 25f
                }
                if (lastTimestampNs != 0L) {
                    val dt = (event.timestamp - lastTimestampNs).toDouble()
                    emaDtNs = if (emaDtNs == 0.0) dt else emaDtNs * 0.9 + dt * 0.1
                    if (emaDtNs > 0.0) measuredHz = 1e9 / emaDtNs
                }
                lastTimestampNs = event.timestamp
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (pressure != null) {
            sensorManager.registerListener(pressureListener, pressure, SensorManager.SENSOR_DELAY_FASTEST)
        }

        val ambient = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
        ambientSensorName = ambient?.name
        val ambientListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                ambientTempC = event.values[0]
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (ambient != null) {
            sensorManager.registerListener(ambientListener, ambient, SensorManager.SENSOR_DELAY_NORMAL)
        }

        val vendorSensors = all.filter {
            it.type != Sensor.TYPE_PRESSURE &&
                it.type != Sensor.TYPE_AMBIENT_TEMPERATURE &&
                (it.name.contains("temp", ignoreCase = true) || it.name.contains("thermo", ignoreCase = true))
        }
        vendorTempNames = vendorSensors.map { it.name }
        val vendorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                vendorTemps[event.sensor.name] = event.values[0]
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        vendorSensors.forEach { sensorManager.registerListener(vendorListener, it, SensorManager.SENSOR_DELAY_NORMAL) }

        val samsungSource = SamsungDepthSource(
            sensorManager,
            onLiveSample = { _, depthM ->
                nativeDepthM = depthM
                nativeDepthSamples++
            },
            onWaterTemp = { celsius -> nativeWaterTempC = celsius },
        )
        nativeDepthAvailable = samsungSource.available
        nativeTempPresent = samsungSource.tempAvailable
        nativeDepthLabel = samsungSource.depthSensorLabel
        samsungSource.start()
        nativeDepthRegistered = samsungSource.depthRegistered

        // Raw viewer for every depth-named sensor (wake-up variants included),
        // so a dunk shows which type streams and which index carries depth.
        val depthSensors = all.filter { it.name.contains("depth", ignoreCase = true) }
        val depthRawListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val key = "t${event.sensor.type}"
                depthRaw[key] = event.values.joinToString(" ") { "%.2f".format(it) }
                // Track the largest value[1] seen (the likely depth index per the guide).
                val d = event.values.getOrNull(1) ?: return
                if ((depthMax[key] ?: Float.NEGATIVE_INFINITY) < d) depthMax[key] = d
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        depthSensors.forEach { sensorManager.registerListener(depthRawListener, it, SensorManager.SENSOR_DELAY_UI) }

        onDispose {
            sensorManager.unregisterListener(pressureListener)
            sensorManager.unregisterListener(ambientListener)
            sensorManager.unregisterListener(vendorListener)
            sensorManager.unregisterListener(depthRawListener)
            samsungSource.stop()
        }
    }

    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item { Text("SENSOR PROBE", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.secondary) }

            val sensor = pressureSensor
            if (sensor == null) {
                item {
                    Text(
                        "No pressure sensor found — this watch cannot measure depth.",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.error,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                item { Text(sensor.name, style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center) }
                item {
                    Text(
                        "Spec range %.0f hPa · res %.3f".format(sensor.maximumRange, sensor.resolution),
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                }
                item {
                    // 1 hPa of water is about 1 cm; ~1013 hPa sits at the surface.
                    // Many HALs declare a nominal atmospheric maximumRange rather than
                    // the chip's real limit (the Ultra 2 declares ~1013 hPa yet reads
                    // past it underwater), so a low spec means "ceiling unknown",
                    // not "unusable" — only readings can prove the true limit.
                    val specHpa = sensor.maximumRange
                    val specDepthM = (specHpa - 1013f) * 0.01f
                    val readsPastSpec = !maxSeenHpa.isNaN() && maxSeenHpa > specHpa + 2f
                    val (message, color) = when {
                        specDepthM >= 45f ->
                            "OK: spec range covers ~%.0f m of water".format(specDepthM) to DiveGreen
                        readsPastSpec ->
                            "Reads %.0f hPa past declared spec — spec is nominal. Find the true limit with staged depths."
                                .format(maxSeenHpa - specHpa) to DiveGreen
                        else ->
                            "Declared spec %.0f hPa (~%.1f m) is likely nominal, not a hard limit. Dunk to test, then verify staged depths in water."
                                .format(specHpa, specDepthM) to DiveAmber
                    }
                    Text(message, style = MaterialTheme.typography.caption1, color = color, textAlign = TextAlign.Center)
                }
                item {
                    Text(
                        if (pressureHpa.isNaN()) "— hPa" else "%.2f hPa".format(pressureHpa),
                        style = MaterialTheme.typography.title1,
                    )
                }
                item {
                    val depth = if (pressureHpa.isNaN() || surfaceRefHpa.isNaN()) null else (pressureHpa - surfaceRefHpa) * 0.01f
                    Text(
                        depth?.let { "Depth %.2f m".format(it) } ?: "Depth — (zeroing…)",
                        style = MaterialTheme.typography.title2,
                        color = MaterialTheme.colors.primary,
                    )
                }
                item {
                    Text(
                        if (maxSeenHpa.isNaN() || surfaceRefHpa.isNaN()) "Max seen —"
                        else "Max seen %.1f hPa ≈ %.2f m".format(maxSeenHpa, (maxSeenHpa - surfaceRefHpa) * 0.01f),
                        style = MaterialTheme.typography.caption1,
                        textAlign = TextAlign.Center,
                    )
                }
                item {
                    Text(
                        "Rate %.0f Hz · min delay %d ms".format(measuredHz, sensor.minDelay / 1000),
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                    )
                }
                item {
                    Chip(
                        label = { Text("Re-zero at current") },
                        onClick = {
                            if (!pressureHpa.isNaN()) {
                                surfaceRefHpa = pressureHpa
                                maxSeenHpa = pressureHpa
                            }
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
            item { Text("DEPTH CANDIDATES (raw)", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.secondary) }
            if (depthRaw.isEmpty()) {
                item {
                    Text(
                        "No depth-named sensors delivering yet — dunk to test",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                val keys = depthRaw.keys.sorted()
                items(keys.size) { i ->
                    val key = keys[i]
                    Text(
                        "$key: [${depthRaw[key]}]  max[1]=${depthMax[key]?.let { "%.2f".format(it) } ?: "—"}",
                        fontSize = 10.sp,
                        color = DiveGreen,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
            item { Text("SAMSUNG DEPTH SENSOR", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.secondary) }
            if (nativeDepthAvailable) {
                item {
                    Text(
                        "Sensor visible — drives dives once it streams",
                        style = MaterialTheme.typography.caption1,
                        color = DiveGreen,
                        textAlign = TextAlign.Center,
                    )
                }
                nativeDepthLabel?.let { label ->
                    item {
                        Text(
                            label,
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                item {
                    Text(
                        if (nativeDepthRegistered) "Listener registered ✓" else "Listener REJECTED — delivery gated",
                        style = MaterialTheme.typography.caption2,
                        color = if (nativeDepthRegistered) DiveGreen else DiveRed,
                    )
                }
                item {
                    Text(
                        if (nativeDepthSamples == 0) {
                            "No samples yet — streams only in water; dunk to test"
                        } else {
                            "Depth %.2f m · %d samples".format(nativeDepthM ?: 0.0, nativeDepthSamples)
                        },
                        style = MaterialTheme.typography.title3,
                        color = MaterialTheme.colors.primary,
                        textAlign = TextAlign.Center,
                    )
                }
                item {
                    Text(
                        if (nativeTempPresent) {
                            nativeWaterTempC?.let { "Water %.1f °C".format(it) } ?: "Water temp —"
                        } else {
                            "Water-temp sensor (69686) not present — skin sensors cover temperature"
                        },
                        style = MaterialTheme.typography.caption2,
                        color = if (nativeTempPresent) MaterialTheme.colors.onBackground else DiveAmber,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                item {
                    Text(
                        "Not accessible on this install — Samsung gates it behind SSENSOR (platform-signed/privileged apps only). Barometer will be used.",
                        style = MaterialTheme.typography.caption2,
                        color = DiveAmber,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
            item { Text("TEMPERATURE", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.secondary) }
            item {
                val name = ambientSensorName
                if (name != null) {
                    Text(
                        "$name: ${ambientTempC?.let { "%.1f °C".format(it) } ?: "—"}",
                        style = MaterialTheme.typography.caption1,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text("No ambient temperature sensor", style = MaterialTheme.typography.caption1, color = DiveAmber)
                }
            }
            if (ambientSensorName == null && vendorTempNames.isNotEmpty()) {
                item {
                    Text(
                        "Skin/vendor sensors below will provide water temperature (body-biased, slow to track)",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (vendorTempNames.isEmpty()) {
                item {
                    Text(
                        "No vendor temperature sensors",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                    )
                }
            } else {
                items(vendorTempNames.size) { i ->
                    val name = vendorTempNames[i]
                    Text(
                        "$name: ${vendorTemps[name]?.let { "%.1f °C".format(it) } ?: "—"}",
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
            item { Text("ALL SENSORS (${allSensors.size})", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.secondary) }
            items(allSensors.size) { i ->
                Text(
                    allSensors[i],
                    fontSize = 9.sp,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
