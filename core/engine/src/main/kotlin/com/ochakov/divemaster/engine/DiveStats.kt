package com.ochakov.divemaster.engine

/**
 * Recomputes dive statistics from stored 1 Hz samples. Used by crash
 * recovery to finalize a dive whose end was never written, so it mirrors
 * what the engine accumulates live (same 8 s rate window, same safety-stop
 * rules) as closely as a depth series alone allows.
 */
object DiveStats {
    data class Stats(
        val maxDepthM: Double,
        val avgDepthM: Double,
        val minTempC: Double?,
        val maxAscentRateMPerMin: Double,
    )

    fun fromSamples(
        depthsM: List<Double>,
        tempsC: List<Double?>,
        avgMinDepthM: Double = 0.3,
        rateWindowSec: Int = 8,
    ): Stats {
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
        return Stats(max, if (count > 0) sum / count else max, minTemp, maxAscentRate(depthsM, rateWindowSec))
    }

    /**
     * Worst ascent rate (m/min) over a trailing window of 1 Hz samples —
     * the same estimate the engine feeds to the ascent-rate alert.
     */
    fun maxAscentRate(depthsM: List<Double>, rateWindowSec: Int = 8): Double {
        var worst = 0.0
        for (i in depthsM.indices) {
            val from = (i - rateWindowSec).coerceAtLeast(0)
            val span = i - from
            if (span < 3) continue
            val rate = (depthsM[from] - depthsM[i]) / span * 60.0
            if (rate > worst) worst = rate
        }
        return worst
    }

    /**
     * Safety-stop outcome of a 1 Hz depth series under the engine's rules:
     * arms once the dive passes [requiredBelowM]; the countdown runs only
     * inside [minDepthM]..[maxDepthM], pausing (never resetting) outside it.
     */
    fun safetyStopResult(
        depthsM: List<Double>,
        stopSeconds: Int,
        minDepthM: Double,
        maxDepthM: Double,
        requiredBelowM: Double = 10.0,
    ): SafetyStopResult {
        var armed = false
        var remaining = stopSeconds
        for (depth in depthsM) {
            if (!armed && depth >= requiredBelowM) armed = true
            if (armed && remaining > 0 && depth >= minDepthM && depth <= maxDepthM) remaining--
        }
        return when {
            !armed -> SafetyStopResult.NOT_REQUIRED
            remaining <= 0 -> SafetyStopResult.DONE
            else -> SafetyStopResult.INCOMPLETE
        }
    }
}
