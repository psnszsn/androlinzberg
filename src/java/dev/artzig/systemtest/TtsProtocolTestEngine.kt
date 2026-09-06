package dev.artzig.systemtest

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.speech.tts.ITextToSpeechCallback
import android.speech.tts.ITextToSpeechService
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import dev.artzig.system.unsupported
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Binder protocol fixture, not a synthesizer. Installed only by system-test's B APK. */
internal class TtsProtocolTestEngine : Service() {
    private val callbacks = ConcurrentHashMap<IBinder, ITextToSpeechCallback>()
    private val voice = Voice("protocol-en-US", Locale.US, Voice.QUALITY_NORMAL,
        Voice.LATENCY_NORMAL, false, setOf("protocol-test"))
    private val binder = object : ITextToSpeechService.Stub(),
        ITextToSpeechService by unsupported<ITextToSpeechService>() {
        override fun asBinder(): IBinder = this
        override fun setCallback(caller: IBinder, callback: ITextToSpeechCallback?) {
            if (callback == null) callbacks.remove(caller) else callbacks[caller] = callback
        }
        override fun getClientDefaultLanguage() = arrayOf("eng", "USA", "")
        override fun getLanguage() = clientDefaultLanguage
        override fun getDefaultVoiceNameFor(lang: String?, country: String?, variant: String?) = voice.name
        override fun getVoices() = listOf(voice)
        override fun loadVoice(caller: IBinder, name: String?) =
            if (name == voice.name) TextToSpeech.SUCCESS else TextToSpeech.ERROR
        override fun isLanguageAvailable(lang: String?, country: String?, variant: String?) =
            if (lang == "eng") TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
        override fun isSpeaking() = false
        override fun stop(caller: IBinder) = TextToSpeech.SUCCESS
        override fun speak(caller: IBinder, text: CharSequence, queueMode: Int,
            params: Bundle, utteranceId: String): Int {
            val callback = checkNotNull(callbacks[caller])
            callback.onStart(utteranceId)
            if (params.getBoolean("dev.artzig.test.b.fail")) {
                callback.onError(utteranceId, TextToSpeech.ERROR_SYNTHESIS)
            } else {
                check(queueMode == TextToSpeech.QUEUE_ADD)
                callback.onBeginSynthesis(utteranceId, 16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)
                callback.onAudioAvailable(utteranceId, byteArrayOf(0, 0, 1, 0))
                callback.onRangeStart(utteranceId, 0, text.length, 0)
                callback.onSuccess(utteranceId)
            }
            return TextToSpeech.SUCCESS
        }
        override fun synthesizeToFileDescriptor(caller: IBinder, text: CharSequence,
            fd: ParcelFileDescriptor, params: Bundle, utteranceId: String): Int {
            val callback = checkNotNull(callbacks[caller])
            callback.onStart(utteranceId)
            // This diagnostic payload tests descriptor transport and parameter merging.
            val payload = "$text|${params.getInt(TextToSpeech.Engine.KEY_PARAM_RATE)}|" +
                "${params.getInt(TextToSpeech.Engine.KEY_PARAM_PITCH)}|" +
                "${params.getString(TextToSpeech.Engine.KEY_PARAM_VOICE_NAME)}|${Binder.getCallingUid()}"
            ParcelFileDescriptor.AutoCloseOutputStream(fd).use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            callback.onSuccess(utteranceId)
            return TextToSpeech.SUCCESS
        }
    }
    override fun onBind(intent: Intent?): IBinder = binder
}
