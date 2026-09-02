package com.ochakov.divemaster.data.transfer

/** Data Layer paths and DataMap keys shared by the wear publisher and phone importer. */
object DiveSyncKeys {
    const val PATH_PREFIX = "/dives/"
    const val KEY_START = "startEpochMs"
    const val KEY_END = "endEpochMs"
    const val KEY_MAX_DEPTH = "maxDepthM"
    const val KEY_AVG_DEPTH = "avgDepthM"
    const val KEY_MIN_TEMP = "minTempC"
    const val KEY_GAS_O2 = "gasO2Fraction"
    const val KEY_WATER = "waterType"
    const val KEY_SURFACE_MBAR = "surfacePressureMbar"
    const val KEY_GF_LOW = "gfLow"
    const val KEY_GF_HIGH = "gfHigh"
    const val KEY_SAMPLES = "samples"
}
