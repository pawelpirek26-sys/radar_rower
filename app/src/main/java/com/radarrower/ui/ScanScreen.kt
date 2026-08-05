package com.radarrower.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radarrower.ble.FoundDevice
import com.radarrower.ble.RadarScanner
import kotlinx.coroutines.delay

/**
 * Ekran parowania: skanuje urządzenia rozgłaszające serwis radarowy Varia
 * i pozwala wybrać radar (zapis MAC w DataStore robi MainActivity).
 * Obsługuje wyłączony Bluetooth i podpowiada, gdy radar długo się nie pojawia.
 */
@Composable
fun ScanScreen(
    onOpenSettings: () -> Unit,
    onDeviceSelected: (FoundDevice) -> Unit,
) {
    val context = LocalContext.current
    val devices = remember { mutableStateMapOf<String, FoundDevice>() }
    var scanning by remember { mutableStateOf(false) }
    var btOff by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var searchingLong by remember { mutableStateOf(false) }

    DisposableEffect(attempt) {
        devices.clear()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        btOff = adapter?.isEnabled != true
        val scanner = RadarScanner(context)
        scanning = if (btOff) false else scanner.start { device -> devices[device.mac] = device }
        onDispose { scanner.stop() }
    }

    LaunchedEffect(attempt) {
        searchingLong = false
        delay(15_000)
        searchingLong = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Szukam radaru…",
                fontSize = 28.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Ustawienia")
            }
        }
        Text(
            "Obudź radar W100 (włącz go) i trzymaj blisko telefonu.",
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        when {
            btOff -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Bluetooth jest wyłączony.",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Włącz Bluetooth", fontSize = 17.sp) }
                OutlinedButton(
                    onClick = { attempt++ },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Sprawdź ponownie") }
            }

            !scanning -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Nie udało się wystartować skanowania.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                OutlinedButton(
                    onClick = { attempt++ },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Spróbuj ponownie") }
            }

            devices.isEmpty() -> Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PulsingRadarIcon()
                if (searchingLong) {
                    Card(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Radar się nie pojawia?", fontSize = 17.sp)
                            Text(
                                "• Upewnij się, że W100 jest włączony (dioda miga).\n" +
                                    "• Radar połączony z licznikiem/aplikacją Garmin może nie " +
                                    "rozgłaszać — rozłącz go tam.\n" +
                                    "• Podejdź bliżej i spróbuj ponownie.",
                                fontSize = 14.sp,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            OutlinedButton(
                                onClick = { attempt++ },
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            ) { Text("Skanuj od nowa") }
                        }
                    }
                }
            }

            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices.values.sortedByDescending { it.rssi }, key = { it.mac }) { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDeviceSelected(device) },
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Radar,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp),
                            ) {
                                Text(device.name ?: "Radar (bez nazwy)", fontSize = 20.sp)
                                Text(device.mac, fontSize = 13.sp)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${device.rssi} dBm", fontSize = 14.sp)
                                Text(signalLabel(device.rssi), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PulsingRadarIcon() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    Spacer(modifier = Modifier.height(32.dp))
    Icon(
        Icons.Filled.Radar,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(96.dp)
            .graphicsLayer { this.alpha = alpha },
    )
}

private fun signalLabel(rssi: Int): String = when {
    rssi > -60 -> "blisko"
    rssi > -80 -> "średnio"
    else -> "daleko"
}
