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
import androidx.compose.ui.graphics.PathEffect
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
 * Paleta kolorów aut — każde nadjeżdżające auto ma własny, stały kolor
 * (po ID celu), żeby dało się je rozróżnić na pasie. Czerwony jest
 * zarezerwowany dla aut powyżej progu prędkości.
 */
private val CAR_COLORS = listOf(
    Color(0xFFFF9800), // pomarańcz
    Color(0xFFFFC107), // bursztyn
    Color(0xFF29B6F6), // błękit
    Color(0xFFAB47BC), // fiolet
    Color(0xFF26C6DA), // cyjan
    Color(0xFFEC407A), // róż
)

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
    riderStyle: String,
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
    val deviceName by RadarRepository.deviceName.collectAsStateWithLifecycle()

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

            if (connected && deviceName?.contains("nasłuch") == true) {
                Text(
                    "⚠ Tryb nasłuchu protokołu — alerty NIEAKTYWNE, trwa rozpoznawanie formatu",
                    color = palette.vehicleAccent,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
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
                            "Jeśli to radar: zbliż go do telefonu i użyj\n" +
                                "Ustawienia → Restartuj połączenie.\n" +
                                "Jeśli nie: Ustawienia → Zmień radar."
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
                    riderStyle = riderStyle,
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
    riderStyle: String,
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

        // rowerzysta u góry, na prawym pasie — sylwetka boczna wg wyboru usera
        val riderX = size.width / 2f + roadWidth / 4f
        // koła/detale roweru jasne — jezdnia jest ciemna w obu motywach
        drawSideBike(riderX, riderY, riderStyle, palette.rider, palette.roadLine)
        drawContext.canvas.nativeCanvas.drawText("TY", riderX - 24f, riderY + 92f, speedPaint)

        // auta doganiają: dystans 0 = tuż za rowerzystą (góra), MAX = dół pasa
        val laneX = size.width / 2f - roadWidth / 4f
        targets.forEach { t ->
            val frac = (t.distanceM.coerceIn(0, MAX_DISTANCE_M.toInt()) / MAX_DISTANCE_M)
            val yNear = riderY + 130f
            val yFar = size.height - 80f
            val cy = yNear + (yFar - yNear) * frac
            // każde auto ma własny kolor po ID; czerwony tylko dla szybkich
            val carColor = if (t.speedKmh >= redThresholdKmh) {
                palette.urgentAccent
            } else {
                CAR_COLORS[t.id % CAR_COLORS.size]
            }

            drawPixelCar(laneX, cy, carColor, palette.carWindow, palette.carWheel)
            drawContext.canvas.nativeCanvas.drawText(
                "${t.distanceM} m",
                laneX + 64f,
                cy + 6f,
                textPaint,
            )
            // prędkość dopiero po zmierzeniu tempa zbliżania (~0,3 s od wykrycia)
            if (t.speedKmh > 0) {
                drawContext.canvas.nativeCanvas.drawText(
                    "+${t.speedKmh} km/h",
                    laneX + 64f,
                    cy + 54f,
                    speedPaint,
                )
            }
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

/** Sylwetka boczna pojazdu rowerzysty — styl wybierany w Ustawieniach. */
private fun DrawScope.drawSideBike(
    cx: Float,
    cy: Float,
    style: String,
    frame: Color,
    wheel: Color,
) {
    when (style) {
        "kids" -> drawKidsBike(cx, cy, frame, wheel)
        "road" -> drawSportBike(cx, cy, frame, wheel, tire = 4f)
        "mtb" -> drawMtbBike(cx, cy, frame, wheel)
        "city" -> drawCityBike(cx, cy, frame, wheel)
        // gravel: grube opony z drobnym bieżnikiem
        else -> drawSportBike(
            cx, cy, frame, wheel, tire = 10f,
            dash = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)),
        )
    }
}

/** Rower sportowy z boku (gravel/szosa różnią się grubością opon). */
private fun DrawScope.drawSportBike(
    cx: Float,
    cy: Float,
    frame: Color,
    wheel: Color,
    tire: Float,
    dash: PathEffect? = null,
) {
    val r = 30f
    val rear = Offset(cx - 48f, cy + 14f)
    val front = Offset(cx + 48f, cy + 14f)
    drawCircle(wheel, r, rear, style = Stroke(tire, pathEffect = dash))
    drawCircle(wheel, r, front, style = Stroke(tire, pathEffect = dash))
    drawCircle(frame, 5f, rear)
    drawCircle(frame, 5f, front)

    val bb = Offset(cx - 2f, cy + 20f)     // suport
    val seat = Offset(cx - 28f, cy - 26f)  // góra sztycy
    val head = Offset(cx + 36f, cy - 22f)  // główka ramy
    val lw = 8f
    drawLine(frame, rear, bb, lw)          // dolne widełki
    drawLine(frame, bb, seat, lw)          // rura podsiodłowa
    drawLine(frame, seat, rear, lw)        // górne widełki
    drawLine(frame, seat, head, lw)        // górna rura
    drawLine(frame, head, bb, lw)          // dolna rura
    drawLine(frame, head, front, lw)       // widelec
    // siodełko
    drawRoundRect(frame, Offset(seat.x - 15f, seat.y - 9f), Size(30f, 9f), CornerRadius(4f, 4f))
    // baranek
    drawLine(frame, head, Offset(head.x + 4f, head.y - 16f), lw)
    drawRoundRect(frame, Offset(head.x + 2f, head.y - 20f), Size(20f, 8f), CornerRadius(4f, 4f))
    drawLine(frame, Offset(head.x + 19f, head.y - 14f), Offset(head.x + 19f, head.y + 2f), 6f)
}

/** Rowerek dziecinny: małe koła, boczne kółka, wysoka kierownica, chorągiewka. */
private fun DrawScope.drawKidsBike(cx: Float, cy: Float, frame: Color, wheel: Color) {
    val r = 20f
    val rear = Offset(cx - 32f, cy + 24f)
    val front = Offset(cx + 32f, cy + 24f)
    drawCircle(wheel, r, rear, style = Stroke(8f))
    drawCircle(wheel, r, front, style = Stroke(8f))
    // boczne kółko
    drawCircle(wheel, 9f, Offset(rear.x + 14f, cy + 36f), style = Stroke(5f))

    val bb = Offset(cx, cy + 28f)
    val seat = Offset(cx - 18f, cy - 8f)
    val head = Offset(cx + 24f, cy - 10f)
    val lw = 7f
    drawLine(frame, rear, bb, lw)
    drawLine(frame, bb, seat, lw)
    drawLine(frame, seat, rear, lw)
    drawLine(frame, seat, head, lw)
    drawLine(frame, head, bb, lw)
    drawLine(frame, head, front, lw)
    // siodełko i wysoka kierownica
    drawRoundRect(frame, Offset(seat.x - 12f, seat.y - 8f), Size(24f, 8f), CornerRadius(4f, 4f))
    drawLine(frame, head, Offset(head.x + 2f, head.y - 22f), 6f)
    drawLine(frame, Offset(head.x - 8f, head.y - 22f), Offset(head.x + 12f, head.y - 22f), 6f)
    // chorągiewka na wysokim maszcie
    drawLine(frame, rear, Offset(rear.x - 12f, cy - 46f), 4f)
    val flag = androidx.compose.ui.graphics.Path().apply {
        moveTo(rear.x - 12f, cy - 46f)
        lineTo(rear.x - 40f, cy - 38f)
        lineTo(rear.x - 12f, cy - 30f)
        close()
    }
    drawPath(flag, Color(0xFFEC407A))
}

/** MTB: grube opony, płaska kierownica, amortyzowany widelec. */
private fun DrawScope.drawMtbBike(cx: Float, cy: Float, frame: Color, wheel: Color) {
    val r = 30f
    val rear = Offset(cx - 48f, cy + 14f)
    val front = Offset(cx + 48f, cy + 14f)
    // gruby bieżnik terenowy
    val knobs = Stroke(13f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f)))
    drawCircle(wheel, r, rear, style = knobs)
    drawCircle(wheel, r, front, style = knobs)
    drawCircle(frame, 5f, rear)
    drawCircle(frame, 5f, front)

    val bb = Offset(cx - 2f, cy + 20f)
    val seat = Offset(cx - 26f, cy - 20f)
    val head = Offset(cx + 36f, cy - 26f)
    val lw = 8f
    drawLine(frame, rear, bb, lw)
    drawLine(frame, bb, seat, lw)
    drawLine(frame, seat, rear, lw)
    drawLine(frame, seat, head, lw)  // sloping top tube
    drawLine(frame, head, bb, lw)
    // amortyzowany widelec: gruba golenia z jaśniejszym rdzeniem
    drawLine(frame, head, front, 12f)
    drawLine(wheel, Offset(head.x + 3f, head.y + 10f), front, 4f)
    // siodełko i płaska kierownica
    drawRoundRect(frame, Offset(seat.x - 14f, seat.y - 9f), Size(28f, 9f), CornerRadius(4f, 4f))
    drawLine(frame, head, Offset(head.x + 2f, head.y - 14f), lw)
    drawLine(frame, Offset(head.x - 14f, head.y - 16f), Offset(head.x + 18f, head.y - 16f), 7f)
}

/** Rower miejski: wyprostowana pozycja, błotniki, cofnięta kierownica, koszyk. */
private fun DrawScope.drawCityBike(cx: Float, cy: Float, frame: Color, wheel: Color) {
    val r = 28f
    val rear = Offset(cx - 46f, cy + 16f)
    val front = Offset(cx + 46f, cy + 16f)
    drawCircle(wheel, r, rear, style = Stroke(7f))
    drawCircle(wheel, r, front, style = Stroke(7f))
    drawCircle(frame, 5f, rear)
    drawCircle(frame, 5f, front)
    // błotniki
    listOf(rear, front).forEach { c ->
        drawArc(
            color = frame,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(c.x - r - 7f, c.y - r - 7f),
            size = Size((r + 7f) * 2f, (r + 7f) * 2f),
            style = Stroke(5f),
        )
    }

    val bb = Offset(cx - 4f, cy + 22f)
    val seat = Offset(cx - 26f, cy - 30f)
    val head = Offset(cx + 32f, cy - 26f)
    val lw = 7f
    drawLine(frame, rear, bb, lw)
    drawLine(frame, bb, seat, lw)
    drawLine(frame, seat, rear, lw)
    // rama damka: wygięta dolna rura zamiast górnej
    drawLine(frame, head, Offset(cx - 12f, cy + 10f), lw)
    drawLine(frame, Offset(cx - 12f, cy + 10f), bb, lw)
    drawLine(frame, head, front, lw)
    // szerokie siodełko, cofnięta kierownica, koszyk
    drawRoundRect(frame, Offset(seat.x - 17f, seat.y - 10f), Size(34f, 10f), CornerRadius(5f, 5f))
    drawLine(frame, head, Offset(head.x, head.y - 20f), 6f)
    drawLine(frame, Offset(head.x, head.y - 20f), Offset(head.x - 18f, head.y - 26f), 6f)
    drawRoundRect(
        wheel,
        Offset(front.x - 10f, head.y - 14f),
        Size(26f, 20f),
        CornerRadius(4f, 4f),
        style = Stroke(5f),
    )
}
