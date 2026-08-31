package com.ochakov.divemaster.ui

import kotlin.math.roundToInt

/**
 * Display-side unit conversion. Everything is stored and computed metric;
 * only formatting converts. Imperial depth shows whole feet, as dive
 * computers conventionally do.
 */
object Units {
    private const val FT_PER_M = 3.28084

    fun depth(meters: Double, metric: Boolean): String =
        if (metric) "%.1f".format(meters) else "${(meters * FT_PER_M).roundToInt()}"

    fun depthUnit(metric: Boolean): String = if (metric) "m" else "ft"

    fun depthWithUnit(meters: Double, metric: Boolean): String =
        "${depth(meters, metric)} ${depthUnit(metric)}"

    fun wholeDepthWithUnit(meters: Double, metric: Boolean): String =
        if (metric) "%.0f m".format(meters) else "${(meters * FT_PER_M).roundToInt()} ft"

    fun windowText(minM: Double, maxM: Double, metric: Boolean): String =
        if (metric) {
            "%.0f–%.0f m".format(minM, maxM)
        } else {
            "${(minM * FT_PER_M).roundToInt()}–${(maxM * FT_PER_M).roundToInt()} ft"
        }

    fun temp(celsius: Double, metric: Boolean): String =
        if (metric) "%.1f°C".format(celsius) else "%.1f°F".format(celsius * 9.0 / 5.0 + 32.0)

    fun tempShort(celsius: Double, metric: Boolean): String =
        if (metric) "%.1f°".format(celsius) else "%.1f°".format(celsius * 9.0 / 5.0 + 32.0)

    fun rate(mPerMin: Double, metric: Boolean): String =
        if (metric) "%.0f m/min".format(mPerMin) else "${(mPerMin * FT_PER_M).roundToInt()} ft/min"
}
