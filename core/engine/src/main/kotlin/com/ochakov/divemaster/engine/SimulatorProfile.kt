package com.ochakov.divemaster.engine

/**
 * The hidden dev-mode dive: 20 m for 5 minutes with realistic descent and
 * ascent rates, then a full 3-minute safety stop at 5 m that includes a
 * drift below the window (to demonstrate the pause/resume behavior), and
 * enough surface tail for the engine's 60-second end-of-dive confirmation.
 */
object SimulatorProfile {
    private data class Segment(val durationSec: Double, val fromM: Double, val toM: Double)

    private val segments = listOf(
        Segment(15.0, 0.0, 0.0),                  // surface
        Segment(20.0 / 18.0 * 60.0, 0.0, 20.0),   // descend at 18 m/min
        Segment(300.0, 20.0, 20.0),               // bottom time
        Segment(15.0 / 9.0 * 60.0, 20.0, 5.0),    // ascend at 9 m/min
        Segment(90.0, 5.0, 5.0),                  // safety stop, first half
        Segment(10.0, 5.0, 7.5),                  // drift below the window...
        Segment(15.0, 7.5, 7.5),                  // ...countdown pauses
        Segment(10.0, 7.5, 5.0),                  // back into the window
        Segment(120.0, 5.0, 5.0),                 // finish the stop (90+120 > 180 s)
        Segment(5.0 / 12.0 * 60.0, 5.0, 0.0),     // final ascent deliberately fast (12 m/min) to demo the ascent alert
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
