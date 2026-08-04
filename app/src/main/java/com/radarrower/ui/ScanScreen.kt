package com.radarrower.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radarrower.ble.FoundDevice
import com.radarrower.ble.RadarScanner

/**
 * Ekran parowania: skanuje urządzenia rozgłaszające serwis radarowy Varia
 * i pozwala wybrać radar (zapis MAC w DataStore robi MainActivity).
 */
@Composable
fun ScanScreen(
    onOpenSettings: () -> Unit,
    onDeviceSelected: (FoundDevice) -> Unit,
) {
    val context = LocalContext.current
    val devices = remember { mutableStateMapOf<String, FoundDevice>() }
    var scanning by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val scanner = RadarScanner(context)
        scanning = scanner.start { device -> devices[device.mac] = device }
        onDispose { scanner.stop() }
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
        if (!scanning) {
            Text(
                "Nie udało się wystartować skanowania — sprawdź, czy Bluetooth jest włączony.",
                color = MaterialTheme.colorScheme.error,
            )
        } else if (devices.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.name ?: "Radar (bez nazwy)", fontSize = 20.sp)
                            Text(device.mac, fontSize = 13.sp)
                        }
                        Text("${device.rssi} dBm", fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
