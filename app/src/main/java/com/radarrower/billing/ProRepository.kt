package com.radarrower.billing

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.proDataStore by preferencesDataStore(name = "pro")

/**
 * Stan wykupienia wersji Pro.
 *
 * Źródłem prawdy jest Google Play, ale odpowiedź z Play wymaga sieci, a rowerzysta
 * bywa poza zasięgiem. Dlatego ostatni znany stan trzymamy lokalnie i to on
 * decyduje o UI do czasu odpowiedzi z Play. Świadomy trade-off: lokalny cache
 * da się teoretycznie podrobić na zrootowanym telefonie — przy jednorazowym
 * zakupie wygody (nie funkcji bezpieczeństwa) walka z tym nie ma sensu
 * biznesowego, a psułaby działanie offline uczciwym użytkownikom.
 */
class ProRepository(private val context: Context) {

    private object Keys {
        val PRO_ACTIVE = booleanPreferencesKey("pro_active")
    }

    val isPro: Flow<Boolean> = context.proDataStore.data.map { it[Keys.PRO_ACTIVE] ?: false }

    suspend fun setPro(active: Boolean) {
        context.proDataStore.edit { it[Keys.PRO_ACTIVE] = active }
    }

    companion object {
        @Volatile
        private var instance: ProRepository? = null

        fun get(context: Context): ProRepository =
            instance ?: synchronized(this) {
                instance ?: ProRepository(context.applicationContext).also { instance = it }
            }
    }
}
