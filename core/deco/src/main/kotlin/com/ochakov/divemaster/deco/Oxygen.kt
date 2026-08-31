package com.ochakov.divemaster.deco

import kotlin.math.pow

/** Oxygen exposure: ppO2, maximum operating depth, and the NOAA CNS clock. */
object Oxygen {
    fun ppO2Bar(ambientBar: Double, gas: Gas): Double = ambientBar * gas.o2Fraction

    /** Maximum operating depth for [gas] at [maxPpO2Bar] (typically 1.4). */
    fun modMeters(gas: Gas, maxPpO2Bar: Double, surfaceBar: Double, converter: DepthConverter): Double =
        converter.depthMeters(maxPpO2Bar / gas.o2Fraction, surfaceBar)

    /** NOAA single-exposure limits: ppO2 in bar to allowed minutes. */
    private val NOAA_LIMITS = listOf(
        0.6 to 720.0,
        0.7 to 570.0,
        0.8 to 450.0,
        0.9 to 360.0,
        1.0 to 300.0,
        1.1 to 240.0,
        1.2 to 210.0,
        1.3 to 180.0,
        1.4 to 150.0,
        1.5 to 120.0,
        1.6 to 45.0,
    )

    /**
     * Fraction of the CNS clock consumed per minute at [ppO2], linearly
     * interpolating the NOAA limit between table entries. Below 0.5 bar the
     * clock does not advance. At or beyond 1.6 bar the 45-minute limit is
     * used — that region is MOD-alert territory, not a place to linger.
     */
    fun cnsRatePerMin(ppO2: Double): Double {
        if (ppO2 < 0.5) return 0.0
        val first = NOAA_LIMITS.first()
        if (ppO2 <= first.first) return 1.0 / first.second
        val last = NOAA_LIMITS.last()
        if (ppO2 >= last.first) return 1.0 / last.second
        for (j in 1 until NOAA_LIMITS.size) {
            val (p1, l1) = NOAA_LIMITS[j - 1]
            val (p2, l2) = NOAA_LIMITS[j]
            if (ppO2 <= p2) {
                val limit = l1 + (l2 - l1) * (ppO2 - p1) / (p2 - p1)
                return 1.0 / limit
            }
        }
        return 1.0 / last.second
    }

    /** Advance the CNS clock (a fraction: 0.5 = 50%) by one exposure interval. */
    fun addExposure(cnsFraction: Double, ppO2: Double, durationSec: Double): Double =
        cnsFraction + cnsRatePerMin(ppO2) * (durationSec / 60.0)

    /** Surface decay of the CNS clock with the conventional 90-minute half-time. */
    fun surfaceDecay(cnsFraction: Double, durationSec: Double, halfTimeMin: Double = 90.0): Double =
        cnsFraction * 2.0.pow(-(durationSec / 60.0) / halfTimeMin)
}
