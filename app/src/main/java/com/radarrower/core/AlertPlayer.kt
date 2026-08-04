package com.radarrower.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
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
 */
class AlertPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Default)

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
        val totalMs = durationsMs.sum()
        val totalSamples = (sampleRate * totalMs / 1000).toInt()
        val pcm = ShortArray(totalSamples)

        var offset = 0
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
            if (s.independentVolume) track.setVolume(s.volume)
            track.play()
            // zwolnij po zakończeniu odtwarzania
            Thread.sleep(totalMs + 100)
            track.release()
        }
    }
}
