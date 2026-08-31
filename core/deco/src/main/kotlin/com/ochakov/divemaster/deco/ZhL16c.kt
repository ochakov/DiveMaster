package com.ochakov.divemaster.deco

/**
 * Bühlmann ZH-L16C nitrogen compartment coefficients, using the 1b variant
 * (5.0 min first compartment) as is conventional for dive computers.
 *
 * The a/b pairs are the published ZH-L16C nitrogen set. Independently of the
 * table, every b satisfies b = 1.005 - halfTime^-0.5 (verified by unit test),
 * and the a values sit at or below the ZH-L16A formula a = 2 * halfTime^-1/3,
 * which is what makes the C variant more conservative for computer use.
 */
object ZhL16c {
    data class Compartment(val halfTimeMin: Double, val a: Double, val b: Double)

    val COMPARTMENTS: List<Compartment> = listOf(
        Compartment(5.0, 1.1696, 0.5578),
        Compartment(8.0, 1.0000, 0.6514),
        Compartment(12.5, 0.8618, 0.7222),
        Compartment(18.5, 0.7562, 0.7725),
        Compartment(27.0, 0.6200, 0.8125),
        Compartment(38.3, 0.5043, 0.8434),
        Compartment(54.3, 0.4410, 0.8693),
        Compartment(77.0, 0.4000, 0.8910),
        Compartment(109.0, 0.3750, 0.9092),
        Compartment(146.0, 0.3500, 0.9222),
        Compartment(187.0, 0.3295, 0.9319),
        Compartment(239.0, 0.3065, 0.9403),
        Compartment(305.0, 0.2835, 0.9477),
        Compartment(390.0, 0.2610, 0.9544),
        Compartment(498.0, 0.2480, 0.9602),
        Compartment(635.0, 0.2327, 0.9653),
    )

    val SIZE: Int = COMPARTMENTS.size

    /** Alveolar water vapor pressure at 37 °C (Bühlmann's value). */
    const val WATER_VAPOR_BAR = 0.0627
}
