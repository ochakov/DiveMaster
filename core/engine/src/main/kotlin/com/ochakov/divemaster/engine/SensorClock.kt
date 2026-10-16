package com.ochakov.divemaster.engine

/**
 * Places a sensor reading in the second it was *measured*, not the second it
 * was *delivered*.
 *
 * Android stamps every `SensorEvent` with the hardware time of the measurement
 * (`SystemClock.elapsedRealtimeNanos()` base). While the CPU sleeps, a
 * non-wake-up sensor's readings queue up in the sensor hub and arrive all at
 * once when it wakes. Stamping those with the wall clock at arrival (what the
 * service did until 2026-10-16) folds the whole backlog into a single 1 s
 * bucket, whose median then reads as one impossible sample — 19.5 m followed
 * by 15.6 m one second later in Ev's dive 10. Converting the event time to the
 * wall clock spreads the backlog over the seconds it really covers.
 *
 * The conversion falls back to the delivery time when the event clock is
 * implausible for this device (ahead of the CPU clock, or older than
 * [maxAgeMs]), so a HAL with a different time base can never push a dive's
 * samples into the wrong hour.
 */
object SensorClock {
    /** Backlogs longer than this are not trusted as measurements; such events keep their arrival time. */
    const val DEFAULT_MAX_AGE_MS: Long = 60L * 60 * 1000

    /** Event clocks may run a little ahead of the CPU's reading of `elapsedRealtimeNanos`. */
    const val FUTURE_TOLERANCE_MS: Long = 2_000

    /** How long ago (ms) the reading was taken, as seen at delivery; negative if the event clock runs ahead. */
    fun eventAgeMs(nowElapsedNanos: Long, eventElapsedNanos: Long): Long =
        (nowElapsedNanos - eventElapsedNanos) / 1_000_000L

    /**
     * Wall-clock milliseconds of the measurement: [nowWallMs] minus the event's
     * age, or [nowWallMs] itself when the age is implausible.
     */
    fun eventWallMs(
        nowWallMs: Long,
        nowElapsedNanos: Long,
        eventElapsedNanos: Long,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    ): Long {
        val ageMs = eventAgeMs(nowElapsedNanos, eventElapsedNanos)
        if (ageMs < -FUTURE_TOLERANCE_MS || ageMs > maxAgeMs) return nowWallMs
        return nowWallMs - ageMs.coerceAtLeast(0L)
    }
}
