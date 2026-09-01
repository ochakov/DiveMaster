package com.ochakov.divemaster.deco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 pinned NDL grid. Planner-style semantics (see [PlannerSim]):
 * fresh water, surface 1013.25 mbar, descent 18 m/min, ascent 9 m/min,
 * runtime counted from leaving the surface, values >= 360 min treated as
 * unlimited. Baseline generated 2026-09-01 from this implementation
 * (regenerate via NdlTableGenerator -> build/ndl-golden.txt).
 *
 * These pins make any change to the deco math loudly visible. External
 * validation against Subsurface uses the same grid — see
 * docs/phase2-validation.md. When external numbers disagree beyond the
 * acceptance rule there, the RESOLUTION must be recorded here alongside
 * the corrected values, never a silent re-pin.
 */
class GoldenNdlTest {

    private data class GoldenRow(
        val depthM: Double,
        val o2Percent: Int,
        val gfPercent: Int,
        val plannerNdlMin: Double,
    )

    private val golden = listOf(
        GoldenRow(12.0, 21, 100, 206.0),
        GoldenRow(15.0, 21, 100, 98.8),
        GoldenRow(18.0, 21, 100, 65.4),
        GoldenRow(21.0, 21, 100, 47.2),
        GoldenRow(24.0, 21, 100, 35.7),
        GoldenRow(27.0, 21, 100, 27.0),
        GoldenRow(30.0, 21, 100, 21.6),
        GoldenRow(33.0, 21, 100, 18.2),
        GoldenRow(36.0, 21, 100, 15.7),
        GoldenRow(40.0, 21, 100, 12.8),
        GoldenRow(12.0, 32, 100, INF),
        GoldenRow(15.0, 32, 100, 240.1),
        GoldenRow(18.0, 32, 100, 120.9),
        GoldenRow(21.0, 32, 100, 76.9),
        GoldenRow(24.0, 32, 100, 57.3),
        GoldenRow(27.0, 32, 100, 44.4),
        GoldenRow(30.0, 32, 100, 35.3),
        GoldenRow(33.0, 32, 100, 28.4),
        GoldenRow(36.0, 32, 100, 23.3),
        GoldenRow(40.0, 32, 100, 18.9),
        GoldenRow(12.0, 36, 100, INF),
        GoldenRow(15.0, 36, 100, INF),
        GoldenRow(18.0, 36, 100, 165.4),
        GoldenRow(21.0, 36, 100, 98.0),
        GoldenRow(24.0, 36, 100, 69.6),
        GoldenRow(27.0, 36, 100, 53.4),
        GoldenRow(30.0, 36, 100, 42.7),
        GoldenRow(33.0, 36, 100, 34.6),
        GoldenRow(36.0, 36, 100, 28.5),
        GoldenRow(40.0, 36, 100, 22.4),
        GoldenRow(12.0, 21, 85, 139.2),
        GoldenRow(15.0, 21, 85, 74.7),
        GoldenRow(18.0, 21, 85, 50.0),
        GoldenRow(21.0, 21, 85, 35.7),
        GoldenRow(24.0, 21, 85, 25.6),
        GoldenRow(27.0, 21, 85, 20.2),
        GoldenRow(30.0, 21, 85, 16.5),
        GoldenRow(33.0, 21, 85, 13.6),
        GoldenRow(36.0, 21, 85, 11.6),
        GoldenRow(40.0, 21, 85, 9.7),
        GoldenRow(12.0, 32, 85, INF),
        GoldenRow(15.0, 32, 85, 157.2),
        GoldenRow(18.0, 32, 85, 86.3),
        GoldenRow(21.0, 32, 85, 59.8),
        GoldenRow(24.0, 32, 85, 44.4),
        GoldenRow(27.0, 32, 85, 33.8),
        GoldenRow(30.0, 32, 85, 25.7),
        GoldenRow(33.0, 32, 85, 21.0),
        GoldenRow(36.0, 32, 85, 17.8),
        GoldenRow(40.0, 32, 85, 14.7),
        GoldenRow(12.0, 36, 85, INF),
        GoldenRow(15.0, 36, 85, 248.7),
        GoldenRow(18.0, 36, 85, 116.7),
        GoldenRow(21.0, 36, 85, 74.2),
        GoldenRow(24.0, 36, 85, 53.8),
        GoldenRow(27.0, 36, 85, 41.5),
        GoldenRow(30.0, 36, 85, 32.5),
        GoldenRow(33.0, 36, 85, 25.4),
        GoldenRow(36.0, 36, 85, 21.1),
        GoldenRow(40.0, 36, 85, 17.3),
    )

    @Test
    fun `planner NDL grid matches the pinned baseline`() {
        for (row in golden) {
            val params = PlannerSim.Params(gas = Gas.nitrox(row.o2Percent), gf = row.gfPercent / 100.0)
            var value = PlannerSim.plannerNdlMin(row.depthM, params)
            if (value >= 360.0) value = INF
            val label = "${row.depthM}m EAN${row.o2Percent} GF${row.gfPercent}"
            if (row.plannerNdlMin.isInfinite()) {
                assertTrue("$label should be unlimited", value.isInfinite())
            } else {
                assertEquals(label, row.plannerNdlMin, value, 0.15)
            }
        }
    }

    private companion object {
        val INF = Double.POSITIVE_INFINITY
    }
}
