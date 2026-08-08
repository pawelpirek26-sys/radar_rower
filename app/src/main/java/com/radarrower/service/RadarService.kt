package com.radarrower.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.radarrower.MainActivity
import com.radarrower.R
import com.radarrower.RadarApp
import com.radarrower.ble.BleRadarClient
import com.radarrower.core.AlertPlayer
import com.radarrower.core.ConnectionState
import com.radarrower.core.RadarRepository
import com.radarrower.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Serwis foreground (typ connectedDevice) utrzymujący połączenie BLE z radarem
 * także przy zgaszonym ekranie. Trzyma partial wake lock (radar to bezpieczeństwo —
 * parser i alerty muszą chodzić z telefonem w kieszeni), auto-reconnect z backoffem
 * 1→30 s, persistent notification ze statusem.
 */
class RadarService : Service(), BleRadarClient.Listener {

    companion object {
        const val ACTION_START = "com.radarrower.START"
        const val ACTION_STOP = "com.radarrower.STOP"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, RadarService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RadarService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var client: BleRadarClient? = null
    private var alertPlayer: AlertPlayer? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var incompatibleCount = 0
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alertPlayer = AlertPlayer(this)

        // obserwuj ustawienia (próg czerwonego, dźwięki/wibracje/strumień)
        scope.launch {
            SettingsRepository.get(this@RadarService).settings.collect { s ->
                RadarRepository.setRedThreshold(s.redThresholdKmh)
                alertPlayer?.settings = s
            }
        }
        // graj alerty
        scope.launch {
            RadarRepository.alerts.collect { alertPlayer?.play(it) }
        }
        // aktualizuj notyfikację przy zmianie stanu/celów
        scope.launch {
            RadarRepository.connectionState.collect { updateNotification() }
        }
        scope.launch {
            RadarRepository.targets.collect { updateNotification() }
        }
        // okresowe odświeżanie poziomu baterii radaru
        scope.launch {
            while (true) {
                delay(10 * 60 * 1000)
                if (RadarRepository.connectionState.value == ConnectionState.CONNECTED) {
                    client?.requestBatteryRead()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopping = true
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startInForeground()
                acquireWakeLock()
                // ponowny ACTION_START (np. „Restartuj połączenie" z Ustawień)
                // zeruje backoff i zrywa bieżące połączenie na rzecz świeżego
                reconnectJob?.cancel()
                reconnectAttempt = 0
                incompatibleCount = 0
                connectSaved(initial = true)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        reconnectJob?.cancel()
        client?.disconnect()
        client = null
        RadarRepository.setConnectionState(ConnectionState.DISCONNECTED)
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    // --- BleRadarClient.Listener ---

    override fun onConnected(deviceName: String?, protocol: com.radarrower.ble.RadarProtocol) {
        reconnectAttempt = 0
        incompatibleCount = 0
        RadarRepository.setProtocol(protocol)
        RadarRepository.logDiagnostic("DIAG: połączono, protokół = $protocol")
        RadarRepository.setConnectionState(ConnectionState.CONNECTED, deviceName)
    }

    override fun onDisconnected() {
        if (stopping) return
        RadarRepository.setConnectionState(ConnectionState.RECONNECTING)
        scheduleReconnect()
    }

    override fun onPacket(data: ByteArray) {
        RadarRepository.onRadarPacket(data)
    }

    override fun onBattery(levelPercent: Int) {
        RadarRepository.setBatteryLevel(levelPercent)
    }

    override fun onSniffStart(discoveredServices: List<String>, characteristicCount: Int) {
        if (stopping) return
        reconnectAttempt = 0
        incompatibleCount = 0
        RadarRepository.logDiagnostic(
            "DIAG: brak serwisu Varia — TRYB NASŁUCHU ($characteristicCount charakterystyk). " +
                "Usługi: " + discoveredServices.joinToString("; ")
        )
        scope.launch {
            val name = SettingsRepository.get(this@RadarService).current().deviceName
            RadarRepository.setConnectionState(
                ConnectionState.CONNECTED,
                (name ?: "Radar") + " · nasłuch",
            )
        }
    }

    override fun onSniffPacket(charUuid: String, data: ByteArray) {
        RadarRepository.logSniffPacket(charUuid, data)
    }

    override fun onIncompatible(discoveredServices: List<String>) {
        if (stopping) return
        // diagnostyka do ekranu Debug: co urządzenie NAPRAWDĘ wystawia
        RadarRepository.logDiagnostic(
            "DIAG: brak serwisu radaru. Usługi urządzenia (${discoveredServices.size}): " +
                (discoveredServices.joinToString("; ").ifEmpty { "PUSTA LISTA" })
        )
        // przy słabym sygnale odkrywanie usług potrafi zwrócić niepełną listę —
        // trzy próby zanim ogłosimy, że to naprawdę nie radar
        incompatibleCount++
        if (incompatibleCount >= 3) {
            RadarRepository.setConnectionState(ConnectionState.INCOMPATIBLE)
        } else {
            RadarRepository.setConnectionState(ConnectionState.RECONNECTING)
            scheduleReconnect()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadarRower:ble").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    // --- łączenie / reconnect ---

    private fun connectSaved(initial: Boolean) {
        scope.launch {
            val settings = SettingsRepository.get(this@RadarService).current()
            var mac = settings.deviceMac
            if (mac == null) {
                RadarRepository.setConnectionState(ConnectionState.DISCONNECTED)
                return@launch
            }
            RadarRepository.setConnectionState(
                if (initial) ConnectionState.CONNECTING else ConnectionState.RECONNECTING
            )

            // po serii nieudanych prób poszukaj radaru skanem — urządzenia
            // z adresem prywatnym (RPA) potrafią go rotować i zapisany MAC
            // przestaje istnieć; nazwa radaru jest stała
            if (reconnectAttempt >= 3) {
                findRadarByScan(settings.deviceName, mac)?.let { freshMac ->
                    if (freshMac != mac) {
                        RadarRepository.logDiagnostic("DIAG: radar znaleziony pod nowym adresem $freshMac (było $mac)")
                        SettingsRepository.get(this@RadarService)
                            .saveDevice(freshMac, settings.deviceName)
                        mac = freshMac
                    }
                }
            }

            client?.disconnect()
            client = BleRadarClient(this@RadarService, this@RadarService)
            // od 2. próby tryb cierpliwy — radar zajęty licznikiem rozgłasza się rzadko
            val ok = client?.connect(mac!!, autoConnect = reconnectAttempt >= 2) ?: false
            if (!ok && !stopping) scheduleReconnect()
        }
    }

    /**
     * Krótki skan w poszukiwaniu zapisanego radaru: dopasowanie po starym MAC
     * albo po dokładnej nazwie (celowo NIE po samym serwisie radarowym —
     * w peletonie złapalibyśmy cudzy radar).
     */
    private suspend fun findRadarByScan(name: String?, oldMac: String): String? {
        val scanner = com.radarrower.ble.RadarScanner(this)
        var found: String? = null
        val started = scanner.start(allDevices = false) { d ->
            if (d.mac == oldMac || (name != null && d.name == name)) found = d.mac
        }
        if (!started) return null
        var waited = 0L
        while (found == null && waited < 12_000) {
            delay(500)
            waited += 500
        }
        scanner.stop()
        return found
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val delayS = min(30L, 1L shl min(reconnectAttempt, 5)) // 1,2,4,8,16,30 s
            reconnectAttempt++
            delay(delayS * 1000)
            if (!stopping) connectSaved(initial = false)
        }
    }

    // --- notyfikacja / foreground ---

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification() {
        if (stopping) return
        getSystemService(android.app.NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val state = RadarRepository.connectionState.value
        val targets = RadarRepository.targets.value
        val text = when (state) {
            ConnectionState.CONNECTED ->
                if (targets.isEmpty()) "Połączono — droga czysta"
                else "Połączono — ${liczbaAut(targets.size)} z tyłu"
            ConnectionState.CONNECTING -> "Łączenie z radarem…"
            ConnectionState.RECONNECTING -> "Zerwane połączenie — ponawiam…"
            ConnectionState.SCANNING -> "Szukanie radaru…"
            ConnectionState.DISCONNECTED -> "Rozłączono"
            ConnectionState.INCOMPATIBLE -> "Urządzenie nie jest radarem — wybierz inne"
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RadarService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, RadarApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_radar_notif)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Zatrzymaj", stopIntent)
            .build()
    }

    private fun liczbaAut(n: Int): String = when {
        n == 1 -> "1 auto"
        n in 2..4 -> "$n auta"
        else -> "$n aut"
    }
}
