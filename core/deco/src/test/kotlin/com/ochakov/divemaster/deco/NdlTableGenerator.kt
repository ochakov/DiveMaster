package com.ochakov.divemaster.deco

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regenerates the Phase 2 golden grid (written to build/ndl-golden.txt): a
 * worksheet block for external cross-checking against Subsurface and Kotlin
 * rows for GoldenNdlTest. Also verifies, on every cell, that the stepped
 * planner NDL and the engine's analytic NDL bracket each other sanely —
 * planner never shorter, ascent credit never implausibly large.
 *
 * Values at or beyond the 360-minute cap are treated as unlimited.
 */
class NdlTableGenerator {

    @Test
    fun `generate golden grid and verify planner vs analytic relationship`() {
        val depths = listOf(12.0, 15.0, 18.0, 21.0, 24.0, 27.0, 30.0, 33.0, 36.0, 40.0)
        val gases = listOf(21, 32, 36)
        val gfs = listOf(100, 85)

        val markdown = StringBuilder()
        markdown.append("| Depth (m) | Gas | GF | Planner NDL (min) | Analytic runtime NDL (min) |\n")
        markdown.append("|---|---|---|---|---|\n")
        val kotlinRows = StringBuilder()

        for (gf in gfs) {
            for (o2 in gases) {
                for (depth in depths) {
                    val params = PlannerSim.Params(gas = Gas.nitrox(o2), gf = gf / 100.0)
                    val planner = cap(PlannerSim.plannerNdlMin(depth, params))
                    val analytic = cap(PlannerSim.instantNdlRuntimeMin(depth, params))

                    if (planner.isFinite() && analytic.isFinite()) {
                        assertTrue(
                            "planner shorter than analytic at ${depth}m/EAN$o2/GF$gf",
                            planner >= analytic - 0.11,
                        )
                        // Near the shallow NDL asymptote a slow compartment approaches
                        // its limit so gradually that a small ascent-off-gassing slack
                        // buys many bottom minutes — the credit bound must scale.
                        assertTrue(
                            "ascent credit implausibly large at ${depth}m/EAN$o2/GF$gf " +
                                "(planner=$planner analytic=$analytic)",
                            planner - analytic <= maxOf(6.0, analytic * 0.25),
                        )
                    } else {
                        assertTrue(
                            "finiteness mismatch at ${depth}m/EAN$o2/GF$gf (planner=$planner analytic=$analytic)",
                            planner.isInfinite() == analytic.isInfinite(),
                        )
                    }

                    markdown.append("| %.0f | %s | %d | %s | %s |\n".format(depth, gasLabel(o2), gf, fmt(planner), fmt(analytic)))
                    kotlinRows.append("        GoldenRow(%.1f, %d, %d, %s),\n".format(depth, o2, gf, ktLiteral(planner)))
                }
            }
        }

        val out = File("build/ndl-golden.txt")
        out.parentFile.mkdirs()
        out.writeText("MARKDOWN\n$markdown\nKOTLIN\n$kotlinRows")
    }

    private fun cap(v: Double): Double = if (v >= 360.0) Double.POSITIVE_INFINITY else v

    private fun fmt(v: Double): String = if (v.isInfinite()) ">360" else "%.1f".format(v)

    private fun ktLiteral(v: Double): String = if (v.isInfinite()) "INF" else "%.1f".format(v)

    private fun gasLabel(o2: Int): String = if (o2 == 21) "Air" else "EAN$o2"
}
