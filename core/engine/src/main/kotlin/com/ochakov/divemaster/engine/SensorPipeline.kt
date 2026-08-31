package com.ochakov.divemaster.engine

/**
 * Turns the raw high-rate pressure stream into a clean 1 Hz stream: readings
 * are grouped into one-second buckets and each bucket emits its median, which
 * suppresses single-sample spikes without lagging like a low-pass filter.
 */
class SensorPipeline(private val intervalMs: Long = 1000) {
    private var bucketStartMs = -1L
    private val bucket = mutableListOf<Double>()

    /** Feed one raw reading; returns a filtered sample whenever a bucket closes. */
    fun onRaw(timestampMs: Long, pressureBar: Double): PressureSample? {
        val start = timestampMs - timestampMs % intervalMs
        return when {
            bucketStartMs < 0 -> {
                bucketStartMs = start
                bucket += pressureBar
                null
            }
            start == bucketStartMs -> {
                bucket += pressureBar
                null
            }
            else -> {
                val median = median(bucket)
                val sampleTs = bucketStartMs + intervalMs
                bucketStartMs = start
                bucket.clear()
                bucket += pressureBar
                PressureSample(sampleTs, median)
            }
        }
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }
}
