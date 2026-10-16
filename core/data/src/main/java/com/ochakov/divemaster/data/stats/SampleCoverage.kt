package com.ochakov.divemaster.data.stats

import kotlin.math.roundToInt

/**
 * How much of a dive's 1 Hz profile is actually there.
 *
 * The engine records one sample per second from the (backdated) start to the
 * end, so a complete dive has `durationSec + 1` samples at offsets
 * 0..durationSec. Anything less means the sensor stream stalled — the
 * 2026-10-16 dives arrived with 1 % and 21 % of their samples — and the
 * tissue model integrated those stretches blind. Both detail screens show
 * this so a thin profile is never mistaken for a quiet dive.
 */
object SampleCoverage {

    data class Result(
        /** Seconds the dive spans, offsets 0..durationSec inclusive. */
        val spanSec: Int,
        /** Distinct seconds that have a sample. */
        val presentSec: Int,
        /** Stretches of one or more missing seconds. */
        val gapCount: Int,
        /** Length of the longest stretch of missing seconds. */
        val longestGapSec: Int,
        /** Offset into the dive where the longest gap begins. */
        val longestGapStartSec: Int,
    ) {
        val missingSec: Int get() = spanSec - presentSec
        val complete: Boolean get() = missingSec == 0
        val fraction: Double get() = if (spanSec <= 0) 1.0 else presentSec.toDouble() / spanSec

        /** "100%" only when nothing is missing; "<1%" for a trace of a dive. */
        fun percentLabel(): String {
            if (complete) return "100%"
            val pct = fraction * 100
            return when {
                pct < 1.0 -> "<1%"
                pct >= 99.5 -> "99%"
                else -> "${pct.roundToInt()}%"
            }
        }

        /** The longest gap as m:ss, or null when the profile is complete. */
        fun longestGapLabel(): String? = if (gapCount == 0) null else minSec(longestGapSec)

        /** Where the longest gap starts, as m:ss into the dive. */
        fun longestGapStartLabel(): String = minSec(longestGapStartSec)
    }

    fun of(offsetsSec: Collection<Int>, durationSec: Long): Result = of(offsetsSec, durationSec.toInt())

    fun of(offsetsSec: Collection<Int>, durationSec: Int): Result {
        val sorted = offsetsSec.filter { it >= 0 }.distinct().sorted()
        val span = maxOf(durationSec, sorted.lastOrNull() ?: 0) + 1
        var gaps = 0
        var longest = 0
        var longestStart = 0
        var expected = 0
        fun gap(start: Int, length: Int) {
            gaps++
            if (length > longest) {
                longest = length
                longestStart = start
            }
        }
        for (offset in sorted) {
            if (offset > expected) gap(expected, offset - expected)
            expected = offset + 1
        }
        if (expected < span) gap(expected, span - expected)
        return Result(
            spanSec = span,
            presentSec = sorted.size,
            gapCount = gaps,
            longestGapSec = longest,
            longestGapStartSec = longestStart,
        )
    }

    fun minSec(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
}
