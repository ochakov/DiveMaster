package com.ochakov.divemaster.deco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TissueLoadingTest {

    private val surface = DepthConverter.STANDARD_ATMOSPHERE_BAR
    private val water = DepthConverter(WaterType.EN13319)

    @Test
    fun `saturated diver carries alveolar air nitrogen in every compartment`() {
        val state = TissueState.saturatedAir(surface)
        val expected = (surface - ZhL16c.WATER_VAPOR_BAR) * Gas.AIR.n2Fraction
        for (i in 0 until ZhL16c.SIZE) assertEquals(expected, state[i], 1e-12)
    }

    @Test
    fun `after exactly one half-time the gap to the target halves`() {
        val state = TissueState.saturatedAir(surface)
        val ambient = water.ambientBar(30.0, surface)
        val pAlv = Buhlmann.alveolarInertBar(ambient, Gas.AIR)
        for (i in 0 until ZhL16c.SIZE) {
            val halfTimeSec = ZhL16c.COMPARTMENTS[i].halfTimeMin * 60.0
            val loaded = Buhlmann.loadConstant(state, ambient, Gas.AIR, halfTimeSec)
            val expected = pAlv + (state[i] - pAlv) * 0.5
            assertEquals("compartment $i", expected, loaded[i], 1e-9)
        }
    }

    @Test
    fun `long exposure saturates to the alveolar pressure without overshoot`() {
        val state = TissueState.saturatedAir(surface)
        val ambient = water.ambientBar(30.0, surface)
        val pAlv = Buhlmann.alveolarInertBar(ambient, Gas.AIR)
        // 500 h is ~47 half-times of the slowest (635 min) compartment.
        val loaded = Buhlmann.loadConstant(state, ambient, Gas.AIR, 500.0 * 3600.0)
        for (i in 0 until ZhL16c.SIZE) {
            assertEquals(pAlv, loaded[i], 1e-6)
            assertTrue("no overshoot", loaded[i] <= pAlv + 1e-9)
        }
    }

    @Test
    fun `loading is split-invariant - two half steps equal one full step`() {
        val state = TissueState.saturatedAir(surface)
        val ambient = water.ambientBar(24.0, surface)
        val oneStep = Buhlmann.loadConstant(state, ambient, Gas.AIR, 1200.0)
        val twoSteps = Buhlmann.loadConstant(
            Buhlmann.loadConstant(state, ambient, Gas.AIR, 600.0),
            ambient, Gas.AIR, 600.0,
        )
        for (i in 0 until ZhL16c.SIZE) assertEquals(oneStep[i], twoSteps[i], 1e-12)
    }

    @Test
    fun `zero duration leaves tissues unchanged`() {
        val state = TissueState.saturatedAir(surface)
        val loaded = Buhlmann.loadConstant(state, water.ambientBar(40.0, surface), Gas.AIR, 0.0)
        for (i in 0 until ZhL16c.SIZE) assertEquals(state[i], loaded[i], 0.0)
    }

    @Test
    fun `72 hours on the surface returns a dived state to saturation`() {
        val state = TissueState.saturatedAir(surface)
        val dived = Buhlmann.loadConstant(state, water.ambientBar(30.0, surface), Gas.AIR, 30.0 * 60.0)
        val rested = Buhlmann.surfaceInterval(dived, surface, 72.0 * 3600.0)
        val saturated = TissueState.saturatedAir(surface)
        for (i in 0 until ZhL16c.SIZE) assertEquals(saturated[i], rested[i], 1e-3)
    }
}
