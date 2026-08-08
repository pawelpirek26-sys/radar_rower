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
import android.os.Handler
import android.os.Looper
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
        fun onConnected(deviceName: String?, protocol: RadarProtocol)
        fun onDisconnected()
        fun onPacket(data: ByteArray)

        /** Poziom baterii radaru w %, ze standardowego serwisu Battery (0x180F). */
        fun onBattery(levelPercent: Int)

        /**
         * Urządzenie po połączeniu NIE ma serwisu radarowego ANI żadnych
         * charakterystyk notyfikujących — nie ma czego słuchać.
         * [discoveredServices] — UUID-y usług, które faktycznie zgłosiło.
         */
        fun onIncompatible(discoveredServices: List<String>)

        /**
         * Tryb nasłuchu: brak serwisu Varia, ale urządzenie ma charakterystyki
         * notyfikujące — subskrybujemy wszystkie i zrzucamy pakiety do logu,
         * żeby odczytać niestandardowy protokół (klony typu W100/TUTULOO).
         */
        fun onSniffStart(discoveredServices: List<String>, characteristicCount: Int)

        /** Pakiet z nasłuchu — [charUuid] mówi, z której charakterystyki. */
        fun onSniffPacket(charUuid: String, data: ByteArray)
    }

    companion object {
        private const val TAG = "BleRadarClient"

        /** Serwis radarowy Varia. */
        val RADAR_SERVICE_UUID: UUID = UUID.fromString("6A4E3200-667B-11E3-949A-0800200C9A66")

        /** Charakterystyka danych radarowych (notyfikacje z celami). */
        val RADAR_DATA_UUID: UUID = UUID.fromString("6A4E3203-667B-11E3-949A-0800200C9A66")

        /** Standardowy deskryptor Client Characteristic Configuration. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Standardowy serwis Battery — wspólny dla radarów wszystkich producentów. */
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        /** Serwis własny radaru W100 (TUTULOO/MMWR) — nie mówi protokołem Varia. */
        val W100_SERVICE_UUID: UUID = UUID.fromString("aa86ffe0-3884-465c-a034-c242988b0000")

        /** Charakterystyka danych radarowych W100 (notyfikacje, ramki 8 B). */
        val W100_DATA_UUID: UUID = UUID.fromString("aa86ffe2-3884-465c-a034-c242988b0000")
    }

    private var gatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var closed = false

    @Volatile
    private var sniffing = false
    private val sniffQueue = ArrayDeque<BluetoothGattCharacteristic>()

    /** Protokół wykryty po odkryciu usług — decyduje, którym parserem czytać. */
    @Volatile
    private var protocol = RadarProtocol.VARIA

    /** UUID charakterystyki, z której płyną dane radarowe. */
    @Volatile
    private var dataUuid: UUID = RADAR_DATA_UUID

    /**
     * @param autoConnect false = szybka próba bezpośrednia (wymaga aktywnego
     *   advertisingu); true = tryb cierpliwy — stos czeka, aż urządzenie będzie
     *   osiągalne. Ważne przy radarze obsługującym RÓWNOLEGLE licznik i telefon:
     *   zajęty drugim centralem potrafi rozgłaszać się rzadko.
     */
    fun connect(mac: String, autoConnect: Boolean = false): Boolean {
        val adapter: BluetoothAdapter =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ?: return false
        if (!adapter.isEnabled) return false
        closed = false
        val device = runCatching { adapter.getRemoteDevice(mac) }.getOrNull() ?: return false
        gatt = device.connectGatt(
            context, autoConnect, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE
        )
        return gatt != null
    }

    fun disconnect() {
        closed = true
        sniffing = false
        sniffQueue.clear()
        runCatching {
            gatt?.disconnect()
            gatt?.close()
        }
        gatt = null
    }

    /** Odśwież poziom baterii (wołane okresowo przez serwis). */
    fun requestBatteryRead() {
        gatt?.let { readBattery(it) }
    }

    private fun readBattery(g: BluetoothGatt) {
        val ch = g.getService(BATTERY_SERVICE_UUID)?.getCharacteristic(BATTERY_LEVEL_UUID) ?: return
        runCatching { g.readCharacteristic(ch) }
    }

    /** Ukryta metoda czyszcząca cache GATT (standardowa praktyka, m.in. Nordic). */
    @Suppress("PrivateApi")
    private fun refreshGattCache(g: BluetoothGatt) {
        runCatching { g.javaClass.getMethod("refresh").invoke(g) }
    }

    /**
     * Włącza notyfikacje kolejnej charakterystyki z kolejki nasłuchu.
     * @return true = wysłano zapis CCCD (czekamy na onDescriptorWrite);
     *         false = kolejka pusta.
     */
    private fun enableNextSniff(g: BluetoothGatt): Boolean {
        while (true) {
            val ch = sniffQueue.removeFirstOrNull() ?: return false
            runCatching { g.setCharacteristicNotification(ch, true) }
            val descriptor = ch.getDescriptor(CCCD_UUID) ?: continue
            val value =
                if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }
            val started = if (Build.VERSION.SDK_INT >= 33) {
                runCatching {
                    g.writeDescriptor(descriptor, value) ==
                        android.bluetooth.BluetoothStatusCodes.SUCCESS
                }.getOrDefault(false)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                runCatching { g.writeDescriptor(descriptor) }.getOrDefault(false)
            }
            if (started) return true
        }
    }

    /** Skrócony zapis UUID: „2a19" dla standardowych, pierwsze 8 znaków dla własnych. */
    private fun shortUuid(uuid: UUID): String {
        val s = uuid.toString()
        return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) {
            s.substring(4, 8)
        } else {
            s.take(8)
        }
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Android CACHE'UJE listę usług per adres — jedno zepsute
                    // odkrycie (historyczny wyścig z requestMtu) potrafi wracać
                    // z cache'u przy każdej kolejnej próbie. Ukryte refresh()
                    // czyści cache przed odkrywaniem.
                    refreshGattCache(g)
                    // BEZ requestMtu: pakiet radarowy ≤19 B mieści się w domyślnym
                    // MTU, a druga równoległa operacja GATT bywała gubiona.
                    handler.postDelayed({
                        if (!closed) runCatching { g.discoverServices() }
                    }, 600)
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
            // najpierw Varia (Garmin i zgodne), potem własny protokół W100
            var characteristic = g.getService(RADAR_SERVICE_UUID)
                ?.getCharacteristic(RADAR_DATA_UUID)
            if (characteristic != null) {
                protocol = RadarProtocol.VARIA
                dataUuid = RADAR_DATA_UUID
            } else {
                characteristic = g.getService(W100_SERVICE_UUID)
                    ?.getCharacteristic(W100_DATA_UUID)
                if (characteristic != null) {
                    protocol = RadarProtocol.W100
                    dataUuid = W100_DATA_UUID
                }
            }
            if (characteristic == null) {
                val discovered = g.services.map { it.uuid.toString() }
                Log.w(TAG, "Brak serwisu radarowego; urządzenie zgłasza: $discovered")
                // fallback: nasłuch wszystkich charakterystyk notyfikujących —
                // klon z własnym protokołem i tak gdzieś nadaje dane radarowe
                val notifyChars = g.services.flatMap { it.characteristics }.filter {
                    it.properties and (
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                            BluetoothGattCharacteristic.PROPERTY_INDICATE
                        ) != 0
                }
                if (notifyChars.isEmpty()) {
                    closed = true
                    runCatching { g.disconnect(); g.close() }
                    gatt = null
                    listener.onIncompatible(discovered)
                } else {
                    sniffing = true
                    sniffQueue.clear()
                    sniffQueue.addAll(notifyChars)
                    listener.onSniffStart(discovered, notifyChars.size)
                    if (!enableNextSniff(g)) readBattery(g)
                }
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
            if (d.uuid != CCCD_UUID) return
            if (sniffing) {
                // niezależnie od statusu lecimy dalej po kolejce (operacje sekwencyjnie)
                if (!enableNextSniff(g)) readBattery(g)
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                listener.onConnected(runCatching { g.device.name }.getOrNull(), protocol)
                readBattery(g) // dopiero po CCCD — operacje GATT muszą iść sekwencyjnie
            } else {
                Log.w(TAG, "CCCD write failed: $status")
                g.disconnect()
            }
        }

        // API 33+: wartość odczytu podana wprost
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) handleRead(ch, value)
        }

        // API < 33
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val value = ch.value ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) handleRead(ch, value)
        }

        private fun handleRead(ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid == BATTERY_LEVEL_UUID && value.isNotEmpty()) {
                listener.onBattery(value[0].toInt() and 0xFF)
            }
        }

        // API 33+: wartość podana wprost
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(ch, value)
        }

        // API < 33: stary callback (na 33+ system woła nowy wariant)
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val value = ch.value ?: return
            handleNotification(ch, value)
        }

        private fun handleNotification(ch: BluetoothGattCharacteristic, value: ByteArray) {
            when {
                sniffing -> listener.onSniffPacket(shortUuid(ch.uuid), value)
                ch.uuid == dataUuid -> listener.onPacket(value)
            }
        }
    }
}
