package com.radarrower.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radarrower.ble.RadarScanner
import com.radarrower.core.ConnectionState
import com.radarrower.core.Permissions
import com.radarrower.core.RadarRepository
import com.radarrower.data.AppSettings
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ustawienia — sekcje w kolejności „od radaru do ciekawostek":
 * Radar / Ekran / Twój rower / Alerty / Bateria telefonu / Demo / O aplikacji.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    batteryOptimized: Boolean,
    demoMode: Boolean,
    onScreenMode: (String) -> Unit,
    onRiderStyle: (String) -> Unit,
    onRedThreshold: (Int) -> Unit,
    onSoundEnabled: (Boolean) -> Unit,
    onSoundTheme: (String) -> Unit,
    onUseAlarmStream: (Boolean) -> Unit,
    onIndependentVolume: (Boolean) -> Unit,
    onVolume: (Float) -> Unit,
    onUrgentVolume: (Float) -> Unit,
    onPlayOnHeadphones: (Boolean) -> Unit,
    onTestSound: () -> Unit,
    onTestUrgent: () -> Unit,
    onVibration: (Boolean) -> Unit,
    onDemoMode: (Boolean) -> Unit,
    onRequestIgnoreBattery: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onScanAgain: () -> Unit,
    onReconnect: () -> Unit,
    onForgetDevice: () -> Unit,
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

        // ------------------------------------------------ RADAR
        Section("Radar")
        if (settings.deviceMac == null) {
            Text("Brak sparowanego radaru.", fontSize = 15.sp)
            Button(onClick = onScanAgain, modifier = Modifier.padding(top = 8.dp)) {
                Text("Szukaj radaru")
            }
        } else {
            Text(
                "${settings.deviceName ?: "Radar"} (${settings.deviceMac})",
                fontSize = 15.sp,
            )
            Text(
                "Stan: " + when (connection) {
                    ConnectionState.CONNECTED -> "połączono ✓" +
                        (battery?.let { " · bateria $it%" } ?: "")
                    ConnectionState.CONNECTING -> "łączenie…"
                    ConnectionState.RECONNECTING -> "ponawianie połączenia…"
                    ConnectionState.SCANNING -> "szukanie…"
                    ConnectionState.DISCONNECTED -> "rozłączono"
                    ConnectionState.INCOMPATIBLE -> "urządzenie niezgodne (brak serwisu radaru)"
                },
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(
                onClick = onReconnect,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text("Restartuj połączenie")
            }
            Row(modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = onScanAgain) {
                    Text("Zmień radar")
                }
                OutlinedButton(
                    onClick = onForgetDevice,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text("Zapomnij")
                }
            }
        }

        // ------------------------------------------------ UPRAWNIENIA
        Section("Uprawnienia")
        val context = LocalContext.current
        val nearbyOk = Permissions.hasNearby(context)
        val notifOk = Permissions.hasNotifications(context)
        PermissionRow(
            ok = nearbyOk,
            label = "Urządzenia w pobliżu (Bluetooth)",
            onClick = onOpenAppSettings,
        )
        PermissionRow(
            ok = notifOk,
            label = "Powiadomienia",
            onClick = onOpenAppSettings,
        )
        PermissionRow(
            ok = !batteryOptimized,
            label = "Bez optymalizacji baterii (praca w tle)",
            onClick = onRequestIgnoreBattery,
        )
        if (!nearbyOk || !notifOk) {
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { }
            Button(
                onClick = { launcher.launch(Permissions.required()) },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Przyznaj brakujące")
            }
        }
        // realny test: czy skan BLE faktycznie startuje i coś widzi
        var scanTestResult by remember { mutableStateOf<String?>(null) }
        var scanTestRunning by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        OutlinedButton(
            onClick = {
                if (scanTestRunning) return@OutlinedButton
                scanTestRunning = true
                scanTestResult = "Skanuję przez 5 sekund…"
                scope.launch {
                    val found = mutableSetOf<String>()
                    val scanner = RadarScanner(context)
                    val started = scanner.start(allDevices = true) { found += it.mac }
                    if (!started) {
                        scanTestResult = "Skan NIE wystartował — sprawdź, czy Bluetooth jest " +
                            "włączony i czy przyznano „Urządzenia w pobliżu”."
                    } else {
                        delay(5_000)
                        scanner.stop()
                        scanTestResult = "Skanowanie działa ✓ — widzę ${found.size} " +
                            "urządzeń BLE w pobliżu."
                    }
                    scanTestRunning = false
                }
            },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(if (scanTestRunning) "Test w toku…" else "Test wyszukiwania (5 s)")
        }
        scanTestResult?.let {
            Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }

        // ------------------------------------------------ EKRAN
        Section("Ekran podczas jazdy")
        ChipRow(
            options = listOf(
                "system" to "Systemowy",
                "keepOn" to "Zawsze włączony",
                "standby" to "Czuwanie",
            ),
            selected = settings.screenMode,
            onSelect = onScreenMode,
        )
        Text(
            when (settings.screenMode) {
                "system" -> "Ekran gaśnie jak zwykle. Dźwięki i wibracje działają " +
                    "niezależnie — gra je serwis w tle."
                "standby" -> "Ekran nie gaśnie, ale przy pustej drodze robi się czarny " +
                    "(OLED oszczędza baterię) i budzi się sam, gdy radar wykryje auto. " +
                    "Dotknięcie też budzi."
                else -> "Ekran świeci przez całą jazdę — telefon na kierownicy."
            },
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp),
        )

        // ------------------------------------------------ ROWER
        Section("Twój rower na ekranie")
        ChipRow(
            options = listOf("gravel" to "Gravel", "road" to "Szosa", "mtb" to "MTB"),
            selected = settings.riderStyle,
            onSelect = onRiderStyle,
        )
        ChipRow(
            options = listOf("city" to "Miejski", "kids" to "Dziecinny"),
            selected = settings.riderStyle,
            onSelect = onRiderStyle,
        )

        // ------------------------------------------------ ALERTY
        Section("Alerty")
        Text(
            "Czerwony alert, gdy auto jedzie szybciej niż: ${settings.redThresholdKmh} km/h",
            fontSize = 15.sp,
        )
        Slider(
            value = settings.redThresholdKmh.toFloat(),
            onValueChange = { onRedThreshold(it.roundToInt()) },
            valueRange = 20f..120f,
            steps = 19,
        )
        SwitchRow("Dźwięki alertów", settings.soundEnabled, onSoundEnabled)
        if (settings.soundEnabled) {
            Text(
                "Brzmienie (tapnij = posłuchaj):",
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            ChipRow(
                options = listOf("beep" to "Beep", "horn" to "Klakson", "bell" to "Dzwonek"),
                selected = settings.soundTheme,
                onSelect = onSoundTheme,
            )
            SwitchRow(
                "Strumień alarmu (głośność mediów bez znaczenia)",
                settings.useAlarmStream,
                onUseAlarmStream,
            )
            SwitchRow("Graj w słuchawkach, jeśli podłączone", settings.playOnHeadphones, onPlayOnHeadphones)
            SwitchRow("Własna głośność aplikacji", settings.independentVolume, onIndependentVolume)
            if (settings.independentVolume) {
                Text("Zwykłe alerty: ${(settings.volume * 100).roundToInt()}%", fontSize = 14.sp)
                Slider(
                    value = settings.volume,
                    onValueChange = onVolume,
                    valueRange = 0.05f..1f,
                )
                Text(
                    "Czerwony alert: ${(settings.urgentVolume * 100).roundToInt()}%",
                    fontSize = 14.sp,
                )
                Slider(
                    value = settings.urgentVolume,
                    onValueChange = onUrgentVolume,
                    valueRange = 0.05f..1f,
                )
            }
            Row {
                OutlinedButton(onClick = onTestSound) {
                    Text("Testuj zwykły")
                }
                OutlinedButton(
                    onClick = onTestUrgent,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text("Testuj czerwony")
                }
            }
        }
        SwitchRow("Wibracje", settings.vibrationEnabled, onVibration)

        // ------------------------------------------------ DEMO
        Section("Demo")
        SwitchRow("Symulacja przejazdów aut (bez radaru)", demoMode, onDemoMode)
        if (demoMode) {
            Text(
                "Ekran jazdy, alerty i log Debug działają na sztucznych pakietach " +
                    "w formacie radaru. Wyłącz przed prawdziwą jazdą.",
                fontSize = 13.sp,
            )
        }

        // ------------------------------------------------ O APLIKACJI
        Section("O aplikacji")
        Text(
            "RadarRower $versionName — wyświetlacz radarów rowerowych zgodnych " +
                "z protokołem Garmin Varia (W100, Varia, Gardia, Magene i inne).",
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

@Composable
private fun PermissionRow(ok: Boolean, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (if (ok) "✓ " else "✗ ") + label,
            fontSize = 15.sp,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        Text("zmień ›", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ChipRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(modifier = Modifier.padding(top = 4.dp)) {
        options.forEach { (id, label) ->
            FilterChip(
                selected = selected == id,
                onClick = { onSelect(id) },
                label = { Text(label) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}
