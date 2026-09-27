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
 * Cold-start surface reference: an app restarted underwater must not take
 * ambient pressure at depth for the atmosphere (Ev's 2026-09-25 dive: a
 * restart at ~4.7 m logged "surface pressure 1487 mbar" and a 3.8 m dive).
 */
class SurfaceReferenceTest {

    private val converter = DepthConverter(WaterType.EN13319)
    private val trueSurface = 1.015
    private val config = DiveEngineConfig(WaterType.EN13319, Gas.AIR, GradientFactors.OFF)

    private fun coldEngine(memory: SurfaceMemory?) = DiveEngine(
        config,
        TissueState.saturatedAir(DepthConverter.STANDARD_ATMOSPHERE_BAR),
        0.0,
        null,
        memory,
    )

    private class Run(val engine: DiveEngine) {
        var tsMs = 0L
        val events = mutableListOf<EngineEvent>()

        fun bar(bar: Double) {
            tsMs += 1000
            events += engine.onSample(PressureSample(tsMs, bar))
        }

        fun bar(bar: Double, seconds: Int) = repeat(seconds) { bar(bar) }

        inline fun <reified T : EngineEvent> eventsOf(): List<T> = events.filterIsInstance<T>()
    }

    @Test
    fun `restart at depth with a fresh memory uses the memory and flags a late start`() {
        val r = Run(coldEngine(SurfaceMemory(trueSurface, ageSec = 1800.0)))
        r.bar(1.487, 5) // Ev's case: first reading 1487 mbar at ~4.7 m
        val start = r.eventsOf<EngineEvent.DiveStarted>().single()
        assertTrue(start.startedUnderwater)
        assertEquals(trueSurface, start.surfacePressureBar, 1e-9)
        assertEquals(4.72, r.engine.displayState.depthM, 0.02)
        assertTrue(r.engine.displayState.startedUnderwater)
        assertEquals(DivePhase.DIVING, r.engine.displayState.phase)
    }

    @Test
    fun `restart at depth with no memory guesses the standard atmosphere`() {
        val r = Run(coldEngine(null))
        r.bar(1.487, 5)
        val start = r.eventsOf<EngineEvent.DiveStarted>().single()
        assertTrue(start.startedUnderwater)
        assertEquals(DepthConverter.STANDARD_ATMOSPHERE_BAR, start.surfacePressureBar, 1e-9)
        assertFalse(r.engine.displayState.referenceTrusted)
    }

    @Test
    fun `a stale memory is ignored but the ceiling still says water`() {
        val r = Run(coldEngine(SurfaceMemory(0.920, ageSec = 7 * 3600.0))) // mountain, yesterday
        r.bar(1.487, 5)
        val start = r.eventsOf<EngineEvent.DiveStarted>().single()
        assertEquals(DepthConverter.STANDARD_ATMOSPHERE_BAR, start.surfacePressureBar, 1e-9)
    }

    @Test
    fun `a first reading above the ceiling is never taken as the surface`() {
        val r = Run(coldEngine(null))
        r.bar(1.150)
        assertEquals(DepthConverter.STANDARD_ATMOSPHERE_BAR, r.engine.displayState.surfacePressureBar, 1e-9)
    }

    @Test
    fun `surface start calibrates to the first reading as before`() {
        // Weather rose 15 hPa since the memory: below the 30 hPa excess, so no suspicion.
        val r = Run(coldEngine(SurfaceMemory(1.000, ageSec = 600.0)))
        r.bar(trueSurface, 30)
        assertEquals(trueSurface, r.engine.displayState.surfacePressureBar, 1e-9)
        assertEquals(0.0, r.engine.displayState.depthM, 1e-9)
        assertTrue(r.engine.displayState.referenceTrusted)
        assertTrue(r.eventsOf<EngineEvent.DiveStarted>().isEmpty())
    }

    @Test
    fun `no memory at all behaves exactly like the old first-sample calibration`() {
        val r = Run(coldEngine(null))
        r.bar(trueSurface, 30)
        assertEquals(trueSurface, r.engine.displayState.surfacePressureBar, 1e-9)
        assertFalse(r.engine.displayState.startCheckActive)
        assertTrue(r.engine.displayState.referenceTrusted)
    }

    @Test
    fun `ambiguous reading with a stale memory calibrates immediately`() {
        val r = Run(coldEngine(SurfaceMemory(0.920, ageSec = 7 * 3600.0)))
        r.bar(trueSurface)
        assertFalse(r.engine.displayState.startCheckActive)
        assertEquals(trueSurface, r.engine.displayState.surfacePressureBar, 1e-9)
    }

    @Test
    fun `ambiguous start in moving water keeps the memory and starts a late dive`() {
        val r = Run(coldEngine(SurfaceMemory(trueSurface, ageSec = 600.0)))
        // Wrist at ~0.5 m swinging +/-0.15 m: 0.03 bar peak-to-peak, well over the motion threshold.
        var i = 0
        repeat(30) { r.bar(trueSurface + 0.050 + if (i++ % 2 == 0) 0.015 else -0.015) }
        val start = r.eventsOf<EngineEvent.DiveStarted>().single()
        assertTrue(start.startedUnderwater)
        assertEquals(trueSurface, start.surfacePressureBar, 1e-9)
        assertEquals(1000L, start.startEpochMs) // backdated to the engine's first sample
    }

    @Test
    fun `ambiguous start that stays dead still is dry land and calibrates after the window`() {
        // Drove down from altitude: memory 0.920 bar (4 h old); the boat deck
        // reads 1.015 - that looks like 0.95 m of water, but nothing moves.
        val r = Run(coldEngine(SurfaceMemory(0.920, ageSec = 4 * 3600.0)))
        r.bar(trueSurface, 10)
        assertTrue(r.engine.displayState.startCheckActive)
        assertFalse(r.engine.displayState.referenceTrusted)
        assertTrue(r.eventsOf<EngineEvent.DiveStarted>().isEmpty())
        r.bar(trueSurface, 15) // 20 s window elapses
        assertFalse(r.engine.displayState.startCheckActive)
        assertTrue(r.engine.displayState.referenceTrusted)
        assertEquals(trueSurface, r.engine.displayState.surfacePressureBar, 1e-9)
        assertEquals(0.0, r.engine.displayState.depthM, 1e-9)
        r.bar(trueSurface, 60)
        assertTrue(r.eventsOf<EngineEvent.DiveStarted>().isEmpty())
    }

    @Test
    fun `a too-high reference is lowered when the diver rises above it and the dive is re-based`() {
        // Memory 0.5 m too deep; a restart at a true 5 m reads as 4.5 m.
        val wrongRef = trueSurface + 0.050
        val r = Run(coldEngine(SurfaceMemory(wrongRef, ageSec = 600.0)))
        r.bar(converter.ambientBar(5.0, trueSurface), 30)
        assertEquals(4.5, r.engine.displayState.depthM, 0.01)
        r.bar(converter.ambientBar(0.25, trueSurface), 5) // -0.25 m under the wrong reference
        val corr = r.eventsOf<EngineEvent.SurfaceReferenceCorrected>().single()
        assertEquals(converter.ambientBar(0.25, trueSurface), corr.surfacePressureBar, 1e-9)
        assertEquals(0.25, corr.depthShiftM, 0.01)
        assertEquals(0.0, r.engine.displayState.depthM, 1e-9)
        assertEquals(4.75, r.engine.displayState.maxDepthM, 0.01)
        assertEquals(DivePhase.DIVING, r.engine.displayState.phase)
    }

    @Test
    fun `a small negative excursion does not move the reference`() {
        val r = Run(coldEngine(SurfaceMemory(trueSurface, ageSec = 600.0)))
        r.bar(converter.ambientBar(3.0, trueSurface), 10)
        r.bar(trueSurface - 0.010, 5) // 0.1 m "above the surface": inside the margin
        assertTrue(r.eventsOf<EngineEvent.SurfaceReferenceCorrected>().isEmpty())
        assertEquals(trueSurface, r.engine.displayState.surfacePressureBar, 1e-9)
    }

    @Test
    fun `guessed reference too low - standing still at the real surface lets the dive end`() {
        // No memory; a strong high (1.040) makes the standard-atmosphere guess
        // 0.27 m too low: a surfaced diver would sit at a permanent 0.27 m.
        val highSurface = 1.040
        val r = Run(coldEngine(null))
        r.bar(converter.ambientBar(5.0, highSurface), 30)
        assertTrue(r.eventsOf<EngineEvent.DiveStarted>().single().startedUnderwater)
        r.bar(highSurface, 65) // out of the water, dead still for a minute
        val corr = r.eventsOf<EngineEvent.SurfaceReferenceCorrected>().single()
        assertEquals(highSurface, corr.surfacePressureBar, 1e-9)
        assertEquals(5.0, r.engine.displayState.maxDepthM, 0.01) // re-based to the true max
        r.bar(highSurface, 65)
        assertEquals(1, r.eventsOf<EngineEvent.DiveEnded>().size)
        assertEquals(DivePhase.SURFACE, r.engine.displayState.phase)
    }

    @Test
    fun `recalibrate discards the dive and takes the current pressure as the surface`() {
        val r = Run(coldEngine(SurfaceMemory(0.920, ageSec = 600.0)))
        r.bar(trueSurface + 0.010)
        r.bar(trueSurface - 0.010) // motion: judged underwater against the 0.920 memory
        r.bar(trueSurface, 10)
        assertEquals(1, r.eventsOf<EngineEvent.DiveStarted>().size)
        assertTrue(r.engine.displayState.startedUnderwater)
        val events = r.engine.recalibrate()
        assertTrue(events.any { it is EngineEvent.DiveDiscarded })
        assertEquals(DivePhase.SURFACE, r.engine.displayState.phase)
        assertEquals(trueSurface, r.engine.displayState.surfacePressureBar, 1e-9)
        assertFalse(r.engine.displayState.startedUnderwater)
        assertTrue(r.engine.displayState.referenceTrusted)
        r.bar(trueSurface, 30)
        assertEquals(0.0, r.engine.displayState.depthM, 1e-9)
        assertEquals(1, r.eventsOf<EngineEvent.DiveStarted>().size)
    }

    @Test
    fun `late-start flag clears for the next dive`() {
        val r = Run(coldEngine(SurfaceMemory(trueSurface, ageSec = 600.0)))
        r.bar(converter.ambientBar(5.0, trueSurface), 90)
        r.bar(trueSurface, 70) // surfaces, dive ends
        assertEquals(1, r.eventsOf<EngineEvent.DiveEnded>().size)
        r.bar(converter.ambientBar(5.0, trueSurface), 10)
        val starts = r.eventsOf<EngineEvent.DiveStarted>()
        assertEquals(2, starts.size)
        assertTrue(starts[0].startedUnderwater)
        assertFalse(starts[1].startedUnderwater)
    }

    @Test
    fun `hot-reload seed is exact and bypasses the memory`() {
        val engine = DiveEngine(
            config,
            TissueState.saturatedAir(trueSurface),
            0.0,
            trueSurface,
            SurfaceMemory(0.500, ageSec = 0.0),
        )
        engine.onSample(PressureSample(1000, trueSurface))
        assertEquals(trueSurface, engine.displayState.surfacePressureBar, 1e-9)
        assertTrue(engine.displayState.referenceTrusted)
    }
}
