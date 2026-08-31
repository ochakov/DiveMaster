package com.ochakov.divemaster.deco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.sqrt

class ZhL16cTableTest {

    @Test
    fun `sixteen compartments with strictly increasing half-times`() {
        assertEquals(16, ZhL16c.COMPARTMENTS.size)
        ZhL16c.COMPARTMENTS.zipWithNext().forEach { (a, b) ->
            assertTrue("half-times must increase", a.halfTimeMin < b.halfTimeMin)
        }
        assertEquals(5.0, ZhL16c.COMPARTMENTS.first().halfTimeMin, 0.0)
        assertEquals(635.0, ZhL16c.COMPARTMENTS.last().halfTimeMin, 0.0)
    }

    @Test
    fun `b coefficients match Buhlmann formula b = 1_005 minus halfTime^-0_5`() {
        ZhL16c.COMPARTMENTS.forEach { c ->
            val expected = 1.005 - 1.0 / sqrt(c.halfTimeMin)
            assertEquals("b for t1/2=${c.halfTimeMin}", expected, c.b, 1e-4)
        }
    }

    @Test
    fun `a coefficients sit within the ZH-L16A envelope`() {
        // The C variant lowers a (more conservative) relative to A's formula
        // a = 2 * t^-1/3; published rounding allows a hair of slack upward.
        ZhL16c.COMPARTMENTS.forEach { c ->
            val aFormula = 2.0 * c.halfTimeMin.pow(-1.0 / 3.0)
            assertTrue("a too large for t1/2=${c.halfTimeMin}", c.a <= aFormula + 0.001)
            assertTrue("a implausibly small for t1/2=${c.halfTimeMin}", c.a >= aFormula * 0.5)
        }
    }

    @Test
    fun `spot-check published ZH-L16C values`() {
        assertEquals(1.1696, ZhL16c.COMPARTMENTS[0].a, 0.0)
        assertEquals(0.5578, ZhL16c.COMPARTMENTS[0].b, 0.0)
        assertEquals(1.0000, ZhL16c.COMPARTMENTS[1].a, 0.0)
        assertEquals(0.4000, ZhL16c.COMPARTMENTS[7].a, 0.0)
        assertEquals(0.8910, ZhL16c.COMPARTMENTS[7].b, 0.0)
        assertEquals(0.2327, ZhL16c.COMPARTMENTS[15].a, 0.0)
        assertEquals(0.9653, ZhL16c.COMPARTMENTS[15].b, 0.0)
    }
}
