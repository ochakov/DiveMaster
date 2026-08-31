package com.ochakov.divemaster.deco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NDL golden tests. The ballpark windows come from published raw ZH-L16C
 * air NDLs for a rested diver at sea level (no descent time, no safety
 * margin); Phase 2 will pin exact values against a chosen reference run.
 */
class NdlTest {

    private val surface = DepthConverter.STANDARD_ATMOSPHERE_BAR
    private val water = DepthConverter(WaterType.EN13319)

    private fun ndlMinutes(depthM: Double, gas: Gas, gfHigh: Double): Double =
        Buhlmann.ndlSeconds(
            TissueState.saturatedAir(surface),
            water.ambientBar(depthM, surface),
            surface,
            gas,
            gfHigh,
        ) / 60.0

    @Test
    fun `air NDLs from a rested diver are in the published ballpark`() {
        assertEquals(60.0, ndlMinutes(18.0, Gas.AIR, 1.0), 15.0)
        assertEquals(16.5, ndlMinutes(30.0, Gas.AIR, 1.0), 5.0)
        assertEquals(9.0, ndlMinutes(40.0, Gas.AIR, 1.0), 3.0)
    }

    @Test
    fun `NDL shrinks monotonically with depth`() {
        val depths = listOf(18.0, 22.0, 26.0, 30.0, 35.0, 40.0)
        val ndls = depths.map { ndlMinutes(it, Gas.AIR, 1.0) }
        ndls.zipWithNext().forEach { (shallow, deep) ->
            assertTrue("deeper must mean shorter NDL", deep < shallow)
        }
    }

    @Test
    fun `nitrox extends NDL versus air at the same depth`() {
        val air = ndlMinutes(30.0, Gas.AIR, 1.0)
        val ean32 = ndlMinutes(30.0, Gas.nitrox(32), 1.0)
        assertTrue("EAN32 must beat air", ean32 > air)
        assertEquals(27.5, ean32, 6.0)
    }

    @Test
    fun `gradient factor high below 100 percent shortens NDL`() {
        val raw = ndlMinutes(30.0, Gas.AIR, 1.0)
        val gf85 = ndlMinutes(30.0, Gas.AIR, 0.85)
        assertTrue(gf85 < raw)
        assertEquals(12.4, gf85, 4.0)
    }

    @Test
    fun `very shallow water never runs out of NDL`() {
        assertTrue(ndlMinutes(4.0, Gas.AIR, 1.0).isInfinite())
    }

    @Test
    fun `NDL reaches zero once the diver is into deco`() {
        val surfaceState = TissueState.saturatedAir(surface)
        val ambient40 = water.ambientBar(40.0, surface)
        val overstayed = Buhlmann.loadConstant(surfaceState, ambient40, Gas.AIR, 30.0 * 60.0)
        assertEquals(0.0, Buhlmann.ndlSeconds(overstayed, ambient40, surface, Gas.AIR, 1.0), 0.0)
    }

    @Test
    fun `staying exactly NDL is safe and overstaying is not - self-consistency`() {
        for (gfHigh in listOf(1.0, 0.85)) {
            for (depth in listOf(25.0, 30.0, 40.0)) {
                val start = TissueState.saturatedAir(surface)
                val ambient = water.ambientBar(depth, surface)
                val ndlSec = Buhlmann.ndlSeconds(start, ambient, surface, Gas.AIR, gfHigh)
                assertTrue(ndlSec.isFinite() && ndlSec > 0)

                val atLimit = Buhlmann.loadConstant(start, ambient, Gas.AIR, ndlSec)
                for (i in 0 until ZhL16c.SIZE) {
                    val allowed = Buhlmann.allowedSurfaceTensionBar(ZhL16c.COMPARTMENTS[i], surface, gfHigh)
                    assertTrue("within limit at NDL (cpt $i)", atLimit[i] <= allowed + 1e-7)
                }

                val overstayed = Buhlmann.loadConstant(start, ambient, Gas.AIR, ndlSec * 1.02 + 60.0)
                val anyExceeds = (0 until ZhL16c.SIZE).any { i ->
                    val allowed = Buhlmann.allowedSurfaceTensionBar(ZhL16c.COMPARTMENTS[i], surface, gfHigh)
                    overstayed[i] > allowed
                }
                assertTrue("overstaying must break a limit", anyExceeds)
            }
        }
    }

    @Test
    fun `fresh tissues tolerate surface pressure - no phantom ceiling`() {
        val state = TissueState.saturatedAir(surface)
        assertTrue(Buhlmann.toleratedAmbientBar(state, 0.85) <= surface)
    }
}
