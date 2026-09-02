package com.ochakov.divemaster.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.ochakov.divemaster.data.db.DiveMasterDatabase
import com.ochakov.divemaster.data.settings.DiveSettings
import com.ochakov.divemaster.data.settings.SettingsRepository
import com.ochakov.divemaster.mobile.sync.SyncRepository
import com.ochakov.divemaster.mobile.ui.DiveDetailScreen
import com.ochakov.divemaster.mobile.ui.DiveListScreen
import com.ochakov.divemaster.mobile.ui.MobileTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MobileTheme {
                AppRoot()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val dao = remember { DiveMasterDatabase.get(context).diveDao() }
    val syncRepository = remember { SyncRepository(context) }
    val settingsRepository = remember { SettingsRepository(context) }
    val settings by settingsRepository.settings.collectAsState(initial = DiveSettings())
    val dives by dao.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var selectedDiveId by rememberSaveable { mutableStateOf(-1L) }
    var syncStatus by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }

    fun runSync() {
        if (syncing) return
        scope.launch {
            syncing = true
            val imported = syncRepository.importFromDataLayer()
            syncStatus = when {
                imported < 0 -> "Sync unavailable — watch paired and nearby?"
                imported == 0 -> "Up to date"
                else -> "Imported $imported dive${if (imported == 1) "" else "s"}"
            }
            syncing = false
        }
    }

    LaunchedEffect(Unit) { runSync() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedDiveId >= 0) "Dive" else "DiveMaster") },
                navigationIcon = {
                    if (selectedDiveId >= 0) {
                        TextButton(onClick = { selectedDiveId = -1L }) { Text("← Back") }
                    }
                },
                actions = {
                    TextButton(
                        onClick = { scope.launch { settingsRepository.setMetricUnits(!settings.metricUnits) } },
                    ) { Text(if (settings.metricUnits) "m" else "ft") }
                    TextButton(onClick = { runSync() }, enabled = !syncing) {
                        Text(if (syncing) "…" else "Sync")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (selectedDiveId >= 0) {
                DiveDetailScreen(diveId = selectedDiveId, metric = settings.metricUnits)
            } else {
                DiveListScreen(
                    dives = dives,
                    metric = settings.metricUnits,
                    syncStatus = syncStatus,
                    onOpen = { selectedDiveId = it },
                )
            }
        }
    }
    if (selectedDiveId >= 0) {
        BackHandler { selectedDiveId = -1L }
    }
}
