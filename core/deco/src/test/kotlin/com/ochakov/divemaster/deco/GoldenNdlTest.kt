package com.ochakov.divemaster.deco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 pinned NDL grid. Planner-style semantics (see [PlannerSim]):
 * EN 13319 water (the app's shipping default; Subsurface's "EN13319
 * 1.020 kg/L"), surface 1013.25 mbar, descent 18 m/min, ascent 9 m/min,
 * runtime counted from leaving the surface, values >= 360 min treated as
 * unlimited. Baseline generated 2026-09-02 from this implementation
 * (regenerate via NdlTableGenerator -> build/ndl-golden.txt).
 *
 * First external corroboration (Subsurface 6.0.5576, 2026-09-02): at
 * 24 m / air / GF 100 Subsurface's instantaneous "NDL (calc)" on arrival
 * read 28 min vs our analytic 28.3 min bottom.
 *
 * These pins make any change to the deco math loudly visible. External
 * validation uses the same grid — see docs/phase2-validation.md. When
 * external numbers disagree beyond the acceptance rule there, the
 * RESOLUTION must be recorded here alongside the corrected values, never
 * a silent re-pin.
 */
class GoldenNdlTest {

    private data class GoldenRow(
        val depthM: Double,
        val o2Percent: Int,
        val gfPercent: Int,
        val plannerNdlMin: Double,
    )

    private val golden = listOf(
        GoldenRow(12.0, 21, 100, 188.5),
        GoldenRow(15.0, 21, 100, 93.6),
        GoldenRow(18.0, 21, 100, 63.0),
        GoldenRow(21.0, 21, 100, 45.4),
        GoldenRow(24.0, 21, 100, 34.2),
        GoldenRow(27.0, 21, 100, 25.7),
        GoldenRow(30.0, 21, 100, 20.8),
        GoldenRow(33.0, 21, 100, 17.5),
        GoldenRow(36.0, 21, 100, 14.9),
        GoldenRow(40.0, 21, 100, 12.2),
        GoldenRow(12.0, 32, 100, INF),
        GoldenRow(15.0, 32, 100, 223.0),
        GoldenRow(18.0, 32, 100, 112.2),
        GoldenRow(21.0, 32, 100, 73.5),
        GoldenRow(24.0, 32, 100, 54.6),
        GoldenRow(27.0, 32, 100, 42.7),
        GoldenRow(30.0, 32, 100, 33.8),
        GoldenRow(33.0, 32, 100, 26.9),
        GoldenRow(36.0, 32, 100, 22.3),
        GoldenRow(40.0, 32, 100, 18.2),
        GoldenRow(12.0, 36, 100, INF),
        GoldenRow(15.0, 36, 100, INF),
        GoldenRow(18.0, 36, 100, 153.7),
        GoldenRow(21.0, 36, 100, 92.4),
        GoldenRow(24.0, 36, 100, 66.7),
        GoldenRow(27.0, 36, 100, 51.0),
        GoldenRow(30.0, 36, 100, 41.0),
        GoldenRow(33.0, 36, 100, 33.1),
        GoldenRow(36.0, 36, 100, 27.0),
        GoldenRow(40.0, 36, 100, 21.4),
        GoldenRow(12.0, 21, 85, 131.7),
        GoldenRow(15.0, 21, 85, 71.7),
        GoldenRow(18.0, 21, 85, 48.0),
        GoldenRow(21.0, 21, 85, 33.8),
        GoldenRow(24.0, 21, 85, 24.4),
        GoldenRow(27.0, 21, 85, 19.4),
        GoldenRow(30.0, 21, 85, 15.8),
        GoldenRow(33.0, 21, 85, 13.1),
        GoldenRow(36.0, 21, 85, 11.2),
        GoldenRow(40.0, 21, 85, 9.4),
        GoldenRow(12.0, 32, 85, INF),
        GoldenRow(15.0, 32, 85, 146.9),
        GoldenRow(18.0, 32, 85, 82.0),
        GoldenRow(21.0, 32, 85, 56.9),
        GoldenRow(24.0, 32, 85, 42.3),
        GoldenRow(27.0, 32, 85, 31.9),
        GoldenRow(30.0, 32, 85, 24.5),
        GoldenRow(33.0, 32, 85, 20.2),
        GoldenRow(36.0, 32, 85, 17.2),
        GoldenRow(40.0, 32, 85, 14.1),
        GoldenRow(12.0, 36, 85, INF),
        GoldenRow(15.0, 36, 85, 229.3),
        GoldenRow(18.0, 36, 85, 108.4),
        GoldenRow(21.0, 36, 85, 70.9),
        GoldenRow(24.0, 36, 85, 51.4),
        GoldenRow(27.0, 36, 85, 39.4),
        GoldenRow(30.0, 36, 85, 30.5),
        GoldenRow(33.0, 36, 85, 24.2),
        GoldenRow(36.0, 36, 85, 20.2),
        GoldenRow(40.0, 36, 85, 16.6),
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
