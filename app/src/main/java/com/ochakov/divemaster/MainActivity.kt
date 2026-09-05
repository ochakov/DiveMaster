package com.ochakov.divemaster

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ochakov.divemaster.data.settings.SettingsRepository
import com.ochakov.divemaster.engine.DivePhase
import com.ochakov.divemaster.ui.DisclaimerScreen
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.ochakov.divemaster.service.DiveService
import com.ochakov.divemaster.ui.dive.DiveScreen
import com.ochakov.divemaster.ui.log.DiveDetailScreen
import com.ochakov.divemaster.ui.log.LogScreen
import com.ochakov.divemaster.ui.probe.ProbeScreen
import com.ochakov.divemaster.ui.settings.NumberSettingScreen
import com.ochakov.divemaster.ui.settings.SettingsScreen
import com.ochakov.divemaster.ui.surface.SurfaceScreen
import com.ochakov.divemaster.ui.theme.DiveMasterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        setContent {
            DiveMasterTheme {
                DiveMasterRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Keep the display on while the app is open (a dive computer shouldn't
        // sleep after 15 s). Note: this cannot override the forced screen-off
        // the OS triggers on water contact — only Samsung Water Lock can.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        DiveService.activityVisible = true
        DiveService.start(this) // ACTION_MONITOR: (re)arms monitoring
    }

    override fun onStop() {
        // A screen-off must NOT stop monitoring — otherwise a dive is missed the
        // instant the watch gets wet. On Wear the activity may be FINISHED on a
        // plain timeout, so isFinishing can't distinguish "user left" from
        // "screen slept". Monitoring instead stands down only via the service's
        // surface idle-timeout, so it always survives water entry.
        DiveService.activityVisible = false
        super.onStop()
    }
}

@Composable
fun DiveMasterRoot() {
    val context = LocalContext.current
    val settings by remember { SettingsRepository(context) }.settings.collectAsState(initial = null)
    when (val current = settings) {
        null -> Unit // brief blank while DataStore loads
        else -> if (current.disclaimerAccepted) DiveMasterNavHost() else DisclaimerScreen()
    }
}

@Composable
fun DiveMasterNavHost() {
    val navController = rememberSwipeDismissableNavController()
    val engineState by DiveService.displayState.collectAsState()
    val diving = engineState?.phase == DivePhase.DIVING

    // Hands-free navigation: jump to the dive screen the moment a dive is
    // confirmed, return to the surface screen when it ends. Swipe-to-dismiss
    // is disabled while diving so water contact cannot navigate away.
    LaunchedEffect(diving) {
        if (diving) {
            navController.navigate("dive") { launchSingleTop = true }
        } else if (navController.currentDestination?.route == "dive") {
            navController.popBackStack("surface", false)
        }
    }

    SwipeDismissableNavHost(
        navController = navController,
        startDestination = "surface",
        userSwipeEnabled = !diving,
    ) {
        composable("surface") {
            SurfaceScreen(
                onOpenLog = { navController.navigate("log") },
                onOpenSettings = { navController.navigate("settings") },
                onOpenProbe = { navController.navigate("probe") },
                onOpenDive = { navController.navigate("dive") },
            )
        }
        composable("probe") { ProbeScreen() }
        composable("dive") { DiveScreen() }
        composable("settings") {
            SettingsScreen(onEditNumber = { id -> navController.navigate("setting/${id.name}") })
        }
        composable("setting/{id}") { entry ->
            NumberSettingScreen(entry.arguments?.getString("id") ?: "")
        }
        composable("log") {
            LogScreen(onOpenDive = { diveId -> navController.navigate("log/$diveId") })
        }
        composable("log/{diveId}") { entry ->
            DiveDetailScreen(
                diveId = entry.arguments?.getString("diveId")?.toLongOrNull() ?: -1L,
                onDeleted = { navController.popBackStack() },
            )
        }
    }
}
