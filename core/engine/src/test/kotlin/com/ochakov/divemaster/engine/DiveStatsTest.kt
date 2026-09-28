package com.ochakov.divemaster.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiveStatsTest {

    @Test
    fun `stats from samples exclude near-surface depths from the average`() {
        val stats = DiveStats.fromSamples(
            depthsM = listOf(0.0, 5.0, 10.0, 0.1),
            tempsC = listOf(null, 20.0, 18.0, 25.0),
        )
        assertEquals(10.0, stats.maxDepthM, 1e-9)
        assertEquals(7.5, stats.avgDepthM, 1e-9)
        assertEquals(18.0, stats.minTempC!!, 1e-9)
    }

    @Test
    fun `no temperatures yields null min temp`() {
        val stats = DiveStats.fromSamples(listOf(5.0, 6.0), listOf(null, null))
        assertNull(stats.minTempC)
    }

    @Test
    fun `max ascent rate uses the trailing window like the engine`() {
        // 20 m, then up 2 m in 8 s = 15 m/min; descents never count.
        val depths = List(10) { 20.0 } + List(8) { i -> 20.0 - 0.25 * (i + 1) } + List(10) { 18.0 } + List(5) { 25.0 }
        assertEquals(15.0, DiveStats.maxAscentRate(depths), 0.01)
        assertEquals(0.0, DiveStats.maxAscentRate(List(30) { 10.0 }), 1e-9)
    }

    @Test
    fun `safety stop result mirrors the engine rules`() {
        val shallow = List(300) { 8.0 }
        assertEquals(SafetyStopResult.NOT_REQUIRED, DiveStats.safetyStopResult(shallow, 180, 4.0, 6.0))
        val done = List(60) { 12.0 } + List(200) { 5.0 } + List(30) { 0.0 }
        assertEquals(SafetyStopResult.DONE, DiveStats.safetyStopResult(done, 180, 4.0, 6.0))
        val incomplete = List(60) { 12.0 } + List(100) { 5.0 } + List(100) { 3.0 } + List(30) { 0.0 }
        assertEquals(SafetyStopResult.INCOMPLETE, DiveStats.safetyStopResult(incomplete, 180, 4.0, 6.0))
    }
}
