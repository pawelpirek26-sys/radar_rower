package com.radarrower.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radarrower.ble.RadarTarget
import com.radarrower.core.ConnectionState
import com.radarrower.core.RadarRepository
import com.radarrower.core.ThreatLevel

/** Maksymalny dystans pokazywany na pasie drogi (zasięg radaru ~140 m). */
private const val MAX_DISTANCE_M = 150f

/**
 * Główny ekran jazdy: pionowy pas drogi — rowerzysta na dole, nadjeżdżające
 * auta jako kropki schodzące z góry, z dystansem w metrach. Kolor tła według
 * poziomu zagrożenia. Wszystko duże — do zerkania kątem oka na kierownicy.
 */
@Composable
fun RideScreen(
    onOpenSettings: () -> Unit,
    onOpenDebug: () -> Unit,
) {
    val targets by RadarRepository.targets.collectAsStateWithLifecycle()
    val threat by RadarRepository.threatLevel.collectAsStateWithLifecycle()
    val connection by RadarRepository.connectionState.collectAsStateWithLifecycle()

    val bgColor by animateColorAsState(
        targetValue = when (threat) {
            ThreatLevel.CLEAR -> RoadColors.ClearBg
            ThreatLevel.VEHICLE -> RoadColors.VehicleBg
            ThreatLevel.URGENT -> RoadColors.UrgentBg
        },
        label = "bg",
    )
    val accent = when (threat) {
        ThreatLevel.CLEAR -> RoadColors.ClearAccent
        ThreatLevel.VEHICLE -> RoadColors.VehicleAccent
        ThreatLevel.URGENT -> RoadColors.UrgentAccent
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor),
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
                    ConnectionState.CONNECTED -> "● RADAR"
                    ConnectionState.CONNECTING -> "○ ŁĄCZENIE…"
                    ConnectionState.RECONNECTING -> "○ PONAWIAM…"
                    ConnectionState.SCANNING -> "○ SZUKANIE…"
                    ConnectionState.DISCONNECTED -> "○ ROZŁĄCZONO"
                },
                color = if (connection == ConnectionState.CONNECTED) accent else Color(0xFFB0B6BC),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenDebug) {
                Icon(Icons.Filled.BugReport, contentDescription = "Debug", tint = Color(0xFFB0B6BC))
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Ustawienia", tint = Color(0xFFB0B6BC))
            }
        }

        // licznik aut — duży, do zerknięcia
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (targets.isEmpty()) "CZYSTO" else "${targets.size}",
                color = Color.White,
                fontSize = if (targets.isEmpty()) 44.sp else 72.sp,
                fontWeight = FontWeight.Black,
            )
            if (targets.isNotEmpty()) {
                Text(
                    text = "  z tyłu",
                    color = Color(0xFFD5DADF),
                    fontSize = 28.sp,
                )
            }
        }

        // pas drogi
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            RoadStrip(
                targets = targets,
                accent = accent,
                modifier = Modifier.fillMaxSize().padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun RoadStrip(
    targets: List<RadarTarget>,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val textPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 64f
        isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    Canvas(modifier = modifier) {
        val roadWidth = size.width * 0.42f
        val roadLeft = (size.width - roadWidth) / 2f
        val riderY = size.height * 0.94f

        // jezdnia
        drawRoundRect(
            color = RoadColors.Road,
            topLeft = Offset(roadLeft, 0f),
            size = Size(roadWidth, size.height),
            cornerRadius = CornerRadius(24f, 24f),
        )
        // przerywana linia środkowa
        val dashH = size.height / 18f
        var y = 0f
        while (y < size.height) {
            drawRoundRect(
                color = RoadColors.RoadLine,
                topLeft = Offset(size.width / 2f - 5f, y),
                size = Size(10f, dashH * 0.55f),
                cornerRadius = CornerRadius(5f, 5f),
            )
            y += dashH
        }

        // rowerzysta (trójkąt na dole, na prawym pasie)
        val riderX = size.width / 2f + roadWidth / 4f
        val riderPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(riderX, riderY - 46f)
            lineTo(riderX - 34f, riderY + 30f)
            lineTo(riderX + 34f, riderY + 30f)
            close()
        }
        drawPath(riderPath, color = Color(0xFF7BD88F))
        drawPath(riderPath, color = Color.White, style = Stroke(width = 4f))

        // auta: dystans 0 = przy rowerzyście, MAX = góra pasa
        val laneX = size.width / 2f - roadWidth / 4f
        targets.forEach { t ->
            val frac = (t.distanceM.coerceIn(0, MAX_DISTANCE_M.toInt()) / MAX_DISTANCE_M)
            // yTop = 150 m (góra pasa), yBottom = 0 m (tuż nad rowerzystą)
            val yTop = 60f
            val yBottom = riderY - 80f
            val cy = yBottom - (yBottom - yTop) * frac

            drawCircle(color = accent, radius = 34f, center = Offset(laneX, cy))
            drawCircle(
                color = Color.White,
                radius = 34f,
                center = Offset(laneX, cy),
                style = Stroke(width = 5f),
            )
            drawContext.canvas.nativeCanvas.drawText(
                "${t.distanceM} m",
                laneX + 56f,
                cy + 22f,
                textPaint,
            )
        }
    }
}
