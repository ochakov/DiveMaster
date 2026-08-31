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
        assertEquals(841.7, SimulatorProfile.totalDurationSec, 0.5)
        // The last 60+ seconds sit on the surface so the engine can close the dive.
        assertEquals(0.0, SimulatorProfile.depthAt(SimulatorProfile.totalDurationSec - 65.0), 1e-9)
    }

    @Test
    fun `profile completes a full safety stop despite the pause excursion`() {
        // Feed the profile through a real engine and require the stop to finish.
        val surface = com.ochakov.divemaster.deco.DepthConverter.STANDARD_ATMOSPHERE_BAR
        val converter = com.ochakov.divemaster.deco.DepthConverter(com.ochakov.divemaster.deco.WaterType.EN13319)
        val engine = DiveEngine(
            DiveEngineConfig(
                com.ochakov.divemaster.deco.WaterType.EN13319,
                com.ochakov.divemaster.deco.Gas.AIR,
                com.ochakov.divemaster.deco.GradientFactors.OFF,
            ),
            com.ochakov.divemaster.deco.TissueState.saturatedAir(surface),
            0.0,
            surface,
        )
        var sawPaused = false
        var sawDone = false
        var t = 0
        while (t <= SimulatorProfile.totalDurationSec.toInt()) {
            engine.onSample(
                PressureSample(t * 1000L, converter.ambientBar(SimulatorProfile.depthAt(t.toDouble()), surface)),
            )
            if (engine.displayState.safetyStop == SafetyStopState.PAUSED) sawPaused = true
            if (engine.displayState.safetyStop == SafetyStopState.DONE) sawDone = true
            t++
        }
        assertTrue("profile must demonstrate the paused state", sawPaused)
        assertTrue("profile must complete the stop", sawDone)
    }
}
