package com.radarrower.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radarrower.ble.RadarTarget
import com.radarrower.core.ConnectionState
import com.radarrower.core.RadarRepository
import com.radarrower.core.ThreatLevel
import kotlinx.coroutines.delay

/** Maksymalny dystans pokazywany na pasie drogi (zasięg radaru ~140 m). */
private const val MAX_DISTANCE_M = 150f

/** Po ilu ms pustej drogi tryb czuwania gasi ekran. */
private const val STANDBY_DELAY_MS = 10_000L

/**
 * Główny ekran jazdy: pionowy pas drogi — rowerzysta na dole, nadjeżdżające
 * auta jako retro mini-samochody schodzące z góry, z dystansem i prędkością.
 * Kolor tła według poziomu zagrożenia (motyw jasny/ciemny za systemem).
 * Tryb czuwania: przy pustej drodze ekran robi się czarny (OLED oszczędza
 * baterię) i zapala się sam, gdy radar wykryje auto.
 */
@Composable
fun RideScreen(
    redThresholdKmh: Int,
    standbyEnabled: Boolean,
    onToggleStandby: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDebug: () -> Unit,
) {
    val palette = roadPalette()
    val targets by RadarRepository.targets.collectAsStateWithLifecycle()
    val threat by RadarRepository.threatLevel.collectAsStateWithLifecycle()
    val connection by RadarRepository.connectionState.collectAsStateWithLifecycle()
    val battery by RadarRepository.batteryLevel.collectAsStateWithLifecycle()

    val connected = connection == ConnectionState.CONNECTED
    val bgColor by animateColorAsState(
        // bez połączenia tło neutralne — zielony nie może udawać „droga czysta"
        targetValue = if (!connected) palette.neutralBg else when (threat) {
            ThreatLevel.CLEAR -> palette.clearBg
            ThreatLevel.VEHICLE -> palette.vehicleBg
            ThreatLevel.URGENT -> palette.urgentBg
        },
        label = "bg",
    )
    val accent = when (threat) {
        ThreatLevel.CLEAR -> palette.clearAccent
        ThreatLevel.VEHICLE -> palette.vehicleAccent
        ThreatLevel.URGENT -> palette.urgentAccent
    }

    // tryb czuwania: po STANDBY_DELAY_MS pustej drogi gasimy ekran;
    // auto na radarze / dotknięcie / utrata połączenia budzą natychmiast
    var blanked by remember { mutableStateOf(false) }
    var wakeTaps by remember { mutableIntStateOf(0) }
    LaunchedEffect(standbyEnabled, connected, targets.isEmpty(), wakeTaps) {
        if (standbyEnabled && connected && targets.isEmpty()) {
            blanked = false
            delay(STANDBY_DELAY_MS)
            blanked = true
        } else {
            blanked = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                // kolor tła wchodzi pod paski systemowe, treść już nie
                .systemBarsPadding(),
        ) {
            // pasek statusu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when (connection) {
                        ConnectionState.CONNECTED ->
                            "● RADAR" + (battery?.let { "  🔋$it%" } ?: "")
                        ConnectionState.CONNECTING -> "○ ŁĄCZENIE…"
                        ConnectionState.RECONNECTING -> "○ PONAWIAM…"
                        ConnectionState.SCANNING -> "○ SZUKANIE…"
                        ConnectionState.DISCONNECTED -> "○ ROZŁĄCZONO"
                        ConnectionState.INCOMPATIBLE -> "○ ZŁE URZĄDZENIE"
                    },
                    color = if (connected) accent else palette.textSecondary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onToggleStandby) {
                    Icon(
                        Icons.Filled.DarkMode,
                        contentDescription = "Tryb czuwania",
                        tint = if (standbyEnabled) accent else palette.textSecondary,
                    )
                }
                IconButton(onClick = onOpenDebug) {
                    Icon(Icons.Filled.BugReport, contentDescription = "Debug", tint = palette.textSecondary)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Ustawienia", tint = palette.textSecondary)
                }
            }

            if (connected) {
                // licznik aut — duży, do zerknięcia
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (targets.isEmpty()) "CZYSTO" else "${targets.size}",
                        color = palette.textPrimary,
                        fontSize = if (targets.isEmpty()) 44.sp else 72.sp,
                        fontWeight = FontWeight.Black,
                    )
                    if (targets.isNotEmpty()) {
                        Text(
                            text = "  z tyłu",
                            color = palette.textSecondary,
                            fontSize = 28.sp,
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = when (connection) {
                            ConnectionState.CONNECTING -> "ŁĄCZENIE…"
                            ConnectionState.RECONNECTING -> "PONAWIAM…"
                            ConnectionState.INCOMPATIBLE -> "TO NIE RADAR"
                            else -> "BRAK POŁĄCZENIA"
                        },
                        color = palette.textPrimary,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        text = if (connection == ConnectionState.INCOMPATIBLE) {
                            "Wybierz inne urządzenie: Ustawienia → Zmień radar"
                        } else {
                            "Sprawdź, czy radar jest włączony"
                        },
                        color = palette.textSecondary,
                        fontSize = 16.sp,
                    )
                }
            }

            // pas drogi
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                RoadStrip(
                    targets = targets,
                    palette = palette,
                    redThresholdKmh = redThresholdKmh,
                    modifier = Modifier.fillMaxSize().padding(bottom = 12.dp),
                )
            }
        }

        // czuwanie: czarna zasłona (na OLED piksele są fizycznie wyłączone);
        // dotknięcie budzi na chwilę, wykryte auto budzi samo (targets → effect)
        if (blanked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { wakeTaps++ },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Text(
                    "· czuwanie ·",
                    color = Color(0xFF2A2E33),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun RoadStrip(
    targets: List<RadarTarget>,
    palette: RoadPalette,
    redThresholdKmh: Int,
    modifier: Modifier = Modifier,
) {
    val textPaint = android.graphics.Paint().apply {
        color = palette.textPrimary.toArgb()
        textSize = 64f
        isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val speedPaint = android.graphics.Paint().apply {
        color = palette.textSecondary.toArgb()
        textSize = 42f
        isAntiAlias = true
    }

    // Widok drogi ZA plecami (radar patrzy do tyłu): rowerzysta u góry,
    // auta wjeżdżają od dołu (daleko z tyłu) i wspinają się ku niemu,
    // przodem w jego stronę — doganiają, nie jadą z naprzeciwka.
    Canvas(modifier = modifier) {
        val roadWidth = size.width * 0.46f
        val roadLeft = (size.width - roadWidth) / 2f
        val riderY = 110f

        // jezdnia
        drawRoundRect(
            color = palette.road,
            topLeft = Offset(roadLeft, 0f),
            size = Size(roadWidth, size.height),
            cornerRadius = CornerRadius(24f, 24f),
        )
        // przerywana linia środkowa — grube retro klocki
        val dashH = size.height / 16f
        var y = 12f
        while (y < size.height) {
            drawRect(
                color = palette.roadLine,
                topLeft = Offset(size.width / 2f - 7f, y),
                size = Size(14f, dashH * 0.5f),
            )
            y += dashH
        }

        // rowerzysta (zielony trójnik pixel-art) u góry, na prawym pasie
        val riderX = size.width / 2f + roadWidth / 4f
        drawPixelTriangle(riderX, riderY, palette.rider)
        drawContext.canvas.nativeCanvas.drawText("TY", riderX - 24f, riderY + 90f, speedPaint)

        // auta doganiają: dystans 0 = tuż za rowerzystą (góra), MAX = dół pasa
        val laneX = size.width / 2f - roadWidth / 4f
        targets.forEach { t ->
            val frac = (t.distanceM.coerceIn(0, MAX_DISTANCE_M.toInt()) / MAX_DISTANCE_M)
            val yNear = riderY + 130f
            val yFar = size.height - 80f
            val cy = yNear + (yFar - yNear) * frac
            val carColor = if (t.speedKmh >= redThresholdKmh) {
                palette.urgentAccent
            } else {
                palette.vehicleAccent
            }

            drawPixelCar(laneX, cy, carColor, palette.carWindow, palette.carWheel)
            drawContext.canvas.nativeCanvas.drawText(
                "${t.distanceM} m",
                laneX + 64f,
                cy + 6f,
                textPaint,
            )
            drawContext.canvas.nativeCanvas.drawText(
                "${t.speedKmh} km/h",
                laneX + 64f,
                cy + 54f,
                speedPaint,
            )
        }
    }
}

/** Retro mini-samochód z góry: karoseria, szyby, koła — jak w starych grach. */
private fun DrawScope.drawPixelCar(
    cx: Float,
    cy: Float,
    body: Color,
    window: Color,
    wheel: Color,
) {
    val w = 68f
    val h = 108f
    val left = cx - w / 2f
    val top = cy - h / 2f

    // koła wystające po bokach (2 osie)
    listOf(top + 14f, top + h - 32f).forEach { wy ->
        drawRect(wheel, Offset(left - 7f, wy), Size(14f, 20f))
        drawRect(wheel, Offset(left + w - 7f, wy), Size(14f, 20f))
    }
    // karoseria
    drawRoundRect(body, Offset(left, top), Size(w, h), CornerRadius(16f, 16f))
    // przednia szyba u GÓRY — auto jedzie w górę ekranu, dogania rowerzystę
    drawRoundRect(window, Offset(left + 10f, top + 22f), Size(w - 20f, 22f), CornerRadius(7f, 7f))
    // tylna szyba
    drawRoundRect(window, Offset(left + 10f, top + h - 36f), Size(w - 20f, 18f), CornerRadius(7f, 7f))
    // maska — jaśniejszy pasek nad przednią szybą
    drawRect(body.copy(alpha = 0.7f), Offset(left + 8f, top + 8f), Size(w - 16f, 10f))
}

/** Pikselowy trójnik rowerzysty (schodkowe krawędzie jak w retro grach). */
private fun DrawScope.drawPixelTriangle(cx: Float, cy: Float, color: Color) {
    val steps = 6
    val stepH = 14f
    val stepW = 7f
    for (i in 0 until steps) {
        val halfW = stepW * (i + 1)
        drawRect(
            color,
            Offset(cx - halfW, cy - 46f + i * stepH),
            Size(halfW * 2f, stepH),
        )
    }
    drawRect(
        Color.White.copy(alpha = 0.9f),
        Offset(cx - stepW * steps, cy - 46f + steps * stepH),
        Size(stepW * steps * 2f, 5f),
    )
}
