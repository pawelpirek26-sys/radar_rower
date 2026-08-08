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
import kotlinx.coroutines.flow.update

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

    /** Tik alertu progresywnego — powtarzany tym gęściej, im auto bliżej. */
    PROXIMITY_TICK,

    /** Radar przestał odpowiadać w trakcie jazdy — cichy radar to fałszywe
     *  poczucie bezpieczeństwa, więc trzeba o tym powiedzieć na głos. */
    CONNECTION_LOST,
}

/** Statystyki przejazdu — zerowane przy starcie serwisu. */
data class RideStats(
    val vehicles: Int = 0,
    val closestPassM: Int? = null,
    val maxClosingKmh: Int = 0,
)

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

    private val _rideStats = MutableStateFlow(RideStats())
    val rideStats = _rideStats.asStateFlow()

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

    @Volatile
    private var noiseFilterEnabled = true

    /** Cele z poprzedniej ramki — do potwierdzania śladów (filtr szumu). */
    private var previousRaw = mapOf<Int, Int>()

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

    /** Pozwala serwisowi zagrać alert niezwiązany z pakietem (np. utrata radaru). */
    fun emitAlert(event: AlertEvent) {
        _alerts.tryEmit(event)
    }

    fun resetRideStats() {
        _rideStats.value = RideStats()
    }

    fun setNoiseFilter(enabled: Boolean) {
        noiseFilterEnabled = enabled
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

    /** Punkt odniesienia do liczenia prędkości ze zmiany dystansu. */
    private data class SpeedAnchor(val distanceM: Int, val timeMs: Long, val speedKmh: Int)

    private var speedAnchors = mutableMapOf<Int, SpeedAnchor>()

    /**
     * Wylicza **prędkość zbliżania** (o ile szybciej auto jedzie od Ciebie) —
     * jedna, spójna wielkość dla ekranu i dla progu czerwonego alertu.
     *
     * Świadoma decyzja: NIE używamy prędkości podawanej przez radar, bo to
     * prędkość auta względem drogi — mieszanie obu dawało raz jedną, raz drugą
     * wielkość, a różnica równa się prędkości roweru. Zbliżanie liczymy sami
     * z tempa spadku dystansu, więc znaczy zawsze to samo, na każdym radarze.
     *
     * Punkt odniesienia przesuwamy dopiero przy realnej zmianie dystansu —
     * radar aktualizuje go rzadziej (co ~250 ms) niż wysyła ramki (co ~80 ms).
     */
    private fun withClosingSpeed(targets: List<RadarTarget>, now: Long): List<RadarTarget> {
        val fresh = mutableMapOf<Int, SpeedAnchor>()
        val result = targets.map { target ->
            val anchor = speedAnchors[target.id]
            if (anchor == null) {
                // pierwsza ramka śladu — jeszcze nie ma z czego liczyć
                fresh[target.id] = SpeedAnchor(target.distanceM, now, 0)
                return@map target.copy(speedKmh = 0)
            }
            if (target.distanceM == anchor.distanceM) {
                fresh[target.id] = anchor
                return@map target.copy(speedKmh = anchor.speedKmh)
            }
            val seconds = (now - anchor.timeMs) / 1000.0
            val closedM = anchor.distanceM - target.distanceM
            val measured = when {
                closedM <= 0 -> 0 // auto się oddala — to nie zagrożenie
                seconds >= 0.15 -> (closedM / seconds * 3.6).toInt().coerceIn(0, 200)
                else -> anchor.speedKmh
            }
            // wygładzenie, żeby kwantyzacja dystansu co 3,125 m nie miotała odczytem
            val smoothed = if (anchor.speedKmh > 0) (measured + anchor.speedKmh) / 2 else measured
            fresh[target.id] = SpeedAnchor(target.distanceM, now, smoothed)
            target.copy(speedKmh = smoothed)
        }
        speedAnchors = fresh
        return result
    }

    /**
     * Filtr szumu: ślad musi pojawić się w DWÓCH kolejnych ramkach (na podobnym
     * dystansie), żeby trafić na ekran. Jednoramkowe migawki radaru — widoczne
     * w logach jako pola pojawiające się na moment — nie wywołają wtedy alertu.
     * Koszt: pierwsze wykrycie opóźnione o jedną ramkę (~80–250 ms).
     */
    private fun confirmed(list: List<RadarTarget>): List<RadarTarget> {
        if (!noiseFilterEnabled) {
            previousRaw = list.associate { it.id to it.distanceM }
            return list
        }
        val result = list.filter { target ->
            val before = previousRaw[target.id]
            before != null && kotlin.math.abs(before - target.distanceM) <= 12
        }
        previousRaw = list.associate { it.id to it.distanceM }
        return result
    }

    private fun updateTargets(rawList: List<RadarTarget>, now: Long) {
        val list = confirmed(rawList)
        val sorted = withClosingSpeed(list, now).sortedBy { it.distanceM }
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
            // statystyki przejazdu: licznik aut, najbliższe minięcie, max zbliżanie
            val nearest = sorted.first().distanceM
            val fastest = sorted.maxOf { it.speedKmh }
            _rideStats.update { stats ->
                stats.copy(
                    vehicles = stats.vehicles + newIds.size,
                    closestPassM = minOf(stats.closestPassM ?: Int.MAX_VALUE, nearest),
                    maxClosingKmh = maxOf(stats.maxClosingKmh, fastest),
                )
            }
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
        speedAnchors = mutableMapOf()
        previousRaw = emptyMap()
        lastFrame = null
        hadVehicles = false
        urgentActive = false
    }
}
