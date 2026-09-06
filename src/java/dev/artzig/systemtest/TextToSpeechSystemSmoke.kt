@file:Suppress("DEPRECATION")
package dev.artzig.systemtest

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal object TextToSpeechSystemSmoke {
    fun run(context: Context) {
        val absentResult = LinkedBlockingQueue<Int>()
        val absent = TextToSpeech(context, { absentResult.add(it) },
            "dev.artzig.test.missing-engine", context.packageName, false)
        try {
            check(absentResult.poll(5, TimeUnit.SECONDS) == TextToSpeech.ERROR)
            check(absent.currentEngine == null)
        } finally { absent.shutdown() }
        val initialized = LinkedBlockingQueue<Int>()
        val client = TextToSpeech(context, { initialized.add(it) }, "dev.artzig.test.b")
        try {
            check(initialized.poll(10, TimeUnit.SECONDS) == TextToSpeech.SUCCESS) { "TTS engine initialization" }
            check(client.currentEngine == "dev.artzig.test.b")
            check(client.engines.any { it.name == "dev.artzig.test.b" && !it.system })
            val voice = client.voices.single()
            check(voice.name == "protocol-en-US" && voice.locale == Locale.US &&
                !voice.isNetworkConnectionRequired && voice.features == setOf("protocol-test"))
            check(client.setVoice(voice) == TextToSpeech.SUCCESS && client.voice == voice)
            check(client.isLanguageAvailable(Locale.US) == TextToSpeech.LANG_COUNTRY_AVAILABLE)
            check(client.isLanguageAvailable(Locale.JAPAN) == TextToSpeech.LANG_NOT_SUPPORTED)
            check(client.setSpeechRate(1.25f) == TextToSpeech.SUCCESS)
            check(client.setPitch(0.75f) == TextToSpeech.SUCCESS)
            check(client.setSpeechRate(0f) == TextToSpeech.ERROR)
            check(client.setPitch(-1f) == TextToSpeech.ERROR)

            val events = LinkedBlockingQueue<String>()
            client.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) { events.add("$id:start") }
                override fun onDone(id: String) { events.add("$id:done") }
                override fun onError(id: String) { events.add("$id:legacy-error") }
                override fun onError(id: String, code: Int) { events.add("$id:error:$code") }
                override fun onBeginSynthesis(id: String, sampleRate: Int, format: Int, channels: Int) {
                    events.add("$id:begin:$sampleRate:$format:$channels")
                }
                override fun onAudioAvailable(id: String, audio: ByteArray) {
                    events.add("$id:audio:${audio.joinToString(",")}")
                }
                override fun onRangeStart(id: String, start: Int, end: Int, frame: Int) {
                    events.add("$id:range:$start:$end:$frame")
                }
            })
            fun expect(vararg expected: String) {
                for (event in expected) check(events.poll(5, TimeUnit.SECONDS) == event) { "TTS callback: $event" }
            }
            check(client.speak("hello", TextToSpeech.QUEUE_ADD, Bundle(), "speech") == TextToSpeech.SUCCESS)
            expect("speech:start", "speech:begin:16000:2:1", "speech:audio:0,0,1,0",
                "speech:range:0:5:0", "speech:done")
            val failure = Bundle().apply { putBoolean("dev.artzig.test.b.fail", true) }
            check(client.speak("hello", TextToSpeech.QUEUE_FLUSH, failure, "failure") == TextToSpeech.SUCCESS)
            expect("failure:start", "failure:error:${TextToSpeech.ERROR_SYNTHESIS}")

            val pipe = ParcelFileDescriptor.createPipe()
            pipe[0].use { reader ->
                pipe[1].use { writer ->
                    check(client.synthesizeToFile("transport", Bundle(), writer, "file") == TextToSpeech.SUCCESS)
                    check(writer.fileDescriptor.valid()) // The engine closes only its Binder duplicate.
                }
                val payload = ParcelFileDescriptor.AutoCloseInputStream(reader).use { it.readBytes().toString(Charsets.UTF_8) }
                check(payload == "transport|125|75|protocol-en-US|${Process.myUid()}") { payload }
            }
            expect("file:start", "file:done")
            check(events.isEmpty() && !client.isSpeaking && client.stop() == TextToSpeech.SUCCESS)
        } finally { client.shutdown() }
        check(client.speak("closed", TextToSpeech.QUEUE_ADD, Bundle(), "closed") == TextToSpeech.ERROR)
        System.err.println("system-test-client-a: ok: stock TTS discovery, session, voices, callbacks, parameters and descriptor transport")
    }
}
