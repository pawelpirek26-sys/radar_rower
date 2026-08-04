package com.radarrower.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radarrower.data.AppSettings
import kotlin.math.roundToInt

/**
 * Ustawienia: próg czerwonego alertu, dźwięki (strumień, głośność), wibracje,
 * keep screen on, zarządzanie sparowanym urządzeniem i optymalizacją baterii.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    batteryOptimized: Boolean,
    onKeepScreenOn: (Boolean) -> Unit,
    onRedThreshold: (Int) -> Unit,
    onSoundEnabled: (Boolean) -> Unit,
    onUseAlarmStream: (Boolean) -> Unit,
    onIndependentVolume: (Boolean) -> Unit,
    onVolume: (Float) -> Unit,
    onVibration: (Boolean) -> Unit,
    onTestSound: () -> Unit,
    onForgetDevice: () -> Unit,
    onRequestIgnoreBattery: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Ustawienia", fontSize = 26.sp, color = MaterialTheme.colorScheme.primary)

        Section("Ekran")
        SwitchRow("Nie wygaszaj ekranu podczas jazdy", settings.keepScreenOn, onKeepScreenOn)

        Section("Alert czerwony")
        Text(
            "Próg prędkości auta: ${settings.redThresholdKmh} km/h",
            fontSize = 16.sp,
        )
        Slider(
            value = settings.redThresholdKmh.toFloat(),
            onValueChange = { onRedThreshold(it.roundToInt()) },
            valueRange = 20f..120f,
            steps = 19,
        )

        Section("Dźwięk")
        SwitchRow("Dźwięki alertów", settings.soundEnabled, onSoundEnabled)
        SwitchRow(
            "Strumień alarmu (niezależny od głośności mediów)",
            settings.useAlarmStream,
            onUseAlarmStream,
        )
        SwitchRow("Własna głośność aplikacji", settings.independentVolume, onIndependentVolume)
        if (settings.independentVolume) {
            Text("Głośność: ${(settings.volume * 100).roundToInt()}%", fontSize = 16.sp)
            Slider(
                value = settings.volume,
                onValueChange = onVolume,
                valueRange = 0.1f..1f,
            )
        }
        Button(onClick = onTestSound, modifier = Modifier.padding(top = 4.dp)) {
            Text("Testuj dźwięk")
        }

        Section("Wibracje")
        SwitchRow("Wibracje przy alertach", settings.vibrationEnabled, onVibration)

        Section("Bateria")
        if (batteryOptimized) {
            Text(
                "System może ubijać połączenie w tle. Wyłącz optymalizację baterii dla RadarRower.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onRequestIgnoreBattery, modifier = Modifier.padding(top = 8.dp)) {
                Text("Wyłącz optymalizację baterii")
            }
        } else {
            Text("Optymalizacja baterii wyłączona — OK ✓", fontSize = 14.sp)
        }

        Section("Urządzenie")
        Text(
            settings.deviceMac?.let { "Sparowany radar: ${settings.deviceName ?: "?"} ($it)" }
                ?: "Brak sparowanego radaru",
            fontSize = 14.sp,
        )
        if (settings.deviceMac != null) {
            Button(onClick = onForgetDevice, modifier = Modifier.padding(top = 8.dp)) {
                Text("Zapomnij urządzenie")
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
    Text(title, fontSize = 18.sp, color = MaterialTheme.colorScheme.secondary)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
