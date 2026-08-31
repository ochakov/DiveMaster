package com.ochakov.divemaster.engine

/**
 * The hidden dev-mode dive: 20 m for 5 minutes with realistic descent and
 * ascent rates and a 1-minute stop at 5 m, then enough surface tail for the
 * engine's 60-second end-of-dive confirmation.
 */
object SimulatorProfile {
    private data class Segment(val durationSec: Double, val fromM: Double, val toM: Double)

    private val segments = listOf(
        Segment(15.0, 0.0, 0.0),                  // surface
        Segment(20.0 / 18.0 * 60.0, 0.0, 20.0),   // descend at 18 m/min
        Segment(300.0, 20.0, 20.0),               // bottom time
        Segment(15.0 / 9.0 * 60.0, 20.0, 5.0),    // ascend at 9 m/min
        Segment(60.0, 5.0, 5.0),                  // stop at 5 m
        Segment(5.0 / 6.0 * 60.0, 5.0, 0.0),      // final ascent at 6 m/min
        Segment(90.0, 0.0, 0.0),                  // surface tail (> end-hold)
    )

    val totalDurationSec: Double = segments.sumOf { it.durationSec }

    fun depthAt(tSec: Double): Double {
        var t = tSec
        for (segment in segments) {
            if (t <= segment.durationSec) {
                return segment.fromM + (segment.toM - segment.fromM) * (t / segment.durationSec)
            }
            t -= segment.durationSec
        }
        return 0.0
    }
}
