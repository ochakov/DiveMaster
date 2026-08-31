package com.ochakov.divemaster.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

val DiveCyan = Color(0xFF00E5FF)
val DiveGreen = Color(0xFF69F0AE)
val DiveAmber = Color(0xFFFFD740)
val DiveRed = Color(0xFFFF5252)

private val DiveColors = Colors(
    primary = DiveCyan,
    primaryVariant = Color(0xFF00B8D4),
    secondary = DiveGreen,
    secondaryVariant = Color(0xFF00C853),
    background = Color.Black,
    surface = Color(0xFF16191C),
    error = DiveRed,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    onError = Color.Black,
)

@Composable
fun DiveMasterTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = DiveColors, content = content)
}
