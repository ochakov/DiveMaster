package com.ochakov.divemaster.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorProfileTest {

    @Test
    fun `profile starts and ends on the surface with a 20 m maximum`() {
        assertEquals(0.0, SimulatorProfile.depthAt(0.0), 1e-9)
        assertEquals(0.0, SimulatorProfile.depthAt(SimulatorProfile.totalDurationSec), 1e-9)
        var max = 0.0
        var t = 0.0
        while (t <= SimulatorProfile.totalDurationSec) {
            val d = SimulatorProfile.depthAt(t)
            assertTrue(d >= 0.0)
            if (d > max) max = d
            t += 1.0
        }
        assertEquals(20.0, max, 0.01)
    }

    @Test
    fun `profile duration leaves room for the end-of-dive confirmation`() {
        assertEquals(681.7, SimulatorProfile.totalDurationSec, 0.5)
        // The last 60+ seconds sit on the surface so the engine can close the dive.
        assertEquals(0.0, SimulatorProfile.depthAt(SimulatorProfile.totalDurationSec - 65.0), 1e-9)
    }
}
