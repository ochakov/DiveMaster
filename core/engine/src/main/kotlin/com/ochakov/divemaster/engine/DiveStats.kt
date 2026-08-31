package com.ochakov.divemaster.engine

/**
 * Recomputes dive statistics from stored 1 Hz samples. Used by crash
 * recovery to finalize a dive whose end was never written.
 */
object DiveStats {
    data class Stats(val maxDepthM: Double, val avgDepthM: Double, val minTempC: Double?)

    fun fromSamples(depthsM: List<Double>, tempsC: List<Double?>, avgMinDepthM: Double = 0.3): Stats {
        var max = 0.0
        var sum = 0.0
        var count = 0
        var minTemp: Double? = null
        for (i in depthsM.indices) {
            val depth = depthsM[i]
            if (depth > max) max = depth
            if (depth >= avgMinDepthM) {
                sum += depth
                count++
            }
            val temp = tempsC.getOrNull(i)
            if (temp != null && (minTemp == null || temp < minTemp!!)) minTemp = temp
        }
        return Stats(max, if (count > 0) sum / count else max, minTemp)
    }
}
