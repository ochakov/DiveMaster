package com.ochakov.divemaster.engine

import com.ochakov.divemaster.deco.DepthConverter
import com.ochakov.divemaster.deco.Gas
import com.ochakov.divemaster.deco.GradientFactors
import com.ochakov.divemaster.deco.TissueState
import com.ochakov.divemaster.deco.WaterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End of dive (Ev, 2026-09-28): the first surface touch is the end time and
 * starts the 60 s hold; splashes while climbing out don't reset it, only a
 * sustained re-descent past the start depth cancels it.
 */
class DiveEndRuleTest {

    private class Harness {
        val surface = DepthConverter.STANDARD_ATMOSPHERE_BAR
        val converter = DepthConverter(WaterType.EN13319)
        val engine = DiveEngine(
            DiveEngineConfig(WaterType.EN13319, Gas.AIR, GradientFactors.OFF),
            TissueState.saturatedAir(surface),
            0.0,
            surface,
        )
        var tsMs = 0L
        val events = mutableListOf<EngineEvent>()

        fun feed(depthM: Double, seconds: Int) = repeat(seconds) {
            tsMs += 1000
            events += engine.onSample(PressureSample(tsMs, converter.ambientBar(depthM, surface)))
        }

        inline fun <reified T : EngineEvent> eventsOf(): List<T> = events.filterIsInstance<T>()
    }

    @Test
    fun `splashes while climbing out do not reset the end hold`() {
        val h = Harness()
        h.feed(0.0, 5)
        h.feed(10.0, 300)
        h.feed(0.1, 20)   // surface touch at t=306 s
        h.feed(0.46, 4)   // dive 6's splash: 4 s above the cancel depth
        h.feed(0.25, 40)  // bobbing between the surface and the cancel depth
        val ended = h.eventsOf<EngineEvent.DiveEnded>().single()
        assertEquals(306_000L, ended.endEpochMs)
        assertEquals(300, ended.durationSec)
    }

    @Test
    fun `endPending is exposed while the hold runs`() {
        val h = Harness()
        h.feed(0.0, 5)
        h.feed(10.0, 120)
        assertFalse(h.engine.displayState.endPending)
        h.feed(0.1, 5)
        assertTrue(h.engine.displayState.endPending)
        assertEquals(DivePhase.DIVING, h.engine.displayState.phase)
    }

    @Test
    fun `a sustained re-descent cancels the pending end and the dive continues`() {
        val h = Harness()
        h.feed(0.0, 5)
        h.feed(10.0, 300)
        h.feed(0.1, 20)   // pending from t=306 s
        h.feed(2.0, 12)   // 12 s past the cancel depth: cancelled
        assertFalse(h.engine.displayState.endPending)
        h.feed(10.0, 100)
        h.feed(0.0, 70)   // real end: first touch at t=438 s
        val ended = h.eventsOf<EngineEvent.DiveEnded>().single()
        assertEquals(438_000L, ended.endEpochMs)
        assertEquals(432, ended.durationSec)
        assertEquals(10.0, ended.maxDepthM, 0.01)
    }

    @Test
    fun `end reports the safety-stop result, worst ascent rate and CNS`() {
        val h = Harness()
        h.feed(0.0, 5)
        h.feed(12.0, 60)
        h.feed(5.0, 200)  // 12 -> 5 m in one step: 7 m over the 8 s window = 52.5 m/min
        h.feed(0.0, 70)
        val ended = h.eventsOf<EngineEvent.DiveEnded>().single()
        assertEquals(SafetyStopResult.DONE, ended.safetyStopResult)
        assertEquals(52.5, ended.maxAscentRateMPerMin, 0.1)
        assertEquals(0.0, ended.cnsFractionAtEnd, 1e-9) // air at 12 m: ppO2 below the CNS threshold
    }

    @Test
    fun `stop result is not required for a shallow dive and incomplete for a cut-short stop`() {
        val shallow = Harness()
        shallow.feed(0.0, 5)
        shallow.feed(8.0, 120)
        shallow.feed(0.0, 70)
        assertEquals(SafetyStopResult.NOT_REQUIRED, shallow.eventsOf<EngineEvent.DiveEnded>().single().safetyStopResult)

        val cut = Harness()
        cut.feed(0.0, 5)
        cut.feed(12.0, 60)
        cut.feed(5.0, 60)
        cut.feed(0.0, 70)
        assertEquals(SafetyStopResult.INCOMPLETE, cut.eventsOf<EngineEvent.DiveEnded>().single().safetyStopResult)
    }
}
