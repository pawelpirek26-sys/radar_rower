package com.radarrower.ble

/** Protokół, którym mówi połączony radar. */
enum class RadarProtocol { VARIA, W100 }

/**
 * Parser protokołu radaru W100 (TUTULOO / MMWR) — własny serwis
 * `aa86ffe0-3884-465c-a034-c242988b0000`, notyfikacje z charakterystyki
 * `aa86ffe2-…`, ramka 8 bajtów co ~120–250 ms.
 *
 * Format zdekodowany z nasłuchu na realnym urządzeniu (2026-08-07, log
 * z przejazdów aut). Ramka:
 *
 * ```
 * b0 = 0x30            nagłówek
 * b1 = maska celów     0x00 = pusto (ramka spoczynkowa 30 00 55 41 10 04 00 00)
 * b2 = 0x00
 * b3 = licznik + 2 najmłodsze bity dystansu celu 1 (bity 6-7)
 * b4 = dolny nibble: bity 2-5 dystansu celu 1; górny nibble: młodszy nibble dystansu celu 2
 * b5 = dolny nibble: starszy nibble dystansu celu 2; górny nibble: dystans celu 3
 * b6 = prędkość celu 1 w BCD (0x33 = 33 km/h)
 * b7 = prędkość celu 2 w BCD (bywa też polem poziomu zagrożenia — patrz niżej)
 * ```
 *
 * **Jednostka dystansu: 3,125 m** (kwantyzacja jak w ANT+ Varia) — wartość
 * ROBOCZA, potwierdzona w 2 z 4 przeanalizowanych zdarzeń: tempo spadku pola
 * podzielone przez odczytaną prędkość daje 2,99 m i 2,89 m na jednostkę.
 * W dwóch pozostałych zdarzeniach wychodzi 2,0 m i 6,0 m — czyli albo bajt
 * prędkości nie zawsze opisuje ten sam cel, albo slot ma inną skalę.
 *
 * ⚠ **Do kalibracji pomiarem ze znanych odległości** (radar nieruchomy,
 * cel w 10/20/40 m). Do tego czasu: wykrycie pojazdu i licznik są pewne
 * (ramka spoczynkowa jest jednoznaczna), metry i km/h traktuj orientacyjnie.
 */
object W100Parser {

    /** Kwantyzacja dystansu (m) — zgodna z ANT+ Varia. */
    private const val DISTANCE_UNIT_M = 3.125

    /** Poniżej tej wartości bajt prędkości to najpewniej nie prędkość pojazdu. */
    private const val MIN_PLAUSIBLE_SPEED_KMH = 10

    /** Zasięg radaru z zapasem — dalsze „cele" to błąd dekodowania. */
    private const val MAX_RANGE_M = 160

    /** Dekoduje bajt zapisany dziesiętnie (BCD): 0x33 → 33. */
    private fun bcd(byte: Int): Int? {
        val hi = (byte shr 4) and 0x0F
        val lo = byte and 0x0F
        return if (hi <= 9 && lo <= 9) hi * 10 + lo else null
    }

    /** Czy ramka pochodzi z radaru W100 (nagłówek + długość). */
    fun looksLikeW100(data: ByteArray): Boolean =
        data.size == 8 && (data[0].toInt() and 0xFF) == 0x30

    fun parse(data: ByteArray): RadarFrame? {
        if (data.size < 8) return null
        val b = IntArray(8) { data[it].toInt() and 0xFF }
        // radar używa też nagłówka 0x31 (druga przeplatana seria ramek)
        if ((b[0] and 0xF0) != 0x30) return null

        if (b[1] == 0x00) return RadarFrame(b[0], emptyList()) // droga pusta

        val speed1 = bcd(b[6]) ?: 0
        val speed2 = bcd(b[7]) ?: 0

        // Radar śledzi kilka celów w osobnych polach bitowych. Które z nich niesie
        // ruch, zależy od slotu, w jakim radar umieścił dany pojazd — dlatego
        // czytamy WSZYSTKIE i pokazujemy te, które faktycznie coś zgłaszają.
        // (log z jazdy: raz porusza się pole A przy zerowym B, raz odwrotnie)
        val slots = listOf(
            ((b[4] and 0x0F) shl 2) or ((b[3] shr 6) and 0x03), // pole A
            b[3] and 0x3F,                                      // pole B
            ((b[5] and 0x03) shl 4) or ((b[4] shr 4) and 0x0F),  // pole C
        )

        val targets = ArrayList<RadarTarget>(3)
        slots.forEachIndexed { index, units ->
            if (units <= 0) return@forEachIndexed
            val distance = (units * DISTANCE_UNIT_M).toInt()
            if (distance > MAX_RANGE_M) return@forEachIndexed
            // nie dubluj tego samego pojazdu widzianego w dwóch polach
            if (targets.any { kotlin.math.abs(it.distanceM - distance) <= 3 }) {
                return@forEachIndexed
            }
            targets += RadarTarget(
                id = index + 1,
                distanceM = distance,
                // b7 bywa polem statusu — bierzemy je tylko jako sensowną prędkość
                speedKmh = if (index > 0 && speed2 >= MIN_PLAUSIBLE_SPEED_KMH) speed2 else speed1,
            )
        }

        return RadarFrame(b[0], targets)
    }
}
