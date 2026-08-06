package com.radarrower.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Paleta pasa drogi — osobna dla trybu ciemnego i jasnego.
 * Kolory stanów mają być rozpoznawalne kątem oka w słońcu.
 */
data class RoadPalette(
    val clearBg: Color,
    val vehicleBg: Color,
    val urgentBg: Color,
    val neutralBg: Color,
    val clearAccent: Color,
    val vehicleAccent: Color,
    val urgentAccent: Color,
    val road: Color,
    val roadLine: Color,
    val rider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val carWindow: Color,
    val carWheel: Color,
)

private val DarkRoad = RoadPalette(
    clearBg = Color(0xFF0B3D14),
    vehicleBg = Color(0xFF4A2A00),
    urgentBg = Color(0xFF4A0505),
    neutralBg = Color(0xFF14181D),
    clearAccent = Color(0xFF4CAF50),
    vehicleAccent = Color(0xFFFF9800),
    urgentAccent = Color(0xFFFF3B30),
    road = Color(0xFF1C1F24),
    roadLine = Color(0xFFB8BEC4),
    rider = Color(0xFF7BD88F),
    textPrimary = Color.White,
    textSecondary = Color(0xFFB0B6BC),
    carWindow = Color(0xFF20242B),
    carWheel = Color(0xFF101317),
)

private val LightRoad = RoadPalette(
    clearBg = Color(0xFFB9E6BC),
    vehicleBg = Color(0xFFFFD9A0),
    urgentBg = Color(0xFFFFBBB3),
    neutralBg = Color(0xFFE9ECEF),
    clearAccent = Color(0xFF1B7E2C),
    vehicleAccent = Color(0xFFB35C00),
    urgentAccent = Color(0xFFC62828),
    road = Color(0xFF3A3F46), // jezdnia zostaje ciemna — auta i linia mają kontrast
    roadLine = Color(0xFFEDEFF2),
    rider = Color(0xFF2E9E4F),
    textPrimary = Color(0xFF14181D),
    textSecondary = Color(0xFF4A5158),
    carWindow = Color(0xFF20242B),
    carWheel = Color(0xFF101317),
)

/** Czy UI jest w trybie ciemnym — ustawiane przez [RadarRowerTheme]. */
val LocalDarkMode = compositionLocalOf { true }

@Composable
fun roadPalette(): RoadPalette = if (LocalDarkMode.current) DarkRoad else LightRoad

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7BD88F),
    secondary = Color(0xFFFF9800),
    background = Color(0xFF0E1116),
    surface = Color(0xFF161A20),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1B7E2C),
    secondary = Color(0xFFB35C00),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
)

/**
 * Motyw aplikacji. [themeMode]: "system" (za systemem) | "light" | "dark" —
 * wybierany w Ustawieniach, działa natychmiast bez restartu.
 */
@Composable
fun RadarRowerTheme(themeMode: String = "system", content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    // kolory ikon pasków systemowych muszą iść za wymuszonym motywem
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }

    CompositionLocalProvider(LocalDarkMode provides dark) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            content = content,
        )
    }
}
