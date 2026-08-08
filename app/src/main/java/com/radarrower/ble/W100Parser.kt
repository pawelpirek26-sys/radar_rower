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
 * **Jednostka dystansu: 3,125 m** — ta sama kwantyzacja co w ANT+ Varia.
 * Weryfikacja na trzech niezależnych przejazdach: dystans maleje liniowo,
 * a prędkość policzona z jego zmiany w czasie zgadza się z bajtem prędkości
 * (23→20 jednostek w 0,98 s = 34,6 km/h przy odczycie 33 km/h; 5→4 jednostki
 * w 0,24 s = 46 km/h przy odczycie 43 km/h).
 *
 * ⚠ Prędkość drugiego celu (b7) bywa też małą wartością wyglądającą na poziom
 * zagrożenia — dlatego przyjmujemy ją tylko, gdy mieści się w sensownym
 * zakresie prędkości pojazdu, inaczej dziedziczy prędkość celu pierwszego.
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
        if (b[0] != 0x30) return null

        val counter = b[3] and 0x3F // młodsze 6 bitów bajtu 3 to licznik ramek
        if (b[1] == 0x00) return RadarFrame(counter, emptyList()) // droga pusta

        val targets = ArrayList<RadarTarget>(3)

        // cel 1: 6-bitowy dystans rozrzucony między b4 (dolny nibble) i b3 (bity 6-7)
        val units1 = ((b[4] and 0x0F) shl 2) or ((b[3] shr 6) and 0x03)
        val speed1 = bcd(b[6]) ?: 0
        targets += RadarTarget(
            id = 1,
            distanceM = (units1 * DISTANCE_UNIT_M).toInt(),
            speedKmh = speed1,
        )

        // cel 2: górny nibble b4 + 2 młodsze bity b5 (pole 6-bitowe, jak przy celu 1)
        val units2 = ((b[5] and 0x03) shl 4) or ((b[4] shr 4) and 0x0F)
        if (units2 > 0) {
            val raw2 = bcd(b[7]) ?: 0
            targets += RadarTarget(
                id = 2,
                distanceM = (units2 * DISTANCE_UNIT_M).toInt(),
                // b7 bywa polem statusu — bierzemy je tylko jako sensowną prędkość
                speedKmh = if (raw2 >= MIN_PLAUSIBLE_SPEED_KMH) raw2 else speed1,
            )
        }

        // cel 3: górny nibble b5 (widziany przy ramkach z trzema pojazdami)
        val units3 = (b[5] shr 4) and 0x0F
        if (units3 > 0) {
            targets += RadarTarget(
                id = 3,
                distanceM = (units3 * DISTANCE_UNIT_M).toInt(),
                speedKmh = speed1,
            )
        }

        // odsiej cele poza fizycznym zasięgiem radaru — to zawsze błąd dekodowania,
        // a nie realny pojazd (lepiej nie pokazać nic niż bzdurę na ekranie jazdy)
        return RadarFrame(counter, targets.filter { it.distanceM <= MAX_RANGE_M })
    }
}
