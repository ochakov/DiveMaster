package com.ochakov.divemaster.deco

import kotlin.math.ln
import kotlin.math.pow

/** Immutable nitrogen tensions (bar) for the 16 ZH-L16C compartments. */
class TissueState(loads: DoubleArray) {
    val n2Bar: DoubleArray = loads.copyOf()

    init {
        require(n2Bar.size == ZhL16c.SIZE) { "Expected ${ZhL16c.SIZE} compartments, got ${n2Bar.size}" }
    }

    operator fun get(i: Int): Double = n2Bar[i]

    companion object {
        /** A diver fully equilibrated to air at the given surface pressure. */
        fun saturatedAir(surfaceBar: Double): TissueState {
            val p = Buhlmann.alveolarInertBar(surfaceBar, Gas.AIR)
            return TissueState(DoubleArray(ZhL16c.SIZE) { p })
        }
    }
}

/**
 * Pure ZH-L16C math. Everything is stateless and side-effect free; the dive
 * engine owns the [TissueState] and time-steps it with these functions.
 * Pressures are bar absolute, durations are seconds.
 */
object Buhlmann {
    private const val LN2 = 0.6931471805599453

    /** Inert-gas partial pressure in the alveoli at [ambientBar] breathing [gas]. */
    fun alveolarInertBar(ambientBar: Double, gas: Gas): Double =
        ((ambientBar - ZhL16c.WATER_VAPOR_BAR) * gas.n2Fraction).coerceAtLeast(0.0)

    /**
     * Haldane exponential on/off-gassing at a constant ambient pressure.
     * Exact for constant depth; the dive engine calls this once per sample
     * interval, which makes the piecewise-constant approximation as good as
     * the sensor data itself.
     */
    fun loadConstant(state: TissueState, ambientBar: Double, gas: Gas, durationSec: Double): TissueState {
        require(durationSec >= 0.0) { "Duration must not be negative" }
        val pAlv = alveolarInertBar(ambientBar, gas)
        val minutes = durationSec / 60.0
        val next = DoubleArray(ZhL16c.SIZE)
        for (i in 0 until ZhL16c.SIZE) {
            val fraction = 1.0 - 2.0.pow(-minutes / ZhL16c.COMPARTMENTS[i].halfTimeMin)
            next[i] = state[i] + (pAlv - state[i]) * fraction
        }
        return TissueState(next)
    }

    /**
     * Off-gassing on the surface breathing air. Also correct for time the app
     * spent closed: one call with the elapsed duration is exact.
     */
    fun surfaceInterval(state: TissueState, surfaceBar: Double, durationSec: Double): TissueState =
        loadConstant(state, surfaceBar, Gas.AIR, durationSec)

    /** Highest tissue tension one compartment may carry at the surface under [gf]. */
    fun allowedSurfaceTensionBar(c: ZhL16c.Compartment, surfaceBar: Double, gf: Double): Double {
        val m0 = surfaceBar / c.b + c.a
        return surfaceBar + gf * (m0 - surfaceBar)
    }

    /**
     * No-decompression limit: how many more seconds the diver may remain at
     * [ambientBar] before a direct ascent would exceed GF-high at the surface.
     * Returns 0.0 when already outside the NDL and POSITIVE_INFINITY when no
     * compartment can ever reach its limit at this depth.
     */
    fun ndlSeconds(
        state: TissueState,
        ambientBar: Double,
        surfaceBar: Double,
        gas: Gas,
        gfHigh: Double,
    ): Double {
        val pAlv = alveolarInertBar(ambientBar, gas)
        var ndl = Double.POSITIVE_INFINITY
        for (i in 0 until ZhL16c.SIZE) {
            val c = ZhL16c.COMPARTMENTS[i]
            val allowed = allowedSurfaceTensionBar(c, surfaceBar, gfHigh)
            if (state[i] >= allowed) return 0.0
            if (pAlv <= allowed) continue // can never exceed the limit at this depth
            val k = LN2 / (c.halfTimeMin * 60.0)
            val t = ln((pAlv - state[i]) / (pAlv - allowed)) / k
            if (t < ndl) ndl = t
        }
        return ndl
    }

    /**
     * Lowest tolerated absolute ambient pressure for the current loading under
     * [gf] — the "ceiling" in bar. At or below surface pressure means a direct
     * ascent is allowed. Foundation for staged deco later.
     */
    fun toleratedAmbientBar(state: TissueState, gf: Double): Double {
        var worst = 0.0
        for (i in 0 until ZhL16c.SIZE) {
            val c = ZhL16c.COMPARTMENTS[i]
            val tolerated = (state[i] - c.a * gf) / (gf / c.b + 1.0 - gf)
            if (tolerated > worst) worst = tolerated
        }
        return worst
    }
}
