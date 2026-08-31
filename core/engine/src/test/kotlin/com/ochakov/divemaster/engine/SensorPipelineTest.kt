package com.ochakov.divemaster.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorPipelineTest {

    @Test
    fun `downsamples a high-rate stream to one sample per second`() {
        val pipeline = SensorPipeline()
        val out = mutableListOf<PressureSample>()
        for (ms in 0 until 3000 step 100) {
            pipeline.onRaw(ms.toLong(), 1.0)?.let { out += it }
        }
        assertEquals(2, out.size)
        assertEquals(listOf(1000L, 2000L), out.map { it.timestampMs })
    }

    @Test
    fun `median suppresses a single spike`() {
        val pipeline = SensorPipeline()
        val out = mutableListOf<PressureSample>()
        for (ms in 0 until 3000 step 100) {
            val bar = if (ms == 1300) 2.5 else 1.0
            pipeline.onRaw(ms.toLong(), bar)?.let { out += it }
        }
        assertTrue(out.isNotEmpty())
        out.forEach { assertEquals(1.0, it.pressureBar, 1e-9) }
    }

    @Test
    fun `a stalled sensor still closes the old bucket on resume`() {
        val pipeline = SensorPipeline()
        pipeline.onRaw(100, 1.0)
        pipeline.onRaw(200, 1.0)
        val resumed = pipeline.onRaw(5300, 1.2)
        assertEquals(1000L, resumed!!.timestampMs)
        assertEquals(1.0, resumed.pressureBar, 1e-9)
    }
}
