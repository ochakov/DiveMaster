package com.ochakov.divemaster.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Stepper
import androidx.wear.compose.material.Text
import com.ochakov.divemaster.data.settings.DiveSettings
import com.ochakov.divemaster.data.settings.SettingsRepository
import kotlinx.coroutines.launch

/**
 * Full-screen +/- editor for one numeric setting. The DataStore value is the
 * single source of truth: each tap saves immediately and the display follows
 * the settings flow, so there is no confirm step to forget.
 */
@Composable
fun NumberSettingScreen(idName: String) {
    val id = runCatching { NumberSettingId.valueOf(idName) }.getOrNull() ?: return
    val spec = NumberSettings.specs.getValue(id)
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context) }
    val settings by repository.settings.collectAsState(initial = DiveSettings())
    val scope = rememberCoroutineScope()

    val value = spec.get(settings).coerceIn(spec.progression.first, spec.progression.last)
    Stepper(
        value = value,
        onValueChange = { newValue -> scope.launch { spec.save(repository, newValue) } },
        valueProgression = spec.progression,
        decreaseIcon = { Text("−", fontSize = 28.sp) },
        increaseIcon = { Text("+", fontSize = 28.sp) },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().align(Alignment.Center),
        ) {
            Text(spec.title, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.secondary)
            Text(spec.display(value, settings.metricUnits), style = MaterialTheme.typography.title1)
        }
    }
}
