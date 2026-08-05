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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radarrower.core.ConnectionState
import com.radarrower.core.RadarRepository
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
    onStandbyEnabled: (Boolean) -> Unit,
    onRedThreshold: (Int) -> Unit,
    onSoundEnabled: (Boolean) -> Unit,
    onUseAlarmStream: (Boolean) -> Unit,
    onIndependentVolume: (Boolean) -> Unit,
    onVolume: (Float) -> Unit,
    onVibration: (Boolean) -> Unit,
    onPlayOnHeadphones: (Boolean) -> Unit,
    onSoundTheme: (String) -> Unit,
    onUrgentVolume: (Float) -> Unit,
    demoMode: Boolean,
    onDemoMode: (Boolean) -> Unit,
    onTestSound: () -> Unit,
    onTestUrgent: () -> Unit,
    onForgetDevice: () -> Unit,
    onRequestIgnoreBattery: () -> Unit,
    onScanAgain: () -> Unit,
) {
    val connection by RadarRepository.connectionState.collectAsStateWithLifecycle()
    val battery by RadarRepository.batteryLevel.collectAsStateWithLifecycle()
    val versionName = LocalContext.current.let { ctx ->
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
            .getOrNull() ?: "?"
    }
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
        SwitchRow("Czuwanie: czarny ekran, budzi się przy aucie", settings.standbyEnabled, onStandbyEnabled)
        if (settings.standbyEnabled) {
            Text(
                "Przy pustej drodze ekran gaśnie na czarno (OLED oszczędza baterię) " +
                    "i zapala się sam, gdy radar wykryje pojazd. Dotknięcie też budzi.",
                fontSize = 13.sp,
            )
        }

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
        Text("Brzmienie alertu (tapnij = posłuchaj):", fontSize = 16.sp)
        Row(
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        ) {
            listOf("beep" to "Beep", "horn" to "Klakson", "bell" to "Dzwonek")
                .forEach { (id, label) ->
                    FilterChip(
                        selected = settings.soundTheme == id,
                        onClick = { onSoundTheme(id) },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
        }
        SwitchRow(
            "Strumień alarmu (niezależny od głośności mediów)",
            settings.useAlarmStream,
            onUseAlarmStream,
        )
        SwitchRow(
            "Graj w słuchawkach, jeśli podłączone",
            settings.playOnHeadphones,
            onPlayOnHeadphones,
        )
        if (!settings.playOnHeadphones) {
            Text(
                "Alerty zawsze z głośnika telefonu — nawet gdy słuchawki są podłączone.",
                fontSize = 13.sp,
            )
        }
        SwitchRow("Własna głośność aplikacji", settings.independentVolume, onIndependentVolume)
        if (settings.independentVolume) {
            Text("Głośność: ${(settings.volume * 100).roundToInt()}%", fontSize = 16.sp)
            Slider(
                value = settings.volume,
                onValueChange = onVolume,
                valueRange = 0.05f..1f,
            )
            Text(
                "Głośność czerwonego alertu: ${(settings.urgentVolume * 100).roundToInt()}%",
                fontSize = 16.sp,
            )
            Slider(
                value = settings.urgentVolume,
                onValueChange = onUrgentVolume,
                valueRange = 0.05f..1f,
            )
        }
        Row(modifier = Modifier.padding(top = 4.dp)) {
            Button(onClick = onTestSound) {
                Text("Testuj dźwięk")
            }
            Button(onClick = onTestUrgent, modifier = Modifier.padding(start = 8.dp)) {
                Text("Testuj czerwony")
            }
        }

        Section("Demo")
        SwitchRow("Symulacja przejazdów aut (bez radaru)", demoMode, onDemoMode)
        if (demoMode) {
            Text(
                "Ekran jazdy, alerty i log Debug działają na sztucznych pakietach " +
                    "w formacie radaru. Wyłącz przed prawdziwą jazdą.",
                fontSize = 13.sp,
            )
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
            Text(
                "Stan: " + when (connection) {
                    ConnectionState.CONNECTED -> "połączono ✓"
                    ConnectionState.CONNECTING -> "łączenie…"
                    ConnectionState.RECONNECTING -> "ponawianie połączenia…"
                    ConnectionState.SCANNING -> "szukanie…"
                    ConnectionState.DISCONNECTED -> "rozłączono"
                    ConnectionState.INCOMPATIBLE -> "urządzenie niezgodne (brak serwisu radaru)"
                },
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            battery?.let {
                Text("Bateria radaru: $it%", fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = onScanAgain) {
                    Text("Zmień radar")
                }
                Button(
                    onClick = onForgetDevice,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text("Zapomnij urządzenie")
                }
            }
        }

        Section("O aplikacji")
        Text(
            "RadarRower $versionName — wyświetlacz radaru rowerowego W100 " +
                "(BLE, protokół Garmin Varia).",
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 16.dp),
        )
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
