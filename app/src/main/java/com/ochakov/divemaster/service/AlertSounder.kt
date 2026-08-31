package com.ochakov.divemaster.service

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.ochakov.divemaster.engine.DiveAlert

/**
 * Plays alerts. Vibration is the primary channel underwater (speakers are
 * near-inaudible at depth); each alert type has a distinct waveform so it can
 * be recognized without looking. When several alerts fire in one sample, only
 * the most severe one plays (enum ordinal = severity).
 */
class AlertSounder(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val toneGenerator: ToneGenerator? = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
    }.getOrNull()

    fun play(alerts: List<DiveAlert>, vibrate: Boolean, beep: Boolean) {
        val alert = alerts.minByOrNull { it.ordinal } ?: return
        if (vibrate) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern(alert), -1))
        }
        if (beep) {
            toneGenerator?.startTone(tone(alert), BEEP_DURATION_MS)
        }
    }

    fun release() {
        toneGenerator?.release()
    }

    /** Off/on millisecond pairs; designed to be distinguishable by feel. */
    private fun pattern(alert: DiveAlert): LongArray = when (alert) {
        DiveAlert.PPO2_HIGH -> longArrayOf(0, 120, 80, 120, 80, 120, 80, 500)
        DiveAlert.DECO_ENTERED -> longArrayOf(0, 600, 250, 600, 250, 600)
        DiveAlert.CNS_HIGH -> longArrayOf(0, 450, 150, 120, 80, 120)
        DiveAlert.ASCENT_TOO_FAST -> longArrayOf(0, 100, 70, 100, 70, 100, 70, 100)
        DiveAlert.SAFETY_STOP_VIOLATED -> longArrayOf(0, 250, 120, 250, 120, 250)
        DiveAlert.DESCENT_TOO_FAST -> longArrayOf(0, 180, 150, 180, 150, 180)
        DiveAlert.LOW_NDL -> longArrayOf(0, 400, 200, 400)
        DiveAlert.SAFETY_STOP_COMPLETE -> longArrayOf(0, 80, 120, 80)
    }

    private fun tone(alert: DiveAlert): Int = when (alert) {
        DiveAlert.SAFETY_STOP_COMPLETE -> ToneGenerator.TONE_PROP_ACK
        DiveAlert.LOW_NDL -> ToneGenerator.TONE_PROP_BEEP2
        else -> ToneGenerator.TONE_SUP_ERROR
    }

    private companion object {
        const val TONE_VOLUME = 90
        const val BEEP_DURATION_MS = 350
    }
}
