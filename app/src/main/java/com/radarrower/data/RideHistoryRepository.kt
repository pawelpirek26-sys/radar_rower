package com.radarrower.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.radarrower.core.RideStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.historyDataStore by preferencesDataStore(name = "ride_history")

/** Podsumowanie jednego przejazdu. */
data class RideRecord(
    val startedAtMs: Long,
    val durationMs: Long,
    val vehicles: Int,
    val closestPassM: Int?,
    val maxClosingKmh: Int,
)

/**
 * Historia przejazdów (funkcja Pro).
 *
 * Zapis w JSON-ie wewnątrz DataStore zamiast bazy: rekord ma pięć pól, a wpisów
 * są dziesiątki — Room byłby tu narzutem bez zysku. Gdyby doszły trasy GPS albo
 * wykresy, wtedy migracja do Room ma sens.
 *
 * Historia jest ZBIERANA także w wersji Free — płatna jest tylko możliwość jej
 * przeglądania. Dzięki temu po zakupie użytkownik od razu widzi swoje przejazdy,
 * zamiast zaczynać od pustego ekranu.
 */
class RideHistoryRepository(private val context: Context) {

    private object Keys {
        val RIDES = stringPreferencesKey("rides_json")
    }

    val rides: Flow<List<RideRecord>> = context.historyDataStore.data.map { prefs ->
        parse(prefs[Keys.RIDES] ?: "[]")
    }

    suspend fun add(stats: RideStats, startedAtMs: Long, durationMs: Long) {
        // przejazd bez ani jednego pojazdu nie niesie informacji — nie zaśmiecamy listy
        if (stats.vehicles <= 0) return
        context.historyDataStore.edit { prefs ->
            val current = parse(prefs[Keys.RIDES] ?: "[]")
            val updated = (
                listOf(
                    RideRecord(
                        startedAtMs = startedAtMs,
                        durationMs = durationMs,
                        vehicles = stats.vehicles,
                        closestPassM = stats.closestPassM,
                        maxClosingKmh = stats.maxClosingKmh,
                    )
                ) + current
                ).take(MAX_RIDES)
            prefs[Keys.RIDES] = serialize(updated)
        }
    }

    suspend fun clear() {
        context.historyDataStore.edit { it[Keys.RIDES] = "[]" }
    }

    private fun parse(json: String): List<RideRecord> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            RideRecord(
                startedAtMs = o.getLong("start"),
                durationMs = o.getLong("duration"),
                vehicles = o.getInt("vehicles"),
                closestPassM = if (o.isNull("closest")) null else o.getInt("closest"),
                maxClosingKmh = o.getInt("maxClosing"),
            )
        }
    }.getOrDefault(emptyList())

    private fun serialize(rides: List<RideRecord>): String {
        val array = JSONArray()
        rides.forEach { ride ->
            array.put(
                JSONObject().apply {
                    put("start", ride.startedAtMs)
                    put("duration", ride.durationMs)
                    put("vehicles", ride.vehicles)
                    put("closest", ride.closestPassM ?: JSONObject.NULL)
                    put("maxClosing", ride.maxClosingKmh)
                }
            )
        }
        return array.toString()
    }

    companion object {
        /** Ile przejazdów trzymamy — starsze wypadają. */
        const val MAX_RIDES = 100

        @Volatile
        private var instance: RideHistoryRepository? = null

        fun get(context: Context): RideHistoryRepository =
            instance ?: synchronized(this) {
                instance ?: RideHistoryRepository(context.applicationContext).also { instance = it }
            }
    }
}
