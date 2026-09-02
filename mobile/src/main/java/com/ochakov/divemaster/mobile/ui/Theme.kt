package com.ochakov.divemaster.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DiveCyan = Color(0xFF00E5FF)
val DiveGreen = Color(0xFF69F0AE)
val DiveAmber = Color(0xFFFFD740)
val DiveRed = Color(0xFFFF5252)

private val DarkColors = darkColorScheme(
    primary = DiveCyan,
    onPrimary = Color.Black,
    secondary = DiveGreen,
    onSecondary = Color.Black,
    error = DiveRed,
    background = Color(0xFF0B0F12),
    surface = Color(0xFF151A1E),
)

@Composable
fun MobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
