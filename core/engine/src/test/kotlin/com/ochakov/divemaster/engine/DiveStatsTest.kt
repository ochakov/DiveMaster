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
}
