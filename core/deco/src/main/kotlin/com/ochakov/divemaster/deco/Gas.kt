package com.ochakov.divemaster.deco

/**
 * A single breathing gas: air or nitrox. Helium is out of scope for v1
 * (single-gas recreational diving), so the inert fraction is nitrogen only.
 */
data class Gas(val o2Fraction: Double) {
    init {
        require(o2Fraction in 0.21..1.0) { "O2 fraction must be within 21%..100%" }
    }

    val n2Fraction: Double get() = 1.0 - o2Fraction

    val isAir: Boolean get() = o2Fraction == AIR_O2_FRACTION

    companion object {
        const val AIR_O2_FRACTION = 0.21
        val AIR = Gas(AIR_O2_FRACTION)
        fun nitrox(o2Percent: Int): Gas = Gas(o2Percent / 100.0)
    }
}
