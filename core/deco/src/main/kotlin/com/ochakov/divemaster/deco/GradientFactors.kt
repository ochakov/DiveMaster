package com.ochakov.divemaster.deco

/**
 * Erik Baker style gradient factors expressed as fractions (0.85 = 85%).
 * [high] governs the surfacing M-value and therefore the NDL; [low] will
 * govern the first stop depth once staged decompression is implemented.
 */
data class GradientFactors(val low: Double, val high: Double) {
    init {
        require(low in 0.1..1.0 && high in 0.1..1.0) { "Gradient factors must be within 10%..100%" }
        require(low <= high) { "GF low must not exceed GF high" }
    }

    companion object {
        val DEFAULT = GradientFactors(0.40, 0.85)
        val OFF = GradientFactors(1.0, 1.0)
    }
}
