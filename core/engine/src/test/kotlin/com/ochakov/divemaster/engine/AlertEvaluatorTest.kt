package com.ochakov.divemaster.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEvaluatorTest {

    private fun diving(
        rate: Double = 0.0,
        ndlMin: Double = 99.0,
        ppO2: Double = 0.8,
        cns: Double = 0.0,
        stop: SafetyStopState = SafetyStopState.NONE,
        depthM: Double = 15.0,
    ) = DiveDisplayState(
        phase = DivePhase.DIVING,
        depthM = depthM,
        verticalRateMPerMin = rate,
        ndlMin = ndlMin,
        ppO2Bar = ppO2,
        cnsFraction = cns,
        safetyStop = stop,
        safetyStopMinDepthM = 4.0,
        safetyStopMaxDepthM = 6.0,
    )

    private fun surface() = DiveDisplayState(phase = DivePhase.SURFACE)

    @Test
    fun `ascent alert fires with cooldown while sustained and stops when slow`() {
        val e = AlertEvaluator(AlertConfig())
        assertEquals(listOf(DiveAlert.ASCENT_TOO_FAST), e.evaluate(diving(rate = 12.0), 0))
        assertTrue(e.evaluate(diving(rate = 12.0), 1_000).isEmpty())
        assertTrue(e.evaluate(diving(rate = 12.0), 9_000).isEmpty())
        assertEquals(listOf(DiveAlert.ASCENT_TOO_FAST), e.evaluate(diving(rate = 12.0), 10_000))
        assertTrue(e.evaluate(diving(rate = 5.0), 30_000).isEmpty())
    }

    @Test
    fun `descent alert uses its own threshold`() {
        val e = AlertEvaluator(AlertConfig())
        assertTrue(e.evaluate(diving(rate = -15.0), 0).isEmpty())
        assertEquals(listOf(DiveAlert.DESCENT_TOO_FAST), e.evaluate(diving(rate = -25.0), 1_000))
    }

    @Test
    fun `rate alerts respect the enable flag`() {
        val e = AlertEvaluator(AlertConfig(rateAlertsEnabled = false))
        assertTrue(e.evaluate(diving(rate = 20.0), 0).isEmpty())
        assertTrue(e.evaluate(diving(rate = -30.0), 1_000).isEmpty())
    }

    @Test
    fun `low NDL fires once and re-arms after recovery`() {
        val e = AlertEvaluator(AlertConfig())
        assertTrue(e.evaluate(diving(ndlMin = 6.0), 0).isEmpty())
        assertEquals(listOf(DiveAlert.LOW_NDL), e.evaluate(diving(ndlMin = 5.0), 1_000))
        assertTrue(e.evaluate(diving(ndlMin = 4.0), 2_000).isEmpty())
        assertTrue(e.evaluate(diving(ndlMin = 6.5), 3_000).isEmpty()) // not yet re-armed
        assertTrue(e.evaluate(diving(ndlMin = 8.0), 4_000).isEmpty()) // re-arms here
        assertEquals(listOf(DiveAlert.LOW_NDL), e.evaluate(diving(ndlMin = 4.5), 5_000))
    }

    @Test
    fun `entering deco fires its own alert`() {
        val e = AlertEvaluator(AlertConfig())
        assertEquals(listOf(DiveAlert.LOW_NDL), e.evaluate(diving(ndlMin = 3.0), 0))
        val atZero = e.evaluate(diving(ndlMin = 0.0), 1_000)
        assertEquals(listOf(DiveAlert.DECO_ENTERED), atZero)
        assertTrue(e.evaluate(diving(ndlMin = 0.0), 2_000).isEmpty())
    }

    @Test
    fun `ndl alerts respect the enable flag`() {
        val e = AlertEvaluator(AlertConfig(ndlAlertEnabled = false))
        assertTrue(e.evaluate(diving(ndlMin = 2.0), 0).isEmpty())
        assertTrue(e.evaluate(diving(ndlMin = 0.0), 1_000).isEmpty())
    }

    @Test
    fun `high ppO2 repeats on its cooldown`() {
        val e = AlertEvaluator(AlertConfig())
        assertEquals(listOf(DiveAlert.PPO2_HIGH), e.evaluate(diving(ppO2 = 1.5), 0))
        assertTrue(e.evaluate(diving(ppO2 = 1.5), 5_000).isEmpty())
        assertEquals(listOf(DiveAlert.PPO2_HIGH), e.evaluate(diving(ppO2 = 1.5), 15_000))
        assertTrue(e.evaluate(diving(ppO2 = 1.2), 40_000).isEmpty())
    }

    @Test
    fun `CNS warns at 80 percent and again at 100 percent`() {
        val e = AlertEvaluator(AlertConfig())
        assertTrue(e.evaluate(diving(cns = 0.5), 0).isEmpty())
        assertEquals(listOf(DiveAlert.CNS_HIGH), e.evaluate(diving(cns = 0.82), 1_000))
        assertTrue(e.evaluate(diving(cns = 0.9), 2_000).isEmpty())
        assertEquals(listOf(DiveAlert.CNS_HIGH), e.evaluate(diving(cns = 1.01), 3_000))
        assertTrue(e.evaluate(diving(cns = 1.05), 4_000).isEmpty())
    }

    @Test
    fun `stop complete fires exactly once per dive`() {
        val e = AlertEvaluator(AlertConfig())
        assertTrue(e.evaluate(diving(stop = SafetyStopState.ACTIVE, depthM = 5.0), 0).isEmpty())
        assertEquals(
            listOf(DiveAlert.SAFETY_STOP_COMPLETE),
            e.evaluate(diving(stop = SafetyStopState.DONE, depthM = 5.0), 1_000),
        )
        assertTrue(e.evaluate(diving(stop = SafetyStopState.DONE, depthM = 4.0), 2_000).isEmpty())
    }

    @Test
    fun `ascending past an unfinished stop warns and nags on cooldown`() {
        val e = AlertEvaluator(AlertConfig())
        // Paused on the deep side: no violation.
        assertTrue(e.evaluate(diving(stop = SafetyStopState.PAUSED, depthM = 8.0), 0).isEmpty())
        // Paused shallow of the window: violation.
        assertEquals(
            listOf(DiveAlert.SAFETY_STOP_VIOLATED),
            e.evaluate(diving(stop = SafetyStopState.PAUSED, depthM = 2.0), 1_000),
        )
        assertTrue(e.evaluate(diving(stop = SafetyStopState.PAUSED, depthM = 2.0), 5_000).isEmpty())
        assertEquals(
            listOf(DiveAlert.SAFETY_STOP_VIOLATED),
            e.evaluate(diving(stop = SafetyStopState.PAUSED, depthM = 1.5), 16_500),
        )
    }

    @Test
    fun `nothing fires on the surface and per-dive state resets between dives`() {
        val e = AlertEvaluator(AlertConfig())
        assertEquals(listOf(DiveAlert.LOW_NDL), e.evaluate(diving(ndlMin = 4.0), 0))
        assertTrue(e.evaluate(surface(), 1_000).isEmpty())
        assertTrue(e.evaluate(surface(), 2_000).isEmpty())
        // Next dive: low-NDL is armed again.
        assertEquals(listOf(DiveAlert.LOW_NDL), e.evaluate(diving(ndlMin = 4.0), 3_000))
    }
}
