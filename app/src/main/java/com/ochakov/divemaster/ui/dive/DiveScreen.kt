package com.ochakov.divemaster.ui.dive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.ochakov.divemaster.ui.theme.DiveCyan
import com.ochakov.divemaster.ui.theme.DiveGreen

/**
 * Static preview of the underwater layout (modeled on the reference photo:
 * max depth and temperature up top, huge current depth, NDL and dive time
 * below, gas label, ascent-rate bar on the right edge). Wired to the live
 * dive engine in Phase 4.
 */
@Composable
fun DiveScreen() {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LabeledValue("MAX", "16.8")
                LabeledValue("TEMP", "12.4°")
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("14.5", fontSize = 58.sp, fontWeight = FontWeight.Bold, color = DiveCyan)
                Text(
                    "m",
                    fontSize = 16.sp,
                    color = DiveCyan.copy(alpha = 0.8f),
                    modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LabeledValue("NDL", "26'", valueColor = DiveGreen)
                LabeledValue("TIME", "37:40")
            }
            Spacer(Modifier.height(6.dp))
            Text("AIR", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DiveGreen)
            Spacer(Modifier.height(4.dp))
            Text("PREVIEW · goes live in Phase 4", fontSize = 9.sp, color = Color.White.copy(alpha = 0.4f))
        }
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            repeat(4) { index ->
                Box(
                    Modifier
                        .size(width = 6.dp, height = 10.dp)
                        .background(DiveGreen.copy(alpha = if (index >= 2) 1f else 0.25f)),
                )
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String, valueColor: Color = Color.White) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}
