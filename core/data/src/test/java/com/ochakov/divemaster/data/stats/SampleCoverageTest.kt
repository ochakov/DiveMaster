package com.ochakov.divemaster.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleCoverageTest {

    @Test
    fun `a complete 1 Hz dive has duration plus one samples and no gaps`() {
        val c = SampleCoverage.of((0..1455).toList(), 1455)
        assertTrue(c.complete)
        assertEquals(1456, c.spanSec)
        assertEquals(0, c.gapCount)
        assertEquals("100%", c.percentLabel())
        assertNull(c.longestGapLabel())
    }

    @Test
    fun `Ev's dive 10 of 2026-10-16 - 30 samples in 2078 s`() {
        val offsets = (0..7) + (18..25) + (763..775) + listOf(2078)
        val c = SampleCoverage.of(offsets, 2078)
        assertEquals(2079, c.spanSec)
        assertEquals(30, c.presentSec)
        assertEquals(3, c.gapCount)
        assertEquals(1302, c.longestGapSec) // 776..2077 missing
        assertEquals(776, c.longestGapStartSec)
        assertEquals("1%", c.percentLabel())
        assertEquals("21:42", c.longestGapLabel())
        assertEquals("12:56", c.longestGapStartLabel())
        assertFalse(c.complete)
    }

    @Test
    fun `a missing head and tail count as gaps`() {
        val c = SampleCoverage.of(listOf(5, 6, 7), 10)
        assertEquals(11, c.spanSec)
        assertEquals(3, c.presentSec)
        assertEquals(2, c.gapCount)
        assertEquals(5, c.longestGapSec) // 0..4
        assertEquals(0, c.longestGapStartSec)
    }

    @Test
    fun `offsets beyond the duration widen the span instead of overflowing`() {
        val c = SampleCoverage.of(listOf(0, 1, 2, 9), 2)
        assertEquals(10, c.spanSec)
        assertEquals(4, c.presentSec)
        assertEquals(1, c.gapCount)
        assertEquals(6, c.longestGapSec)
    }

    @Test
    fun `duplicates and negatives are ignored`() {
        val c = SampleCoverage.of(listOf(-1, 0, 0, 1, 1, 2), 2)
        assertTrue(c.complete)
        assertEquals(3, c.presentSec)
    }

    @Test
    fun `nearly complete never rounds up to 100 percent and a trace never rounds down to 0`() {
        val nearly = SampleCoverage.of((0..998).toList(), 999) // one missing second of 1000
        assertEquals("99%", nearly.percentLabel())
        val trace = SampleCoverage.of(listOf(0), 999)
        assertEquals("<1%", trace.percentLabel())
    }

    @Test
    fun `no samples at all is an entirely missing profile`() {
        val c = SampleCoverage.of(emptyList(), 59)
        assertEquals(60, c.spanSec)
        assertEquals(0, c.presentSec)
        assertEquals(1, c.gapCount)
        assertEquals(60, c.longestGapSec)
        assertEquals("<1%", c.percentLabel())
    }
}
