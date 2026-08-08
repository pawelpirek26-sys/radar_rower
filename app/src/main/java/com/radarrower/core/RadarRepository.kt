package com.radarrower.core

import com.radarrower.ble.RadarFrame
import com.radarrower.ble.RadarProtocol
import com.radarrower.ble.RadarTarget
import com.radarrower.ble.VariaParser
import com.radarrower.ble.W100Parser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stan połączenia z radarem. */
enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    RECONNECTING,

    /** Sparowane urządzenie nie ma serwisu radarowego — trzeba wybrać inne. */
    INCOMPATIBLE,
}

/** Poziom zagrożenia — steruje kolorem UI i alertami. */
enum class ThreatLevel {
    CLEAR,   // zielony: pusto
    VEHICLE, // pomarańczowy: auto w zasięgu
    URGENT,  // czerwony: auto zbliża się szybko
}

/** Zdarzenia alertowe emitowane przez tracker do odtwarzacza dźwięków/wibracji. */
enum class AlertEvent {
    NEW_VEHICLE,
    URGENT,
    ALL_CLEAR,
}

/** Wpis logu surowych pakietów dla ekranu Debug. */
data class RawPacket(
    val timestampMs: Long,
    val hex: String,
    val parsed: RadarFrame?,
)

/**
 * Wspólny stan aplikacji — pojedynczy obiekt współdzielony przez serwis BLE i UI.
 * Serwis pisze, UI czyta (StateFlow), alerty idą SharedFlow.
 */
object RadarRepository {

    const val MAX_DEBUG_PACKETS = 1000

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName = _deviceName.asStateFlow()

    private val _targets = MutableStateFlow<List<RadarTarget>>(emptyList())
    val targets = _targets.asStateFlow()

    private val _threatLevel = MutableStateFlow(ThreatLevel.CLEAR)
    val threatLevel = _threatLevel.asStateFlow()

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel = _batteryLevel.asStateFlow()

    private val _alerts = MutableSharedFlow<AlertEvent>(extraBufferCapacity = 16)
    val alerts = _alerts.asSharedFlow()

    private val _debugLog = MutableStateFlow<List<RawPacket>>(emptyList())
    val debugLog = _debugLog.asStateFlow()

    private val _debugPaused = MutableStateFlow(false)
    val debugPaused = _debugPaused.asStateFlow()

    // --- stan wewnętrzny trackera ---
    private var knownIds = setOf<Int>()
    private var lastFrame: RadarFrame? = null
    private var lastNonEmptyMs = 0L
    private var hadVehicles = false
    private var urgentActive = false
    private var redThresholdKmh = 50

    @Volatile
    private var protocol = RadarProtocol.VARIA

    fun setProtocol(value: RadarProtocol) {
        protocol = value
    }

    fun setConnectionState(state: ConnectionState, name: String? = null) {
        _connectionState.value = state
        if (name != null) _deviceName.value = name
        if (state != ConnectionState.CONNECTED) {
            resetTargets()
            _batteryLevel.value = null
        }
    }

    fun setBatteryLevel(percent: Int) {
        _batteryLevel.value = percent.coerceIn(0, 100)
    }

    fun setRedThreshold(kmh: Int) {
        redThresholdKmh = kmh
    }

    fun setDebugPaused(paused: Boolean) {
        _debugPaused.value = paused
    }

    fun clearDebugLog() {
        _debugLog.value = emptyList()
    }

    /** Wpis diagnostyczny (nie-pakietowy) w logu Debug — np. lista usług GATT. */
    fun logDiagnostic(message: String) {
        val entry = RawPacket(System.currentTimeMillis(), message, null)
        _debugLog.value = (_debugLog.value + entry).takeLast(MAX_DEBUG_PACKETS)
    }

    private var lastSniffKey: String? = null
    private var lastSniffRepeat = 0

    /**
     * Pakiet z trybu nasłuchu — hex z etykietą charakterystyki źródłowej.
     * Identyczne kolejne ramki zwijane do jednego wpisu z licznikiem (×N),
     * żeby ramki spoczynkowe nie wypychały z bufora tych z pojazdami.
     */
    fun logSniffPacket(charUuid: String, data: ByteArray) {
        if (_debugPaused.value) return
        val hex = VariaParser.toHex(data)
        val key = "$charUuid|$hex"
        if (key == lastSniffKey) {
            lastSniffRepeat++
            val log = _debugLog.value
            if (log.isNotEmpty()) {
                val updated = log.last().copy(hex = "[$charUuid] $hex  (×${lastSniffRepeat + 1})")
                _debugLog.value = log.dropLast(1) + updated
            }
            return
        }
        lastSniffKey = key
        lastSniffRepeat = 0
        val entry = RawPacket(System.currentTimeMillis(), "[$charUuid] $hex", null)
        _debugLog.value = (_debugLog.value + entry).takeLast(MAX_DEBUG_PACKETS)
    }

    /** Wywoływane przez klienta BLE dla każdej notyfikacji charakterystyki radarowej. */
    fun onRadarPacket(data: ByteArray) {
        val now = System.currentTimeMillis()
        val frame = when (protocol) {
            RadarProtocol.W100 -> W100Parser.parse(data)
            RadarProtocol.VARIA -> VariaParser.parse(data)
        }

        if (!_debugPaused.value) {
            val entry = RawPacket(now, VariaParser.toHex(data), frame)
            _debugLog.value = (_debugLog.value + entry).takeLast(MAX_DEBUG_PACKETS)
        }

        frame ?: return

        // Podzielony payload: dwie części niosą ten sam górny półbajt licznika —
        // scal cele zamiast nadpisywać (inaczej >6 aut migotałoby listą).
        // dzielone payloady występują tylko w Varii — W100 wysyła kompletne ramki
        val merged = if (protocol == RadarProtocol.VARIA &&
            VariaParser.isContinuation(lastFrame, frame) &&
            now - lastNonEmptyMs < 500
        ) {
            val prevTargets = _targets.value
            val ids = frame.targets.map { it.id }.toSet()
            prevTargets.filter { it.id !in ids } + frame.targets
        } else {
            frame.targets
        }
        lastFrame = frame

        updateTargets(merged, now)
    }

    private fun updateTargets(list: List<RadarTarget>, now: Long) {
        val sorted = list.sortedBy { it.distanceM }
        _targets.value = sorted

        val ids = sorted.map { it.id }.toSet()
        val newIds = ids - knownIds
        knownIds = ids

        val urgent = sorted.any { it.speedKmh >= redThresholdKmh }
        _threatLevel.value = when {
            urgent -> ThreatLevel.URGENT
            sorted.isNotEmpty() -> ThreatLevel.VEHICLE
            else -> ThreatLevel.CLEAR
        }

        if (sorted.isNotEmpty()) {
            lastNonEmptyMs = now
            if (urgent && !urgentActive) {
                urgentActive = true
                _alerts.tryEmit(AlertEvent.URGENT)
            } else if (newIds.isNotEmpty()) {
                _alerts.tryEmit(AlertEvent.NEW_VEHICLE)
            }
            if (!urgent) urgentActive = false
            hadVehicles = true
        } else {
            urgentActive = false
            // Sygnał „czysto" dopiero gdy przez chwilę faktycznie pusto —
            // pojedyncza pusta ramka między podzielonymi payloadami to nie przejazd.
            if (hadVehicles && now - lastNonEmptyMs > 800) {
                hadVehicles = false
                _alerts.tryEmit(AlertEvent.ALL_CLEAR)
            }
        }
    }

    private fun resetTargets() {
        _targets.value = emptyList()
        _threatLevel.value = ThreatLevel.CLEAR
        knownIds = emptySet()
        lastFrame = null
        hadVehicles = false
        urgentActive = false
    }
}
