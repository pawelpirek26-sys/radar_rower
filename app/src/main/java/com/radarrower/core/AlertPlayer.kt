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
                val (freqs, durations) = tonesFor(s.soundTheme, event)
                // pilny alert ma własną głośność — może grać głośniej niż zwykłe
                val gain = if (event == AlertEvent.URGENT) s.urgentVolume else s.volume
                playTone(s, freqs, durations, gain)
            }
        }
        if (s.vibrationEnabled) vibrate(event)
    }

    /**
     * Sekwencja tonów per brzmienie i zdarzenie (freq 0 = cisza).
     * Sygnał „czysto" jest we wszystkich brzmieniach łagodny — klakson na pustej
     * drodze byłby mylący.
     */
    private fun tonesFor(theme: String, event: AlertEvent): Pair<FloatArray, LongArray> =
        when (theme) {
            "horn" -> when (event) {
                // krótki pojedynczy klakson
                AlertEvent.NEW_VEHICLE ->
                    floatArrayOf(420f) to longArrayOf(160)
                // „ta-taaa" — dwa klaksony, drugi dłuższy i wyższy
                AlertEvent.URGENT ->
                    floatArrayOf(430f, 0f, 470f) to longArrayOf(130, 60, 300)
                AlertEvent.ALL_CLEAR ->
                    floatArrayOf(330f) to longArrayOf(220)
            }
            "bell" -> when (event) {
                // ding-ding jak dzwonek rowerowy
                AlertEvent.NEW_VEHICLE ->
                    floatArrayOf(2093f, 0f, 2093f) to longArrayOf(200, 50, 260)
                AlertEvent.URGENT ->
                    floatArrayOf(2349f, 0f, 2349f, 0f, 2349f) to
                        longArrayOf(160, 40, 160, 40, 280)
                AlertEvent.ALL_CLEAR ->
                    floatArrayOf(1568f) to longArrayOf(400)
            }
            else -> when (event) { // "beep"
                AlertEvent.NEW_VEHICLE ->
                    floatArrayOf(880f, 0f, 880f) to longArrayOf(120, 70, 120)
                AlertEvent.URGENT ->
                    floatArrayOf(1400f, 0f, 1400f, 0f, 1400f) to
                        longArrayOf(90, 50, 90, 50, 180)
                AlertEvent.ALL_CLEAR ->
                    floatArrayOf(660f, 990f) to longArrayOf(140, 220)
            }
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
    private fun playTone(s: AppSettings, freqs: FloatArray, durationsMs: LongArray, gain: Float) {
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
            val f = freqs[i].toDouble()
            if (f > 0.0) {
                val attack = min(n / 8, sampleRate / 100)
                for (j in 0 until n) {
                    val t = j.toDouble() / sampleRate
                    val w = 2.0 * PI * f * t
                    // barwa zależna od brzmienia
                    val raw = when (s.soundTheme) {
                        // klakson: stos harmonicznych jak w prawdziwym sygnale
                        "horn" -> (sin(w) + 0.6 * sin(2 * w) + 0.4 * sin(3 * w) +
                            0.25 * sin(4 * w)) / 2.25
                        // dzwonek: uderzenie + nieharmoniczny alikwot, wybrzmiewanie
                        "bell" -> sin(w) * kotlin.math.exp(-6.0 * t) +
                            0.4 * sin(2.4 * w) * kotlin.math.exp(-10.0 * t)
                        else -> sin(w)
                    }
                    // obwiednia: krótki attack/release, żeby nie trzaskało
                    // (dzwonek wybrzmiewa sam — dostaje tylko attack)
                    val env = when {
                        j < attack -> j.toFloat() / attack
                        s.soundTheme != "bell" && j > n - attack -> (n - j).toFloat() / attack
                        else -> 1f
                    }
                    val sample = raw * env * 0.6
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
            if (s.independentVolume) {
                // krzywa percepcyjna: gain liniowy brzmi „za głośno" na dole skali
                // (10% liniowo to ledwie -20 dB); sześcian daje sensowny zakres
                track.setVolume(gain * gain * gain)
            }
            track.play()
            // zwolnij po zakończeniu odtwarzania
            Thread.sleep(totalMs + 100)
            track.release()
        }
    }
}
