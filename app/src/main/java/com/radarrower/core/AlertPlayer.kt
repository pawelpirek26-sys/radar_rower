package com.radarrower.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.radarrower.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Odtwarzacz alertów: dźwięki syntezowane programowo (AudioTrack, bez plików audio)
 * + wibracje. Strumień wyjściowy konfigurowalny: USAGE_ALARM (niezależny od
 * głośności mediów) lub USAGE_MEDIA. Działa przy zgaszonym ekranie — wołany
 * z serwisu foreground.
 *
 * Słuchawki: gdy podłączone są słuchawki (BT/przewodowe/USB) i user tego chce
 * ([AppSettings.playOnHeadphones]), alert jest kierowany wprost do nich przez
 * setPreferredDevice — także przy strumieniu ALARM, który na części telefonów
 * domyślnie gra tylko z głośnika. Bezczynne słuchawki BT budzą się ułamek
 * sekundy, więc przed tonem dokładana jest cisza rozbiegowa (inaczej początek
 * beepu byłby ucięty). Przy wyłączonym przełączniku alert gra ZAWSZE
 * z głośnika telefonu, nawet gdy słuchawki są podłączone.
 */
class AlertPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Default)

    private val headsetTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLE_HEADSET, // stała inline'owana — bezpieczna też na API <31
    )

    @Volatile
    var settings: AppSettings? = null

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    fun play(event: AlertEvent) {
        val s = settings ?: return
        if (s.soundEnabled) {
            scope.launch {
                when (event) {
                    // nowy pojazd: podwójny średni beep
                    AlertEvent.NEW_VEHICLE -> playTone(
                        s, floatArrayOf(880f, 0f, 880f), longArrayOf(120, 70, 120)
                    )
                    // czerwony: potrójny szybki wysoki beep — pilniejszy
                    AlertEvent.URGENT -> playTone(
                        s, floatArrayOf(1400f, 0f, 1400f, 0f, 1400f),
                        longArrayOf(90, 50, 90, 50, 180)
                    )
                    // czysto: łagodny akord wznoszący
                    AlertEvent.ALL_CLEAR -> playTone(
                        s, floatArrayOf(660f, 990f), longArrayOf(140, 220)
                    )
                }
            }
        }
        if (s.vibrationEnabled) vibrate(event)
    }

    private fun vibrate(event: AlertEvent) {
        val v = vibrator ?: return
        val effect = when (event) {
            AlertEvent.NEW_VEHICLE -> VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1)
            AlertEvent.URGENT -> VibrationEffect.createWaveform(longArrayOf(0, 90, 50, 90, 50, 250), -1)
            AlertEvent.ALL_CLEAR -> VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        runCatching { v.vibrate(effect) }
    }

    /**
     * Syntezuje sekwencję tonów (freq 0 = cisza) i odtwarza w trybie statycznym.
     * Głośność: gdy [AppSettings.independentVolume] — stała wartość suwaka
     * aplikacji (setVolume), a strumień ALARM dodatkowo uniezależnia od
     * głośności mediów; inaczej pełna skala względem strumienia.
     */
    private fun playTone(s: AppSettings, freqs: FloatArray, durationsMs: LongArray) {
        val sampleRate = 22050

        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val headset = outputs.firstOrNull { it.type in headsetTypes }
        val speaker = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val preferred = if (s.playOnHeadphones) headset else speaker

        // cisza rozbiegowa: BT audio wybudza się z bezczynności i ucina początek
        val leadMs = if (s.playOnHeadphones && headset != null) 300L else 0L
        val leadSamples = (sampleRate * leadMs / 1000).toInt()

        val totalMs = leadMs + durationsMs.sum()
        val totalSamples = leadSamples + (sampleRate * durationsMs.sum() / 1000).toInt()
        val pcm = ShortArray(totalSamples)

        var offset = leadSamples
        for (i in freqs.indices) {
            val n = (sampleRate * durationsMs[i] / 1000).toInt()
            val f = freqs[i]
            if (f > 0f) {
                val attack = min(n / 8, sampleRate / 100)
                for (j in 0 until n) {
                    // obwiednia: krótki attack/release, żeby nie trzaskało
                    val env = when {
                        j < attack -> j.toFloat() / attack
                        j > n - attack -> (n - j).toFloat() / attack
                        else -> 1f
                    }
                    val sample = sin(2.0 * PI * f * j / sampleRate) * env * 0.85
                    if (offset + j < totalSamples) {
                        pcm[offset + j] = (sample * Short.MAX_VALUE).toInt().toShort()
                    }
                }
            }
            offset += n
        }

        val usage = if (s.useAlarmStream) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA
        val attrs = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            if (preferred != null) track.setPreferredDevice(preferred)
            if (s.independentVolume) track.setVolume(s.volume)
            track.play()
            // zwolnij po zakończeniu odtwarzania
            Thread.sleep(totalMs + 100)
            track.release()
        }
    }
}
