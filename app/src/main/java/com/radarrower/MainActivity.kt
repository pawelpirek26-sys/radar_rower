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

        when {
            !permissionsGranted -> PermissionScreen(
                batteryOptimized = batteryOptimized,
                onRequestBattery = { requestIgnoreBatteryOptimizations() },
            ) { permissionsGranted = hasAllPermissions() }

            currentSettings.deviceMac == null || screen == Screen.SCAN -> ScanScreen { device ->
                scope.launch {
                    settingsRepo.saveDevice(device.mac, device.name)
                    screen = Screen.RIDE
                }
            }

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
                    onTestSound = { testPlayer.play(AlertEvent.NEW_VEHICLE) },
                    onForgetDevice = {
                        scope.launch {
                            RadarService.stop(context)
                            settingsRepo.forgetDevice()
                            screen = Screen.RIDE
                        }
                    },
                    onRequestIgnoreBattery = { requestIgnoreBatteryOptimizations() },
                )
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
        batteryOptimized: Boolean,
        onRequestBattery: () -> Unit,
        onGranted: () -> Unit,
    ) {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { onGranted() }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("RadarRower", fontSize = 34.sp, color = MaterialTheme.colorScheme.primary)
            Text(
                "Do działania radaru potrzebne są uprawnienia Bluetooth " +
                    "oraz powiadomień (stały status połączenia).",
                fontSize = 16.sp,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            Button(
                onClick = { launcher.launch(requiredPermissions()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Przyznaj uprawnienia", fontSize = 18.sp)
            }
            if (batteryOptimized) {
                Text(
                    "Zalecane: wyłącz optymalizację baterii, żeby system nie zrywał " +
                        "połączenia przy zgaszonym ekranie.",
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                Button(onClick = onRequestBattery, modifier = Modifier.fillMaxWidth()) {
                    Text("Wyłącz optymalizację baterii")
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
