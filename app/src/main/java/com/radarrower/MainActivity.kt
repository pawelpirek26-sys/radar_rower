package com.radarrower

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radarrower.core.AlertEvent
import com.radarrower.core.AlertPlayer
import com.radarrower.data.SettingsRepository
import com.radarrower.service.RadarService
import com.radarrower.ui.DebugScreen
import com.radarrower.ui.RadarRowerTheme
import com.radarrower.ui.RideScreen
import com.radarrower.ui.ScanScreen
import com.radarrower.ui.SettingsScreen
import kotlinx.coroutines.launch

private enum class Screen { RIDE, SCAN, DEBUG, SETTINGS }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RadarRowerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }

    @Composable
    private fun AppRoot() {
        val context = this
        val scope = rememberCoroutineScope()
        val settingsRepo = remember { SettingsRepository.get(context) }
        val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = null)
        val testPlayer = remember { AlertPlayer(context) }

        var permissionsGranted by remember { mutableStateOf(hasAllPermissions()) }
        var batteryOptimized by remember { mutableStateOf(isBatteryOptimized()) }
        var screen by remember { mutableStateOf(Screen.RIDE) }

        // odśwież stan uprawnień/baterii po powrocie z systemowych dialogów
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(lifecycleOwner) {
            lifecycleOwner.lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        permissionsGranted = hasAllPermissions()
                        batteryOptimized = isBatteryOptimized()
                    }
                }
            )
        }

        val currentSettings = settings ?: return

        // keep screen on wg ustawienia
        LaunchedEffect(currentSettings.keepScreenOn) {
            if (currentSettings.keepScreenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        // auto-start serwisu, gdy jest sparowany radar i komplet uprawnień
        LaunchedEffect(permissionsGranted, currentSettings.deviceMac) {
            if (permissionsGranted && currentSettings.deviceMac != null) {
                RadarService.start(context)
            }
        }

        testPlayer.settings = currentSettings

        // onboarding trzyma usera do skompletowania uprawnień; krok baterii można pominąć
        val onboardingNeeded = !permissionsGranted ||
            (batteryOptimized && !currentSettings.batteryPromptDismissed)

        when {
            onboardingNeeded -> PermissionScreen(
                permissionsGranted = permissionsGranted,
                batteryOptimized = batteryOptimized,
                onRequestBattery = { requestIgnoreBatteryOptimizations() },
                onSkipBattery = { scope.launch { settingsRepo.setBatteryPromptDismissed(true) } },
            ) { permissionsGranted = hasAllPermissions() }

            screen == Screen.DEBUG -> BackHandlerTo({ screen = Screen.RIDE }) { DebugScreen() }

            screen == Screen.SETTINGS -> BackHandlerTo({ screen = Screen.RIDE }) {
                SettingsScreen(
                    settings = currentSettings,
                    batteryOptimized = batteryOptimized,
                    onKeepScreenOn = { v -> scope.launch { settingsRepo.setKeepScreenOn(v) } },
                    onRedThreshold = { v -> scope.launch { settingsRepo.setRedThreshold(v) } },
                    onSoundEnabled = { v -> scope.launch { settingsRepo.setSoundEnabled(v) } },
                    onUseAlarmStream = { v -> scope.launch { settingsRepo.setUseAlarmStream(v) } },
                    onIndependentVolume = { v -> scope.launch { settingsRepo.setIndependentVolume(v) } },
                    onVolume = { v -> scope.launch { settingsRepo.setVolume(v) } },
                    onVibration = { v -> scope.launch { settingsRepo.setVibrationEnabled(v) } },
                    onPlayOnHeadphones = { v -> scope.launch { settingsRepo.setPlayOnHeadphones(v) } },
                    onSoundTheme = { v ->
                        scope.launch { settingsRepo.setSoundTheme(v) }
                        // natychmiastowy odsłuch wybranego brzmienia
                        testPlayer.settings = currentSettings.copy(soundTheme = v)
                        testPlayer.play(AlertEvent.NEW_VEHICLE)
                    },
                    onTestSound = { testPlayer.play(AlertEvent.NEW_VEHICLE) },
                    onForgetDevice = {
                        scope.launch {
                            RadarService.stop(context)
                            settingsRepo.forgetDevice()
                            screen = Screen.RIDE
                        }
                    },
                    onRequestIgnoreBattery = { requestIgnoreBatteryOptimizations() },
                    onScanAgain = { screen = Screen.SCAN },
                )
            }

            // ustawienia (wyżej) dostępne też bez sparowanego radaru — testy dźwięku/baterii
            currentSettings.deviceMac == null || screen == Screen.SCAN -> ScanScreen(
                onOpenSettings = { screen = Screen.SETTINGS },
            ) { device ->
                scope.launch {
                    settingsRepo.saveDevice(device.mac, device.name)
                    screen = Screen.RIDE
                }
            }

            else -> RideScreen(
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenDebug = { screen = Screen.DEBUG },
            )
        }
    }

    @Composable
    private fun BackHandlerTo(onBack: () -> Unit, content: @Composable () -> Unit) {
        androidx.activity.compose.BackHandler(onBack = onBack)
        content()
    }

    @Composable
    private fun PermissionScreen(
        permissionsGranted: Boolean,
        batteryOptimized: Boolean,
        onRequestBattery: () -> Unit,
        onSkipBattery: () -> Unit,
        onGranted: () -> Unit,
    ) {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { onGranted() }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("RadarRower", fontSize = 34.sp, color = MaterialTheme.colorScheme.primary)
            Text(
                "Wyświetlacz radaru rowerowego W100. Dwa kroki i jedziemy:",
                fontSize = 16.sp,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            OnboardingStep(
                done = permissionsGranted,
                title = "1. Uprawnienia Bluetooth i powiadomień",
                description = "Skanowanie i łączenie z radarem oraz stały status połączenia.",
                buttonLabel = "Przyznaj uprawnienia",
                onClick = { launcher.launch(requiredPermissions()) },
            )
            OnboardingStep(
                done = !batteryOptimized,
                title = "2. Optymalizacja baterii wyłączona",
                description = "Bez tego system może zrywać połączenie przy zgaszonym ekranie " +
                    "i telefonie w kieszeni.",
                buttonLabel = "Wyłącz optymalizację",
                onClick = onRequestBattery,
            )
            if (permissionsGranted && batteryOptimized) {
                androidx.compose.material3.TextButton(
                    onClick = onSkipBattery,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("Pomiń — zaryzykuję zrywanie połączenia")
                }
            }
        }
    }

    @Composable
    private fun OnboardingStep(
        done: Boolean,
        title: String,
        description: String,
        buttonLabel: String,
        onClick: () -> Unit,
    ) {
        androidx.compose.material3.Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (done) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                    Text(
                        title,
                        fontSize = 17.sp,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                if (!done) {
                    Text(
                        description,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(
                        onClick = onClick,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    ) {
                        Text(buttonLabel, fontSize = 16.sp)
                    }
                }
            }
        }
    }

    // --- uprawnienia / bateria ---

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.toTypedArray()
    }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun isBatteryOptimized(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }
}
