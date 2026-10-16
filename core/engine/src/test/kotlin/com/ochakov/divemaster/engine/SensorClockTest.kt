package com.ochakov.divemaster.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorClockTest {

    private val nowWall = 1_792_133_972_000L
    private val nowElapsed = 5_000_000_000_000L // 5000 s since boot

    @Test
    fun `a live reading keeps the current second`() {
        val event = nowElapsed - 3_000_000 // measured 3 ms ago
        assertEquals(nowWall - 3, SensorClock.eventWallMs(nowWall, nowElapsed, event))
    }

    @Test
    fun `a buffered backlog is placed in the seconds it was measured`() {
        // 60 readings taken once a second, delivered together 61 s after the first.
        val readings = (0 until 60).map { i -> nowElapsed - (61L - i) * 1_000_000_000L }
        val placed = readings.map { SensorClock.eventWallMs(nowWall, nowElapsed, it) }
        assertEquals(nowWall - 61_000, placed.first())
        assertEquals(nowWall - 2_000, placed.last())
        // ... and the pipeline would bucket them into 60 distinct seconds.
        assertEquals(60, placed.map { it / 1000 }.distinct().size)
    }

    @Test
    fun `an implausibly old event falls back to the delivery time`() {
        val event = nowElapsed - 2L * 60 * 60 * 1_000_000_000L // 2 h "old"
        assertEquals(nowWall, SensorClock.eventWallMs(nowWall, nowElapsed, event))
    }

    @Test
    fun `an event clock slightly ahead of the CPU clock is clamped to now`() {
        val event = nowElapsed + 500_000_000 // 0.5 s in the "future"
        assertEquals(nowWall, SensorClock.eventWallMs(nowWall, nowElapsed, event))
    }

    @Test
    fun `an event clock far ahead means a different time base and falls back to now`() {
        val event = nowElapsed + 30L * 1_000_000_000L
        assertEquals(nowWall, SensorClock.eventWallMs(nowWall, nowElapsed, event))
    }

    @Test
    fun `age is reported in whole milliseconds`() {
        assertEquals(61_000L, SensorClock.eventAgeMs(nowElapsed, nowElapsed - 61_000_000_000L))
        assertEquals(-500L, SensorClock.eventAgeMs(nowElapsed, nowElapsed + 500_000_000L))
    }
}
