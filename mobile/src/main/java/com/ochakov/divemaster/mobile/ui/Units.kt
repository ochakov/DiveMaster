package com.ochakov.divemaster.mobile.ui

import kotlin.math.roundToInt

/** Display-side unit conversion; storage is always metric. */
object Units {
    private const val FT_PER_M = 3.28084

    fun depth(meters: Double, metric: Boolean): String =
        if (metric) "%.1f".format(meters) else "${(meters * FT_PER_M).roundToInt()}"

    fun depthUnit(metric: Boolean): String = if (metric) "m" else "ft"

    fun depthWithUnit(meters: Double, metric: Boolean): String =
        "${depth(meters, metric)} ${depthUnit(metric)}"

    fun temp(celsius: Double, metric: Boolean): String =
        if (metric) "%.1f°C".format(celsius) else "%.1f°F".format(celsius * 9.0 / 5.0 + 32.0)
}
