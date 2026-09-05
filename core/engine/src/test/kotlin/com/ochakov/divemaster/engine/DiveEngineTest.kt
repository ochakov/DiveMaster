package com.ochakov.divemaster.engine

import com.ochakov.divemaster.deco.Buhlmann
import com.ochakov.divemaster.deco.DepthConverter
import com.ochakov.divemaster.deco.Gas
import com.ochakov.divemaster.deco.GradientFactors
import com.ochakov.divemaster.deco.TissueState
import com.ochakov.divemaster.deco.WaterType
import com.ochakov.divemaster.deco.ZhL16c
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiveEngineTest {

    private class Harness(gas: Gas = Gas.AIR) {
        val surface = DepthConverter.STANDARD_ATMOSPHERE_BAR
        val converter = DepthConverter(WaterType.EN13319)
        val engine = DiveEngine(
            DiveEngineConfig(WaterType.EN13319, gas, GradientFactors.OFF),
            TissueState.saturatedAir(surface),
            0.0,
            surface,
        )
        var tsMs = 0L
        val events = mutableListOf<EngineEvent>()

        fun step(depthM: Double, tempC: Double? = null) {
            tsMs += 1000
            events += engine.onSample(PressureSample(tsMs, converter.ambientBar(depthM, surface), tempC))
        }

        fun feed(depthM: Double, seconds: Int, tempC: Double? = null) = repeat(seconds) { step(depthM, tempC) }

        fun feedPressure(bar: Double, seconds: Int) = repeat(seconds) {
            tsMs += 1000
            events += engine.onSample(PressureSample(tsMs, bar))
        }

        inline fun <reified T : EngineEvent> eventsOf(): List<T> = events.filterIsInstance<T>()
    }

    @Test
    fun `dive start is confirmed after hold and backdated to submersion`() {
        val h = Harness()
        h.feed(0.0, 30)
        h.feed(0.5, 2)   // submerged at t=31 s
        h.feed(2.0, 10)  // past 1.2 m from t=33 s, confirmed at t=36 s
        val starts = h.eventsOf<EngineEvent.DiveStarted>()
        assertEquals(1, starts.size)
        assertEquals(31_000L, starts[0].startEpochMs)

        val samples = h.eventsOf<EngineEvent.SampleRecorded>()
        // Backfilled from submersion: offsets 0..5 with no NDL, then live samples.
        assertEquals((0..5).toList(), samples.take(6).map { it.tOffsetSec })
        assertTrue(samples.take(6).all { it.ndlMin == null })
        assertEquals(6, samples[6].tOffsetSec) // live samples continue seamlessly
        assertEquals(DivePhase.DIVING, h.engine.displayState.phase)
    }

    @Test
    fun `a splash shallower than the start threshold never starts a dive`() {
        val h = Harness()
        h.feed(0.0, 10)
        h.feed(0.2, 120) // below the 0.3 m start threshold
        h.feed(0.0, 10)
        assertTrue(h.eventsOf<EngineEvent.DiveStarted>().isEmpty())
        assertEquals(DivePhase.SURFACE, h.engine.displayState.phase)
    }

    @Test
    fun `a short bounce is discarded not logged`() {
        val h = Harness()
        h.feed(0.0, 10)
        h.feed(2.0, 20)
        h.feed(0.0, 70)
        assertEquals(1, h.eventsOf<EngineEvent.DiveStarted>().size)
        assertEquals(1, h.eventsOf<EngineEvent.DiveDiscarded>().size)
        assertTrue(h.eventsOf<EngineEvent.DiveEnded>().isEmpty())
        assertEquals(DivePhase.SURFACE, h.engine.displayState.phase)
    }

    @Test
    fun `brief surfacing under a minute continues the same dive`() {
        val h = Harness()
        h.feed(0.0, 5)
        h.feed(10.0, 300, tempC = 22.0)
        h.feed(0.2, 30)             // 30 s on the surface — not enough to end
        h.feed(10.0, 180, tempC = 18.0)
        h.feed(0.0, 90)             // now the dive ends
        assertEquals(1, h.eventsOf<EngineEvent.DiveStarted>().size)
        val ended = h.eventsOf<EngineEvent.DiveEnded>()
        assertEquals(1, ended.size)
        // Start backdated to t=6 s, end backdated to final surfacing at t=516 s.
        assertEquals(510, ended[0].durationSec)
        assertEquals(516_000L, ended[0].endEpochMs)
        assertEquals(10.0, ended[0].maxDepthM, 0.01)
        assertEquals(10.0, ended[0].avgDepthM, 0.05)
        assertEquals(18.0, ended[0].minTempC!!, 0.01)
    }

    @Test
    fun `dive end is backdated to the surfacing moment`() {
        val h = Harness()
        h.feed(0.0, 5)
        h.feed(10.0, 120)
        h.feed(0.0, 70)
        val ended = h.eventsOf<EngineEvent.DiveEnded>().single()
        assertEquals(126_000L, ended.endEpochMs)
        assertEquals(120, ended.durationSec)
    }

    @Test
    fun `average excludes surface samples and max survives the profile`() {
        val h = Harness()
        h.feed(0.0, 5)
        h.feed(10.0, 60)
        h.feed(20.0, 60)
        h.feed(0.0, 70)
        val ended = h.eventsOf<EngineEvent.DiveEnded>().single()
        assertEquals(20.0, ended.maxDepthM, 0.01)
        assertEquals(15.0, ended.avgDepthM, 0.3)
    }

    @Test
    fun `surface reference adapts to weather then freezes at dive start`() {
        val h = Harness()
        h.feedPressure(1.01325, 200)
        h.feedPressure(1.01825, 600) // +5 hPa weather front, EMA converges
        assertEquals(0.0, h.engine.displayState.depthM, 0.02)

        h.feedPressure(h.converter.ambientBar(10.0, 1.01825), 120)
        assertEquals(10.0, h.engine.displayState.depthM, 0.05)
        assertEquals(1.01825, h.engine.displayState.surfacePressureBar, 0.0005)
    }

    @Test
    fun `tissues load with air on the surface even when nitrox is configured`() {
        val h = Harness(gas = Gas.nitrox(32))
        h.feed(0.0, 7200)
        val airTarget = (h.surface - ZhL16c.WATER_VAPOR_BAR) * Gas.AIR.n2Fraction
        for (i in 0 until ZhL16c.SIZE) {
            assertEquals("compartment $i", airTarget, h.engine.tissue[i], 1e-6)
        }
    }

    @Test
    fun `engine NDL matches a direct deco-model computation`() {
        val h = Harness()
        h.feed(0.0, 10)
        h.feed(30.0, 600)

        var reference = TissueState.saturatedAir(h.surface)
        val ambient = h.converter.ambientBar(30.0, h.surface)
        repeat(600) { reference = Buhlmann.loadConstant(reference, ambient, Gas.AIR, 1.0) }
        val expected = Buhlmann.ndlSeconds(reference, ambient, h.surface, Gas.AIR, 1.0)

        assertEquals(expected / 60.0, h.engine.displayState.ndlMin, 0.05)
        val lastRecorded = h.eventsOf<EngineEvent.SampleRecorded>().last().ndlMin
        assertNotNull(lastRecorded)
        assertEquals(expected / 60.0, lastRecorded!!, 0.1)
    }

    @Test
    fun `NDL shrinks with bottom time`() {
        val h = Harness()
        h.feed(0.0, 5)
        h.feed(30.0, 60)
        val early = h.engine.displayState.ndlMin
        h.feed(30.0, 540)
        val late = h.engine.displayState.ndlMin
        assertTrue(late < early)
    }

    @Test
    fun `CNS clock accumulates on nitrox at depth`() {
        val h = Harness(gas = Gas.nitrox(32))
        h.feed(0.0, 10)
        h.feed(30.0, 600) // ppO2 1.284 bar for 10 min
        assertEquals(0.054, h.engine.cnsFraction, 0.01)
        assertEquals(1.284, h.engine.displayState.ppO2Bar, 0.01)
    }

    @Test
    fun `abort discards a short dive and finalizes a long one`() {
        val short = Harness()
        short.feed(0.0, 5)
        short.feed(5.0, 20)
        short.events += short.engine.abortDive()
        assertEquals(1, short.eventsOf<EngineEvent.DiveDiscarded>().size)
        assertTrue(short.eventsOf<EngineEvent.DiveEnded>().isEmpty())

        val long = Harness()
        long.feed(0.0, 5)
        long.feed(10.0, 300)
        long.events += long.engine.abortDive()
        val ended = long.eventsOf<EngineEvent.DiveEnded>().single()
        assertEquals(299, ended.durationSec)
        assertEquals(DivePhase.SURFACE, long.engine.displayState.phase)
    }

    @Test
    fun `vertical rate tracks descent and ascent`() {
        val h = Harness()
        // Descend at 0.2 m/s = 12 m/min.
        for (i in 1..90) h.step(i * 0.2)
        assertEquals(-12.0, h.engine.displayState.verticalRateMPerMin, 0.6)
        // Ascend at 0.15 m/s = 9 m/min.
        for (i in 1..60) h.step(18.0 - i * 0.15)
        assertEquals(9.0, h.engine.displayState.verticalRateMPerMin, 0.6)
    }

    @Test
    fun `safety stop arms only after passing the required depth`() {
        val h = Harness()
        h.feed(0.0, 5)
        h.feed(8.0, 120)  // never past 10 m
        h.feed(5.0, 60)   // in the stop window, but nothing armed
        assertEquals(SafetyStopState.NONE, h.engine.displayState.safetyStop)
    }

    @Test
    fun `safety stop is pending at depth then counts down in the window to done`() {
        val h = Harness()
        h.feed(0.0, 5)
        h.feed(20.0, 120)
        assertEquals(SafetyStopState.PENDING, h.engine.displayState.safetyStop)
        assertEquals(180, h.engine.displayState.safetyStopRemainingSec)

        h.feed(5.0, 30)
        assertEquals(SafetyStopState.ACTIVE, h.engine.displayState.safetyStop)
        assertEquals(150, h.engine.displayState.safetyStopRemainingSec)

        h.feed(5.0, 155)
        assertEquals(SafetyStopState.DONE, h.engine.displayState.safetyStop)
        assertEquals(0, h.engine.displayState.safetyStopRemainingSec)
    }

    @Test
    fun `safety stop pauses outside the window in both directions and resumes`() {
        val h = Harness()
        h.feed(0.0, 5)
        h.feed(20.0, 120)
        h.feed(5.0, 60) // 120 s remaining

        h.feed(8.0, 40) // drifted below the window
        assertEquals(SafetyStopState.PAUSED, h.engine.displayState.safetyStop)
        assertEquals(120, h.engine.displayState.safetyStopRemainingSec)

        h.feed(3.0, 20) // drifted above the window
        assertEquals(SafetyStopState.PAUSED, h.engine.displayState.safetyStop)
        assertEquals(120, h.engine.displayState.safetyStopRemainingSec)

        h.feed(5.0, 125) // back in the window: resumes and finishes
        assertEquals(SafetyStopState.DONE, h.engine.displayState.safetyStop)
    }

    @Test
    fun `safety stop resets for the next dive`() {
        val h = Harness()
        h.feed(0.0, 5)
        h.feed(20.0, 120)
        h.feed(5.0, 200) // complete the stop
        assertEquals(SafetyStopState.DONE, h.engine.displayState.safetyStop)
        h.feed(0.0, 70)  // dive ends
        assertEquals(SafetyStopState.NONE, h.engine.displayState.safetyStop)

        h.feed(15.0, 10) // next dive, freshly armed
        assertEquals(SafetyStopState.PENDING, h.engine.displayState.safetyStop)
        assertEquals(180, h.engine.displayState.safetyStopRemainingSec)
    }

    @Test
    fun `sustained half-metre swim triggers and holds with production defaults`() {
        // Ev's real-world case: finning to the entry at ~0.5 m for minutes must
        // register as a dive and keep reading ~0.5 m, not re-zero.
        val h = Harness()
        h.feed(0.0, 10)
        h.feed(0.5, 240)
        assertEquals(1, h.eventsOf<EngineEvent.DiveStarted>().size)
        assertEquals(DivePhase.DIVING, h.engine.displayState.phase)
        assertEquals(0.5, h.engine.displayState.depthM, 0.05)
    }

    @Test
    fun `low freeze threshold keeps a sustained shallow dunk from re-zeroing`() {
        val surface = DepthConverter.STANDARD_ATMOSPHERE_BAR
        val converter = DepthConverter(WaterType.EN13319)
        val engine = DiveEngine(
            DiveEngineConfig(
                WaterType.EN13319, Gas.AIR, GradientFactors.OFF,
                startDepthM = 0.5, endDepthM = 0.3, submersionEpsilonM = 0.15,
                surfaceRefFreezeDepthM = 0.25,
            ),
            TissueState.saturatedAir(surface), 0.0, surface,
        )
        var ts = 0L
        val events = mutableListOf<EngineEvent>()
        repeat(10) { ts += 1000; events += engine.onSample(PressureSample(ts, converter.ambientBar(0.0, surface))) }
        // A steady 0.53 m held four minutes — long past the 90 s reference half-life.
        repeat(240) { ts += 1000; events += engine.onSample(PressureSample(ts, converter.ambientBar(0.53, surface))) }

        assertTrue("dive should trigger", events.any { it is EngineEvent.DiveStarted })
        // The reference froze at 0.25 m, so a steady dunk still reads ~0.53 m
        // four minutes in rather than drifting back to zero.
        assertEquals(0.53, engine.displayState.depthM, 0.05)
    }

    @Test
    fun `no NDL recorded while unlimited in very shallow water`() {
        val h = Harness()
        h.feed(0.0, 5)
        h.feed(2.0, 120)
        val live = h.eventsOf<EngineEvent.SampleRecorded>().last()
        assertNull(live.ndlMin) // NDL infinite at 2 m for a rested diver
        assertTrue(h.engine.displayState.ndlMin.isInfinite())
    }
}
