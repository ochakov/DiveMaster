package com.ochakov.divemaster.engine

/** Alert types, declared most-severe first (ordinal = priority). */
enum class DiveAlert {
    PPO2_HIGH,
    DECO_ENTERED,
    CNS_HIGH,
    ASCENT_TOO_FAST,
    SAFETY_STOP_VIOLATED,
    DESCENT_TOO_FAST,
    LOW_NDL,
    SAFETY_STOP_COMPLETE,
}

data class AlertConfig(
    val rateAlertsEnabled: Boolean = true,
    val ascentRateMPerMin: Double = 10.0,
    val descentRateMPerMin: Double = 20.0,
    val ndlAlertEnabled: Boolean = true,
    val ndlAlertMinutes: Int = 5,
    val maxPpO2Bar: Double = 1.4,
    val cnsWarnFraction: Double = 0.8,
)

/**
 * Turns the per-sample display state into discrete alert firings.
 *
 * Sustained conditions (rate, ppO2, skipped stop) re-fire on a cooldown while
 * they persist; threshold crossings (low NDL, deco entry, CNS levels, stop
 * complete) fire once and re-arm with hysteresis. All timing uses sample
 * timestamps, so behavior is identical for real and simulated dives. Alerts
 * only fire while diving; per-dive state resets on surfacing (CNS memory is
 * deliberately kept — it spans dives).
 */
class AlertEvaluator(private val config: AlertConfig) {
    private var lastPhase = DivePhase.SURFACE
    private val nextAllowedMs = mutableMapOf<DiveAlert, Long>()
    private var lowNdlArmed = true
    private var decoArmed = true
    private var cnsWarnFired = false
    private var cnsFullFired = false
    private var stopCompleteFired = false

    fun evaluate(state: DiveDisplayState, nowMs: Long): List<DiveAlert> {
        if (state.phase != DivePhase.DIVING) {
            if (lastPhase == DivePhase.DIVING) resetPerDive()
            lastPhase = state.phase
            return emptyList()
        }
        lastPhase = DivePhase.DIVING
        val fired = mutableListOf<DiveAlert>()

        if (config.rateAlertsEnabled) {
            if (state.verticalRateMPerMin > config.ascentRateMPerMin) {
                fireRepeating(DiveAlert.ASCENT_TOO_FAST, nowMs, REPEAT_RATE_MS, fired)
            }
            if (-state.verticalRateMPerMin > config.descentRateMPerMin) {
                fireRepeating(DiveAlert.DESCENT_TOO_FAST, nowMs, REPEAT_RATE_MS, fired)
            }
        }

        if (config.ndlAlertEnabled) {
            val ndl = state.ndlMin
            if (lowNdlArmed && ndl.isFinite() && ndl > 0.0 && ndl <= config.ndlAlertMinutes.toDouble()) {
                lowNdlArmed = false
                fired += DiveAlert.LOW_NDL
            } else if (!lowNdlArmed && ndl > config.ndlAlertMinutes + 2.0) {
                lowNdlArmed = true
            }
            if (decoArmed && ndl <= 0.0) {
                decoArmed = false
                fired += DiveAlert.DECO_ENTERED
            } else if (!decoArmed && ndl > 1.0) {
                decoArmed = true
            }
        }

        if (state.ppO2Bar > config.maxPpO2Bar) {
            fireRepeating(DiveAlert.PPO2_HIGH, nowMs, REPEAT_PPO2_MS, fired)
        }

        if (state.cnsFraction < config.cnsWarnFraction - 0.05) cnsWarnFired = false
        if (state.cnsFraction < 0.95) cnsFullFired = false
        if (!cnsWarnFired && state.cnsFraction >= config.cnsWarnFraction) {
            cnsWarnFired = true
            fired += DiveAlert.CNS_HIGH
        }
        if (!cnsFullFired && state.cnsFraction >= 1.0) {
            cnsFullFired = true
            fired += DiveAlert.CNS_HIGH
        }

        when (state.safetyStop) {
            SafetyStopState.DONE -> if (!stopCompleteFired) {
                stopCompleteFired = true
                fired += DiveAlert.SAFETY_STOP_COMPLETE
            }
            // Paused on the shallow side = ascending past an unfinished stop.
            SafetyStopState.PAUSED -> if (state.depthM < state.safetyStopMinDepthM - 0.3) {
                fireRepeating(DiveAlert.SAFETY_STOP_VIOLATED, nowMs, REPEAT_STOP_MS, fired)
            }
            else -> Unit
        }

        return fired
    }

    private fun fireRepeating(alert: DiveAlert, nowMs: Long, intervalMs: Long, out: MutableList<DiveAlert>) {
        val next = nextAllowedMs[alert] ?: 0L
        if (nowMs >= next) {
            nextAllowedMs[alert] = nowMs + intervalMs
            out += alert
        }
    }

    private fun resetPerDive() {
        nextAllowedMs.clear()
        lowNdlArmed = true
        decoArmed = true
        stopCompleteFired = false
        // CNS firing memory intentionally survives — the clock spans dives.
    }

    private companion object {
        const val REPEAT_RATE_MS = 10_000L
        const val REPEAT_PPO2_MS = 15_000L
        const val REPEAT_STOP_MS = 15_000L
    }
}
