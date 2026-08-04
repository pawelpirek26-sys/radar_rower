package com.radarrower.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radarrower.core.RadarRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ekran debug: log surowych pakietów hex z charakterystyki radarowej +
 * wstępna interpretacja parsera. Służy do weryfikacji formatu na realnym W100
 * zanim parser zostanie uznany za ostateczny.
 */
@Composable
fun DebugScreen() {
    val log by RadarRepository.debugLog.collectAsStateWithLifecycle()
    val paused by RadarRepository.debugPaused.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    LaunchedEffect(log.size) {
        if (!paused && log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
        Text("Debug — surowe pakiety", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
        Text(
            "Weryfikacja formatu W100: trójki [ID, dystans m, prędkość km/h] po bajcie licznika.",
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { RadarRepository.setDebugPaused(!paused) }) {
                Text(if (paused) "Wznów" else "Pauza")
            }
            Button(onClick = { RadarRepository.clearDebugLog() }) {
                Text("Wyczyść")
            }
            Button(onClick = {
                val text = log.joinToString("\n") { p ->
                    "${timeFormat.format(Date(p.timestampMs))}  ${p.hex}"
                }
                clipboard.setText(AnnotatedString(text))
            }) {
                Text("Kopiuj")
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(log) { packet ->
                Column(modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(
                        text = "${timeFormat.format(Date(packet.timestampMs))}  ${packet.hex}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                    packet.parsed?.let { frame ->
                        if (frame.targets.isNotEmpty()) {
                            Text(
                                text = "  cnt=${frame.counter} " + frame.targets.joinToString(" ") {
                                    "[id=${it.id} ${it.distanceM}m ${it.speedKmh}km/h]"
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
        }
    }
}
