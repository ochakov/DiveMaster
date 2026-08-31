package com.ochakov.divemaster.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ochakov.divemaster.deco.GradientFactors
import com.ochakov.divemaster.deco.WaterType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.diveDataStore by preferencesDataStore(name = "dive_settings")

class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.diveDataStore

    private object Keys {
        val O2 = doublePreferencesKey("o2_fraction")
        val WATER = stringPreferencesKey("water_type")
        val GF_LOW = doublePreferencesKey("gf_low")
        val GF_HIGH = doublePreferencesKey("gf_high")
        val METRIC = booleanPreferencesKey("metric_units")
        val RATE_ALERTS = booleanPreferencesKey("rate_alerts_enabled")
        val ASCENT = doublePreferencesKey("ascent_alert_m_per_min")
        val DESCENT = doublePreferencesKey("descent_alert_m_per_min")
        val NDL_ALERT = booleanPreferencesKey("ndl_alert_enabled")
        val NDL_MIN = intPreferencesKey("ndl_alert_minutes")
        val SAFETY_STOP = intPreferencesKey("safety_stop_minutes")
        val MAX_PPO2 = doublePreferencesKey("max_ppo2_bar")
        val BEEP = booleanPreferencesKey("beep_enabled")
        val VIBRATE = booleanPreferencesKey("vibrate_enabled")
        val SIMULATOR = booleanPreferencesKey("simulator_enabled")
    }

    val settings: Flow<DiveSettings> = dataStore.data.map { p ->
        val d = DiveSettings()
        DiveSettings(
            o2Fraction = p[Keys.O2] ?: d.o2Fraction,
            waterType = p[Keys.WATER]
                ?.let { stored -> WaterType.entries.firstOrNull { it.name == stored } }
                ?: d.waterType,
            gradientFactors = runCatching {
                GradientFactors(
                    p[Keys.GF_LOW] ?: d.gradientFactors.low,
                    p[Keys.GF_HIGH] ?: d.gradientFactors.high,
                )
            }.getOrDefault(d.gradientFactors),
            metricUnits = p[Keys.METRIC] ?: d.metricUnits,
            rateAlertsEnabled = p[Keys.RATE_ALERTS] ?: d.rateAlertsEnabled,
            ascentAlertMPerMin = p[Keys.ASCENT] ?: d.ascentAlertMPerMin,
            descentAlertMPerMin = p[Keys.DESCENT] ?: d.descentAlertMPerMin,
            ndlAlertEnabled = p[Keys.NDL_ALERT] ?: d.ndlAlertEnabled,
            ndlAlertMinutes = p[Keys.NDL_MIN] ?: d.ndlAlertMinutes,
            safetyStopMinutes = p[Keys.SAFETY_STOP] ?: d.safetyStopMinutes,
            maxPpO2Bar = p[Keys.MAX_PPO2] ?: d.maxPpO2Bar,
            beepEnabled = p[Keys.BEEP] ?: d.beepEnabled,
            vibrateEnabled = p[Keys.VIBRATE] ?: d.vibrateEnabled,
            simulatorEnabled = p[Keys.SIMULATOR] ?: d.simulatorEnabled,
        )
    }

    suspend fun setGasO2Fraction(o2Fraction: Double) {
        dataStore.edit { it[Keys.O2] = o2Fraction }
    }

    suspend fun setWaterType(waterType: WaterType) {
        dataStore.edit { it[Keys.WATER] = waterType.name }
    }

    suspend fun setMetricUnits(metric: Boolean) {
        dataStore.edit { it[Keys.METRIC] = metric }
    }

    suspend fun setGradientFactors(gf: GradientFactors) {
        dataStore.edit {
            it[Keys.GF_LOW] = gf.low
            it[Keys.GF_HIGH] = gf.high
        }
    }

    suspend fun setSimulatorEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.SIMULATOR] = enabled }
    }

    // Remaining setters land together with the editable settings screen (Phase 5).
}
