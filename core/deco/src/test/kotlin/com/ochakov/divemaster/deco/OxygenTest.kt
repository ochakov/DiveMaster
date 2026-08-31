package com.ochakov.divemaster.deco

import org.junit.Assert.assertEquals
import org.junit.Test

class OxygenTest {

    private val surface = DepthConverter.STANDARD_ATMOSPHERE_BAR
    private val salt = DepthConverter(WaterType.SALT)

    @Test
    fun `ppO2 at depth`() {
        val en = DepthConverter(WaterType.EN13319)
        val ambient30 = en.ambientBar(30.0, surface)
        assertEquals(0.843, Oxygen.ppO2Bar(ambient30, Gas.AIR), 0.01)
    }

    @Test
    fun `MOD for EAN32 at 1_4 bar in salt water is about 33 m`() {
        assertEquals(33.4, Oxygen.modMeters(Gas.nitrox(32), 1.4, surface, salt), 0.5)
    }

    @Test
    fun `MOD for air at 1_4 bar in salt water is about 56 m`() {
        assertEquals(56.2, Oxygen.modMeters(Gas.AIR, 1.4, surface, salt), 0.6)
    }

    @Test
    fun `CNS rate follows the NOAA table at exact entries`() {
        assertEquals(1.0 / 150.0, Oxygen.cnsRatePerMin(1.4), 1e-9)
        assertEquals(1.0 / 45.0, Oxygen.cnsRatePerMin(1.6), 1e-9)
        assertEquals(1.0 / 720.0, Oxygen.cnsRatePerMin(0.55), 1e-9)
        assertEquals(0.0, Oxygen.cnsRatePerMin(0.4), 0.0)
    }

    @Test
    fun `CNS rate interpolates between table entries`() {
        // Between 1.4 (150 min) and 1.5 (120 min) the limit at 1.45 is 135 min.
        assertEquals(1.0 / 135.0, Oxygen.cnsRatePerMin(1.45), 1e-9)
    }

    @Test
    fun `75 minutes at ppO2 1_4 consumes half the CNS clock`() {
        val cns = Oxygen.addExposure(0.0, 1.4, 75.0 * 60.0)
        assertEquals(0.5, cns, 1e-9)
    }

    @Test
    fun `CNS halves after 90 minutes on the surface`() {
        assertEquals(0.5, Oxygen.surfaceDecay(1.0, 90.0 * 60.0), 1e-9)
    }
}
