package com.ochakov.divemaster.deco

/**
 * Test-only planner-style NDL computation used to pin golden values (Phase 2).
 *
 * Semantics deliberately match a dive planner's no-stop boundary: runtime
 * counts from leaving the surface, descent at a fixed rate loads tissues on
 * the way down, and a candidate runtime is "clean" if a direct ascent at the
 * configured rate — tissues continuing to evolve on the way up — never
 * requires a stop and surfaces within the gradient factor.
 *
 * This is a stepped simulation that shares only the model primitives with
 * the engine's analytic NDL solver, so the two act as cross-checks: the
 * stepped planner result must always be at least as long as the analytic
 * instantaneous-surfacing result (ascent off-gassing credit), and never
 * implausibly longer.
 */
object PlannerSim {
    data class Params(
        val gas: Gas,
        val gf: Double,
        // EN13319: the app's shipping default, and selectable in Subsurface
        // as "EN13319 (1.020 kg/L)" — the validation grid uses it on both sides.
        val water: WaterType = WaterType.EN13319,
        val surfaceBar: Double = DepthConverter.STANDARD_ATMOSPHERE_BAR,
        val descentMPerMin: Double = 18.0,
        val ascentMPerMin: Double = 9.0,
        val maxRuntimeMin: Int = 360,
    )

    /** Longest no-stop runtime (minutes from leaving the surface, descent included). */
    fun plannerNdlMin(depthM: Double, p: Params): Double {
        val converter = DepthConverter(p.water)
        val descentSec = depthM / p.descentMPerMin * 60.0
        var tissue = TissueState.saturatedAir(p.surfaceBar)
        var lastCleanSec = 0
        var t = 0
        val maxSec = p.maxRuntimeMin * 60
        while (t < maxSec) {
            val midSec = t + 0.5
            val depth = if (midSec < descentSec) depthM * (midSec / descentSec) else depthM
            tissue = loadOneSecond(tissue, depth, p, converter)
            t++
            if (t >= descentSec && t % 6 == 0) {
                if (ascentIsClean(tissue, depthM, p, converter)) {
                    lastCleanSec = t
                } else {
                    return lastCleanSec / 60.0
                }
            }
        }
        return Double.POSITIVE_INFINITY
    }

    /** Runtime NDL using the engine's analytic solver after the same simulated descent. */
    fun instantNdlRuntimeMin(depthM: Double, p: Params): Double {
        val converter = DepthConverter(p.water)
        val descentSec = depthM / p.descentMPerMin * 60.0
        var tissue = TissueState.saturatedAir(p.surfaceBar)
        var t = 0
        while (t < descentSec) {
            val midSec = t + 0.5
            val depth = if (midSec < descentSec) depthM * (midSec / descentSec) else depthM
            tissue = loadOneSecond(tissue, depth, p, converter)
            t++
        }
        val ndlSec = Buhlmann.ndlSeconds(
            tissue,
            converter.ambientBar(depthM, p.surfaceBar),
            p.surfaceBar,
            p.gas,
            p.gf,
        )
        return if (ndlSec.isInfinite()) Double.POSITIVE_INFINITY else (t + ndlSec) / 60.0
    }

    private fun loadOneSecond(
        tissue: TissueState,
        depthM: Double,
        p: Params,
        converter: DepthConverter,
    ): TissueState {
        val gas = if (depthM > 0.5) p.gas else Gas.AIR
        return Buhlmann.loadConstant(tissue, converter.ambientBar(depthM, p.surfaceBar), gas, 1.0)
    }

    private fun ascentIsClean(
        start: TissueState,
        fromDepthM: Double,
        p: Params,
        converter: DepthConverter,
    ): Boolean {
        var tissue = start
        var depth = fromDepthM
        val metersPerSec = p.ascentMPerMin / 60.0
        while (depth > 0.0) {
            val next = (depth - metersPerSec).coerceAtLeast(0.0)
            tissue = loadOneSecond(tissue, (depth + next) / 2.0, p, converter)
            depth = next
            if (Buhlmann.toleratedAmbientBar(tissue, p.gf) > converter.ambientBar(depth, p.surfaceBar) + 1e-9) {
                return false
            }
        }
        return Buhlmann.toleratedAmbientBar(tissue, p.gf) <= p.surfaceBar + 1e-9
    }
}
