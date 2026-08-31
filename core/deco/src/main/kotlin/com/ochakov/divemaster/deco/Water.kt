package com.ochakov.divemaster.deco

/** Water density models used to convert pressure to depth. */
enum class WaterType(val densityKgPerM3: Double) {
    /** EN 13319 dive-computer standard: exactly 10.0 m per bar of gauge pressure. */
    EN13319(1019.716),
    SALT(1025.0),
    FRESH(1000.0),
}

/** Converts between absolute ambient pressure (bar) and water depth (m). */
class DepthConverter(val waterType: WaterType) {
    val barPerMeter: Double = waterType.densityKgPerM3 * GRAVITY / PASCAL_PER_BAR

    /** Gauge depth in meters; small negative values can occur from sensor noise. */
    fun depthMeters(ambientBar: Double, surfaceBar: Double): Double =
        (ambientBar - surfaceBar) / barPerMeter

    fun ambientBar(depthMeters: Double, surfaceBar: Double): Double =
        surfaceBar + depthMeters * barPerMeter

    companion object {
        const val GRAVITY = 9.80665
        const val PASCAL_PER_BAR = 100_000.0
        const val STANDARD_ATMOSPHERE_BAR = 1.01325
        const val BAR_PER_HPA = 0.001
    }
}
