package com.radarrower.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid

/** Znalezione urządzenie rozgłaszające serwis radarowy. */
data class FoundDevice(
    val mac: String,
    val name: String?,
    val rssi: Int,
)

/**
 * Skaner BLE filtrowany po serwisie radarowym Varia — do ekranu parowania.
 */
@SuppressLint("MissingPermission") // uprawnienia wymusza UI przed startem skanu
class RadarScanner(private val context: Context) {

    private var callback: ScanCallback? = null

    fun start(onDevice: (FoundDevice) -> Unit): Boolean {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: return false
        if (!adapter.isEnabled) return false
        val scanner = adapter.bluetoothLeScanner ?: return false

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleRadarClient.RADAR_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onDevice(
                    FoundDevice(
                        mac = result.device.address,
                        name = runCatching { result.device.name }.getOrNull()
                            ?: result.scanRecord?.deviceName,
                        rssi = result.rssi,
                    )
                )
            }
        }
        callback = cb
        return runCatching { scanner.startScan(listOf(filter), settings, cb) }.isSuccess
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
    }
}
