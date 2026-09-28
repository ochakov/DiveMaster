package com.ochakov.divemaster.engine

import com.ochakov.divemaster.deco.DepthConverter
import com.ochakov.divemaster.deco.Gas
import com.ochakov.divemaster.deco.GradientFactors
import com.ochakov.divemaster.deco.TissueState
import com.ochakov.divemaster.deco.WaterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real dives (Ev, Red Sea, 2026-09-25, Galaxy Watch Ultra 2, certified
 * computer on the other wrist) replayed through the engine from their
 * exported CSVs. They pin what the engine must keep doing with real water:
 * detect, track depth, arm and complete the safety stop, measure ascent
 * rates, and end at the first surface touch rather than after the float.
 */
class DiveReplayTest {

    private class Logged(val surfaceBar: Double, val samples: List<Triple<Int, Double, Double?>>)

    private fun load(name: String): Logged {
        val lines = javaClass.getResourceAsStream("/dives/$name")!!.bufferedReader().readLines()
        val surfaceMbar = lines.first { it.startsWith("# startEpochMs") }
            .split(' ').first { it.startsWith("surfaceMbar=") }.substringAfter('=').toDouble()
        val samples = lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("time_sec") }
            .map { line ->
                val f = line.split(',')
                Triple(f[0].toInt(), f[1].toDouble(), f[3].takeIf { it.isNotEmpty() }?.toDouble())
            }
        return Logged(surfaceMbar / 1000.0, samples)
    }

    private fun replay(logged: Logged, extraSurfaceSec: Int = 0): Pair<DiveEngine, List<EngineEvent>> {
        val converter = DepthConverter(WaterType.EN13319)
        val engine = DiveEngine(
            DiveEngineConfig(WaterType.EN13319, Gas.AIR, GradientFactors(0.40, 0.85)),
            TissueState.saturatedAir(logged.surfaceBar),
            0.0,
            logged.surfaceBar,
        )
        val events = mutableListOf<EngineEvent>()
        var last = 0
        for ((t, depth, _) in logged.samples) {
            events += engine.onSample(PressureSample(t * 1000L, converter.ambientBar(depth, logged.surfaceBar)))
            last = t
        }
        repeat(extraSurfaceSec) {
            last++
            events += engine.onSample(PressureSample(last * 1000L, logged.surfaceBar))
        }
        return engine to events
    }

    @Test
    fun `dive 6 - 29 m reef dive replays with the same profile, stop and worst ascent`() {
        val logged = load("2026-09-25_dive6_29m.csv")
        val (_, events) = replay(logged)
        val start = events.filterIsInstance<EngineEvent.DiveStarted>().single()
        assertTrue("dive detected within the first seconds", start.startEpochMs <= 5_000L)

        val ended = events.filterIsInstance<EngineEvent.DiveEnded>().single()
        // The watch logged 24:15 because the old rule let splashes reset the
        // hold until the process died; the first surface touch was at 23:03.
        assertEquals(1383, ended.durationSec)
        assertEquals(29.52, ended.maxDepthM, 0.01)
        assertEquals(12.55, ended.avgDepthM, 0.2)
        assertEquals(SafetyStopResult.DONE, ended.safetyStopResult)
        assertEquals(15.2, ended.maxAscentRateMPerMin, 0.5)

        // NDL at the deepest point: the watch computed 10.0 min from the same model.
        val deepest = logged.samples.maxBy { it.second }
        val replayedNdlAtDeepest = events.filterIsInstance<EngineEvent.SampleRecorded>()
            .first { it.tOffsetSec == deepest.first }.ndlMin!!
        assertEquals(deepest.third!!, replayedNdlAtDeepest, 1.0)
    }

    @Test
    fun `dive 9 - shallow dive keeps the end pending through the exit and ends at the first touch`() {
        val logged = load("2026-09-25_dive9_8m.csv")
        val (engine, events) = replay(logged)
        assertEquals(1, events.filterIsInstance<EngineEvent.DiveStarted>().size)
        // The file ends 26 s after the wrist surfaced: still diving, end pending.
        assertEquals(DivePhase.DIVING, engine.displayState.phase)
        assertTrue(engine.displayState.endPending)
        assertTrue(events.filterIsInstance<EngineEvent.DiveEnded>().isEmpty())

        val (_, more) = replay(logged, extraSurfaceSec = 40)
        val ended = more.filterIsInstance<EngineEvent.DiveEnded>().single()
        assertEquals(2274, ended.durationSec)
        assertEquals(8.60, ended.maxDepthM, 0.01)
        assertEquals(SafetyStopResult.NOT_REQUIRED, ended.safetyStopResult)
        assertEquals(16.7, ended.maxAscentRateMPerMin, 0.5)
    }
}
