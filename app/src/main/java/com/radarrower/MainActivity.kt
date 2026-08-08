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
import android.widget.Toast
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.radarrower.core.AlertEvent
import com.radarrower.core.AlertPlayer
import com.radarrower.core.DemoSimulator
import com.radarrower.core.Permissions
import com.radarrower.core.RadarRepository
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
            val themeSettings by SettingsRepository.get(this)
                .settings.collectAsStateWithLifecycle(initialValue = null)
            RadarRowerTheme(themeMode = themeSettings?.appTheme ?: "system") {
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
        var demoMode by remember { mutableStateOf(DemoSimulator.isRunning) }

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

        // ekran jazdy nad ekranem blokady (telefon na kierownicy, ekran się zablokował)
        LaunchedEffect(currentSettings.showOnLockScreen) {
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(currentSettings.showOnLockScreen)
                setTurnScreenOn(currentSettings.showOnLockScreen)
            }
        }

        // podtrzymanie ekranu wg trybu (keepOn i standby podtrzymują)
        LaunchedEffect(currentSettings.screenMode) {
            if (currentSettings.screenMode != "system") {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        // auto-start serwisu, gdy jest sparowany radar i komplet uprawnień
        LaunchedEffect(permissionsGranted, currentSettings.deviceMac) {
            if (permissionsGranted && currentSettings.deviceMac != null && !demoMode) {
                RadarService.start(context)
            }
        }

        testPlayer.settings = currentSettings

        // globalna ochrona przed przypadkowym wyjściem — działa na KAŻDYM ekranie
        // głównym (jazda, skaner, onboarding); podekrany rejestrują własne
        // BackHandlery później, więc mają pierwszeństwo i cofają do środka apki
        var lastBackMs by remember { mutableStateOf(0L) }
        androidx.activity.compose.BackHandler {
            val now = System.currentTimeMillis()
            if (now - lastBackMs < 2_000) {
                finish()
            } else {
                lastBackMs = now
                Toast.makeText(
                    context,
                    "Naciśnij ponownie, aby wyjść (radar działa dalej w tle)",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

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

            // debug otwierany z Ustawień — cofnięcie wraca do Ustawień
            screen == Screen.DEBUG -> BackHandlerTo({ screen = Screen.SETTINGS }) { DebugScreen() }

            screen == Screen.SETTINGS -> BackHandlerTo({ screen = Screen.RIDE }) {
                SettingsScreen(
                    settings = currentSettings,
                    batteryOptimized = batteryOptimized,
                    onScreenMode = { v -> scope.launch { settingsRepo.setScreenMode(v) } },
                    onAppTheme = { v -> scope.launch { settingsRepo.setAppTheme(v) } },
                    onRiderStyle = { v -> scope.launch { settingsRepo.setRiderStyle(v) } },
                    onRedThreshold = { v -> scope.launch { settingsRepo.setRedThreshold(v) } },
                    onSoundEnabled = { v -> scope.launch { settingsRepo.setSoundEnabled(v) } },
                    onUseAlarmStream = { v -> scope.launch { settingsRepo.setUseAlarmStream(v) } },
                    onIndependentVolume = { v -> scope.launch { settingsRepo.setIndependentVolume(v) } },
                    onVolume = { v -> scope.launch { settingsRepo.setVolume(v) } },
                    onVibration = { v -> scope.launch { settingsRepo.setVibrationEnabled(v) } },
                    onProgressiveAlerts = { v -> scope.launch { settingsRepo.setProgressiveAlerts(v) } },
                    onNoiseFilter = { v -> scope.launch { settingsRepo.setNoiseFilter(v) } },
                    onShowOnLockScreen = { v -> scope.launch { settingsRepo.setShowOnLockScreen(v) } },
                    onSniffExtraServices = { v -> scope.launch { settingsRepo.setSniffExtraServices(v) } },
                    onResetStats = { RadarRepository.resetRideStats() },
                    onPlayOnHeadphones = { v -> scope.launch { settingsRepo.setPlayOnHeadphones(v) } },
                    onSoundTheme = { v ->
                        scope.launch { settingsRepo.setSoundTheme(v) }
                        // natychmiastowy odsłuch wybranego brzmienia
                        testPlayer.settings = currentSettings.copy(soundTheme = v)
                        testPlayer.play(AlertEvent.NEW_VEHICLE)
                    },
                    onUrgentVolume = { v -> scope.launch { settingsRepo.setUrgentVolume(v) } },
                    demoMode = demoMode,
                    onDemoMode = { on ->
                        demoMode = on
                        if (on) {
                            // demo zastępuje prawdziwy serwis — nie mieszać źródeł pakietów
                            RadarService.stop(context)
                            DemoSimulator.start(lifecycleScope)
                            screen = Screen.RIDE
                        } else {
                            DemoSimulator.stop()
                            if (currentSettings.deviceMac != null) RadarService.start(context)
                        }
                    },
                    onTestSound = { testPlayer.play(AlertEvent.NEW_VEHICLE) },
                    onTestUrgent = { testPlayer.play(AlertEvent.URGENT) },
                    onForgetDevice = {
                        scope.launch {
                            RadarService.stop(context)
                            settingsRepo.forgetDevice()
                            screen = Screen.RIDE
                        }
                    },
                    onRequestIgnoreBattery = { requestIgnoreBatteryOptimizations() },
                    onOpenAppSettings = {
                        runCatching {
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.parse("package:$packageName"))
                            )
                        }
                    },
                    onOpenDebug = { screen = Screen.DEBUG },
                    onScanAgain = { screen = Screen.SCAN },
                    onReconnect = {
                        // ACTION_START zeruje backoff i wymusza świeże połączenie
                        RadarService.start(context)
                        Toast.makeText(context, "Restartuję połączenie z radarem…", Toast.LENGTH_SHORT).show()
                    },
                )
            }

            // ustawienia (wyżej) dostępne też bez sparowanego radaru — testy dźwięku/baterii;
            // tryb demo pokazuje ekran jazdy nawet bez sparowanego urządzenia
            (currentSettings.deviceMac == null && !demoMode) || screen == Screen.SCAN -> {
                // skaner otwarty ręcznie (Zmień radar) cofa do jazdy;
                // skaner-korzeń (brak sparowania) łapie globalna ochrona wyjścia
                val scanIsRoot = currentSettings.deviceMac == null && !demoMode
                if (!scanIsRoot) {
                    androidx.activity.compose.BackHandler { screen = Screen.RIDE }
                }
                ScanScreen(
                    onOpenSettings = { screen = Screen.SETTINGS },
                ) { device ->
                    scope.launch {
                        settingsRepo.saveDevice(device.mac, device.name)
                        screen = Screen.RIDE
                    }
                }
            }

            else -> {
                RideScreen(
                    redThresholdKmh = currentSettings.redThresholdKmh,
                    riderStyle = currentSettings.riderStyle,
                    standbyEnabled = currentSettings.screenMode == "standby",
                    onToggleStandby = {
                        scope.launch {
                            settingsRepo.setScreenMode(
                                if (currentSettings.screenMode == "standby") "keepOn" else "standby"
                            )
                        }
                    },
                    onOpenSettings = { screen = Screen.SETTINGS },
                )
            }
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
            Text(
                stringResource(R.string.app_name_full),
                fontSize = 30.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Wyświetlacz radarów rowerowych. Dwa kroki i jedziemy:",
                fontSize = 16.sp,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            // status per uprawnienie — widać, co system faktycznie przyznał
            val nearbyOk = Permissions.hasNearby(this@MainActivity)
            val notifOk = Permissions.hasNotifications(this@MainActivity)
            OnboardingStep(
                done = permissionsGranted,
                title = "1. Uprawnienia",
                description = (if (nearbyOk) "✓" else "✗") +
                    " Urządzenia w pobliżu — skanowanie i łączenie z radarem\n" +
                    (if (notifOk) "✓" else "✗") +
                    " Powiadomienia — stały status połączenia\n" +
                    "System zapyta o zgodę po naciśnięciu przycisku.",
                buttonLabel = "Przyznaj uprawnienia",
                onClick = { launcher.launch(Permissions.required()) },
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

    private fun hasAllPermissions(): Boolean = Permissions.hasAll(this)

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
