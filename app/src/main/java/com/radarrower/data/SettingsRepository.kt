package com.radarrower.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val deviceMac: String?,
    val deviceName: String?,
    val keepScreenOn: Boolean,
    val redThresholdKmh: Int,
    val soundEnabled: Boolean,
    val useAlarmStream: Boolean,
    val independentVolume: Boolean,
    val volume: Float,
    val vibrationEnabled: Boolean,
    val playOnHeadphones: Boolean,
    val batteryPromptDismissed: Boolean,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEVICE_MAC = stringPreferencesKey("device_mac")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val RED_THRESHOLD = intPreferencesKey("red_threshold_kmh")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val USE_ALARM_STREAM = booleanPreferencesKey("use_alarm_stream")
        val INDEPENDENT_VOLUME = booleanPreferencesKey("independent_volume")
        val VOLUME = floatPreferencesKey("volume")
        val VIBRATION = booleanPreferencesKey("vibration_enabled")
        val PLAY_ON_HEADPHONES = booleanPreferencesKey("play_on_headphones")
        val BATTERY_PROMPT_DISMISSED = booleanPreferencesKey("battery_prompt_dismissed")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            deviceMac = p[Keys.DEVICE_MAC],
            deviceName = p[Keys.DEVICE_NAME],
            keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: true,
            redThresholdKmh = p[Keys.RED_THRESHOLD] ?: 50,
            soundEnabled = p[Keys.SOUND_ENABLED] ?: true,
            useAlarmStream = p[Keys.USE_ALARM_STREAM] ?: true,
            independentVolume = p[Keys.INDEPENDENT_VOLUME] ?: true,
            volume = p[Keys.VOLUME] ?: 0.9f,
            vibrationEnabled = p[Keys.VIBRATION] ?: true,
            playOnHeadphones = p[Keys.PLAY_ON_HEADPHONES] ?: true,
            batteryPromptDismissed = p[Keys.BATTERY_PROMPT_DISMISSED] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun saveDevice(mac: String, name: String?) {
        context.dataStore.edit {
            it[Keys.DEVICE_MAC] = mac
            if (name != null) it[Keys.DEVICE_NAME] = name else it.remove(Keys.DEVICE_NAME)
        }
    }

    suspend fun forgetDevice() {
        context.dataStore.edit {
            it.remove(Keys.DEVICE_MAC)
            it.remove(Keys.DEVICE_NAME)
        }
    }

    suspend fun setKeepScreenOn(value: Boolean) =
        context.dataStore.edit { it[Keys.KEEP_SCREEN_ON] = value }

    suspend fun setRedThreshold(kmh: Int) =
        context.dataStore.edit { it[Keys.RED_THRESHOLD] = kmh }

    suspend fun setSoundEnabled(value: Boolean) =
        context.dataStore.edit { it[Keys.SOUND_ENABLED] = value }

    suspend fun setUseAlarmStream(value: Boolean) =
        context.dataStore.edit { it[Keys.USE_ALARM_STREAM] = value }

    suspend fun setIndependentVolume(value: Boolean) =
        context.dataStore.edit { it[Keys.INDEPENDENT_VOLUME] = value }

    suspend fun setVolume(value: Float) =
        context.dataStore.edit { it[Keys.VOLUME] = value }

    suspend fun setVibrationEnabled(value: Boolean) =
        context.dataStore.edit { it[Keys.VIBRATION] = value }

    suspend fun setPlayOnHeadphones(value: Boolean) =
        context.dataStore.edit { it[Keys.PLAY_ON_HEADPHONES] = value }

    suspend fun setBatteryPromptDismissed(value: Boolean) =
        context.dataStore.edit { it[Keys.BATTERY_PROMPT_DISMISSED] = value }

    companion object {
        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
