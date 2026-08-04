package com.radarrower.ble

/**
 * Pojedynczy cel (pojazd) zgłoszony przez radar.
 *
 * @param id       identyfikator celu nadawany przez radar (stały przez czas śledzenia)
 * @param distanceM dystans w metrach (uint8, zasięg radaru ~140 m)
 * @param speedKmh  prędkość celu w km/h (uint8)
 */
data class RadarTarget(
    val id: Int,
    val distanceM: Int,
    val speedKmh: Int,
)

/** Sparsowana ramka notyfikacji z charakterystyki danych radarowych. */
data class RadarFrame(
    val counter: Int,
    val targets: List<RadarTarget>,
)

/**
 * Parser pakietów radaru Varia / W100 (charakterystyka 6A4E3203-667B-11E3-949A-0800200C9A66).
 *
 * Format według otwartych implementacji protokołu (harbour-tacho / wątek
 * "Bluetooth profile for Garmin Varia RTL515" na forum Garmin — zweryfikowany
 * na realnym RTL515):
 *
 *   bajt 0            — licznik/identyfikator pakietu; górne 4 bity wspólne dla
 *                       części podzielonego payloadu (>6 celów nie mieści się w MTU 20 B)
 *   bajty 1+3i..3+3i  — trójka na cel: [ID celu, dystans w metrach, prędkość w km/h]
 *
 * UWAGA: format przyjęty dla RTL515; W100 ma być zgodny, ale przed finalizacją
 * zweryfikuj surowe pakiety na ekranie Debug z realnym urządzeniem.
 */
object VariaParser {

    fun parse(data: ByteArray): RadarFrame? {
        if (data.isEmpty()) return null
        val counter = data[0].toInt() and 0xFF
        val count = (data.size - 1) / 3
        val targets = ArrayList<RadarTarget>(count)
        for (i in 0 until count) {
            targets.add(
                RadarTarget(
                    id = data[1 + i * 3].toInt() and 0xFF,
                    distanceM = data[2 + i * 3].toInt() and 0xFF,
                    speedKmh = data[3 + i * 3].toInt() and 0xFF,
                )
            )
        }
        return RadarFrame(counter, targets)
    }

    /**
     * Czy [frame] jest kontynuacją [previous] (podzielony payload)?
     * Części podzielonego pakietu niosą te same górne 4 bity licznika.
     */
    fun isContinuation(previous: RadarFrame?, frame: RadarFrame): Boolean {
        previous ?: return false
        return (previous.counter and 0xF0) == (frame.counter and 0xF0) &&
            previous.counter != frame.counter
    }

    fun toHex(data: ByteArray): String =
        data.joinToString(" ") { "%02X".format(it) }
}
