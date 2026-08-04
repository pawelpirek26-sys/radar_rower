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

    override fun onConnected(deviceName: String?) {
        reconnectAttempt = 0
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
            val mac = SettingsRepository.get(this@RadarService).current().deviceMac
            if (mac == null) {
                RadarRepository.setConnectionState(ConnectionState.DISCONNECTED)
                return@launch
            }
            RadarRepository.setConnectionState(
                if (initial) ConnectionState.CONNECTING else ConnectionState.RECONNECTING
            )
            client?.disconnect()
            client = BleRadarClient(this@RadarService, this@RadarService)
            val ok = client?.connect(mac) ?: false
            if (!ok && !stopping) scheduleReconnect()
        }
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
