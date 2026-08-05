package com.radarrower.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid

/** Znalezione urządzenie BLE — kandydat na radar. */
data class FoundDevice(
    val mac: String,
    val name: String?,
    val rssi: Int,
    /** Urządzenie rozgłasza serwis radarowy Varia — pewna zgodność. */
    val hasRadarService: Boolean,
    /** Nazwa pasuje do znanego radaru (część radarów nie rozgłasza serwisu). */
    val nameLooksLikeRadar: Boolean,
)

/**
 * Skaner BLE do ekranu parowania. Skanuje BEZ filtra systemowego i klasyfikuje
 * wyniki w kodzie: serwis radarowy Varia > znana nazwa radaru > reszta.
 * Dzięki temu widać też radary, które nie umieszczają serwisu w advertisingu
 * (niektóre klony) — w trybie domyślnym pokazywane po nazwie, a w trybie
 * „wszystkie urządzenia" widać całe otoczenie BLE.
 */
@SuppressLint("MissingPermission") // uprawnienia wymusza UI przed startem skanu
class RadarScanner(private val context: Context) {

    companion object {
        private val RADAR_SERVICE = ParcelUuid(BleRadarClient.RADAR_SERVICE_UUID)

        // znane rodziny radarów zgodnych z protokołem Varia
        private val KNOWN_NAMES = listOf(
            "varia", "rtl", "rvr", "rct", // Garmin
            "gardia", // Bryton
            "l508", "magene", // Magene
            "sr30", "igpsport", // iGPSPORT
            "carback", // Trek
            "w100",
            "radar",
        )

        fun looksLikeRadar(name: String?): Boolean {
            val n = name?.lowercase() ?: return false
            return KNOWN_NAMES.any { n.contains(it) }
        }
    }

    private var callback: ScanCallback? = null

    /**
     * @param allDevices false = pokazuj tylko urządzenia z serwisem radarowym
     *   lub znaną nazwą; true = pokazuj wszystko (nietypowe klony).
     */
    fun start(allDevices: Boolean, onDevice: (FoundDevice) -> Unit): Boolean {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: return false
        if (!adapter.isEnabled) return false
        val scanner = adapter.bluetoothLeScanner ?: return false

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = runCatching { result.device.name }.getOrNull()
                    ?: result.scanRecord?.deviceName
                val hasService =
                    result.scanRecord?.serviceUuids?.contains(RADAR_SERVICE) == true
                val nameMatch = looksLikeRadar(name)
                if (!allDevices && !hasService && !nameMatch) return
                onDevice(
                    FoundDevice(
                        mac = result.device.address,
                        name = name,
                        rssi = result.rssi,
                        hasRadarService = hasService,
                        nameLooksLikeRadar = nameMatch,
                    )
                )
            }
        }
        callback = cb
        return runCatching { scanner.startScan(null, settings, cb) }.isSuccess
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
    }
}
