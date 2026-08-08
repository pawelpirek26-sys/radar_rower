package com.radarrower.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Random

/**
 * Tryb demo: symuluje przejazdy aut BEZ radaru, fabrykując pakiety w formacie
 * Varia i wpuszczając je w normalny pipeline (RadarRepository.onRadarPacket).
 * Dzięki temu ekran jazdy, alerty dźwiękowe/wibracyjne i log Debug zachowują
 * się identycznie jak z prawdziwym urządzeniem.
 */
object DemoSimulator {

    private var job: Job? = null
    private val rnd = Random()

    val isRunning: Boolean
        get() = job?.isActive == true

    fun start(scope: CoroutineScope) {
        if (isRunning) return
        job = scope.launch {
            // demo fabrykuje pakiety w formacie Varia — parser musi być zgodny
            RadarRepository.setProtocol(com.radarrower.ble.RadarProtocol.VARIA)
            RadarRepository.setConnectionState(ConnectionState.CONNECTED, "DEMO")
            RadarRepository.setBatteryLevel(87)

            data class Car(val id: Int, var distM: Double, val speedKmh: Int)

            val cars = mutableListOf<Car>()
            var counter = 0
            var nextId = 1
            var pauseMs = 1_500L // pierwsza przerwa krótka — szybko coś widać

            while (isActive) {
                delay(500)

                if (cars.isEmpty()) {
                    pauseMs -= 500
                    if (pauseMs <= 0) {
                        // co ~4. auto jest szybkie (czerwony alert)
                        val fast = rnd.nextInt(4) == 0
                        val speed = if (fast) 65 + rnd.nextInt(25) else 28 + rnd.nextInt(17)
                        cars += Car(nextId++ and 0xFF, 130.0 + rnd.nextInt(15), speed)
                        // czasem kolumna dwóch aut
                        if (rnd.nextInt(3) == 0) {
                            cars += Car(nextId++ and 0xFF, 165.0, 30 + rnd.nextInt(15))
                        }
                        pauseMs = 3_000L + rnd.nextInt(5) * 1_000L
                    }
                }

                // przybliżanie: prędkość względem roweru (~18 km/h), tick 0,5 s
                val iter = cars.iterator()
                while (iter.hasNext()) {
                    val c = iter.next()
                    c.distM -= (c.speedKmh - 18).coerceAtLeast(6) / 3.6 * 0.5
                    if (c.distM < 2) iter.remove()
                }

                // pakiet jak z radaru: [licznik][id, dystans, prędkość]×N
                val visible = cars.filter { it.distM <= 140 }
                val packet = ByteArray(1 + visible.size * 3)
                packet[0] = (counter++ and 0xFF).toByte()
                visible.forEachIndexed { i, c ->
                    packet[1 + i * 3] = c.id.toByte()
                    packet[2 + i * 3] = c.distM.toInt().coerceIn(0, 255).toByte()
                    packet[3 + i * 3] = c.speedKmh.coerceIn(0, 255).toByte()
                }
                RadarRepository.onRadarPacket(packet)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        RadarRepository.setConnectionState(ConnectionState.DISCONNECTED)
    }
}
