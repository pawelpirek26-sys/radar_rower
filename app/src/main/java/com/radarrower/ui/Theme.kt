package com.radarrower.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Paleta stanów drogi — duży kontrast, czytelna w słońcu
object RoadColors {
    val ClearBg = Color(0xFF0B3D14)      // zielony ciemny (tło)
    val ClearAccent = Color(0xFF4CAF50)
    val VehicleBg = Color(0xFF4A2A00)    // pomarańczowy ciemny
    val VehicleAccent = Color(0xFFFF9800)
    val UrgentBg = Color(0xFF4A0505)     // czerwony ciemny
    val UrgentAccent = Color(0xFFFF3B30)
    val Road = Color(0xFF1C1F24)
    val RoadLine = Color(0xFF9AA0A6)
}

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7BD88F),
    secondary = Color(0xFFFF9800),
    background = Color(0xFF0E1116),
    surface = Color(0xFF161A20),
)

@Composable
fun RadarRowerTheme(content: @Composable () -> Unit) {
    // Aplikacja jest ciemna z założenia (jazda, oszczędzanie OLED) — ignorujemy motyw systemu
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
