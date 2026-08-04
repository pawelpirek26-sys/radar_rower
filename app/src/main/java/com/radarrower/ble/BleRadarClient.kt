package com.radarrower.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.UUID

/**
 * Klient GATT radaru Varia/W100: łączy się z zapamiętanym adresem, włącza
 * notyfikacje charakterystyki danych radarowych i przekazuje surowe pakiety
 * wyżej. Reconnect (backoff) orkiestruje RadarService — klient tylko zgłasza
 * rozłączenie przez [Listener.onDisconnected].
 */
@SuppressLint("MissingPermission") // uprawnienia BLE wymusza UI przed startem serwisu
class BleRadarClient(
    private val context: Context,
    private val listener: Listener,
) {

    interface Listener {
        fun onConnected(deviceName: String?)
        fun onDisconnected()
        fun onPacket(data: ByteArray)
    }

    companion object {
        private const val TAG = "BleRadarClient"

        /** Serwis radarowy Varia. */
        val RADAR_SERVICE_UUID: UUID = UUID.fromString("6A4E3200-667B-11E3-949A-0800200C9A66")

        /** Charakterystyka danych radarowych (notyfikacje z celami). */
        val RADAR_DATA_UUID: UUID = UUID.fromString("6A4E3203-667B-11E3-949A-0800200C9A66")

        /** Standardowy deskryptor Client Characteristic Configuration. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var gatt: BluetoothGatt? = null

    @Volatile
    private var closed = false

    fun connect(mac: String): Boolean {
        val adapter: BluetoothAdapter =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ?: return false
        if (!adapter.isEnabled) return false
        closed = false
        val device = runCatching { adapter.getRemoteDevice(mac) }.getOrNull() ?: return false
        gatt = device.connectGatt(context, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        return gatt != null
    }

    fun disconnect() {
        closed = true
        runCatching {
            gatt?.disconnect()
            gatt?.close()
        }
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    g.requestMtu(64) // pakiet z 6 celami to 19 B, ale zapas nie szkodzi
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    runCatching { g.close() }
                    gatt = null
                    if (!closed) listener.onDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "discoverServices failed: $status")
                g.disconnect()
                return
            }
            val characteristic = g.getService(RADAR_SERVICE_UUID)
                ?.getCharacteristic(RADAR_DATA_UUID)
            if (characteristic == null) {
                Log.w(TAG, "Brak serwisu/charakterystyki radarowej na urządzeniu")
                g.disconnect()
                return
            }
            enableNotifications(g, characteristic)
        }

        private fun enableNotifications(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            g.setCharacteristicNotification(ch, true)
            val descriptor = ch.getDescriptor(CCCD_UUID) ?: run {
                Log.w(TAG, "Brak deskryptora CCCD")
                return
            }
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                listener.onConnected(runCatching { g.device.name }.getOrNull())
            } else if (d.uuid == CCCD_UUID) {
                Log.w(TAG, "CCCD write failed: $status")
                g.disconnect()
            }
        }

        // API 33+: wartość podana wprost
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (ch.uuid == RADAR_DATA_UUID) listener.onPacket(value)
        }

        // API < 33: stary callback (na 33+ system woła nowy wariant)
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val value = ch.value ?: return
            if (ch.uuid == RADAR_DATA_UUID) listener.onPacket(value)
        }
    }
}
