package com.ochakov.divemaster

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.ochakov.divemaster.service.DiveService
import com.ochakov.divemaster.ui.dive.DiveScreen
import com.ochakov.divemaster.ui.log.LogScreen
import com.ochakov.divemaster.ui.probe.ProbeScreen
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
                DiveMasterNavHost()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DiveService.activityVisible = true
        DiveService.start(this)
    }

    override fun onStop() {
        DiveService.activityVisible = false
        if (DiveService.serviceRunning.value) {
            DiveService.start(this, DiveService.ACTION_MAYBE_STOP)
        }
        super.onStop()
    }
}

@Composable
fun DiveMasterNavHost() {
    val navController = rememberSwipeDismissableNavController()
    SwipeDismissableNavHost(navController = navController, startDestination = "surface") {
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
        composable("settings") { SettingsScreen() }
        composable("log") { LogScreen() }
    }
}
