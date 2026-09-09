package dev.artzig

import android.media.AudioManager
import android.media.ToneGenerator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Observe generated PCM and the existing output's frame counters through stock APIs. */
internal object StockToneSmoke {
    private external fun beginCapture()
    private external fun samples(session: Int): ShortArray
    // Legacy stream, sample rate, submitted frames, played frames, live, stopped.
    private external fun status(session: Int): LongArray

    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!predicate()) {
            check(System.nanoTime() < deadline) { "Tone output timed out: $label" }
            Thread.sleep(2)
        }
    }
    private fun amplitude(pcm: ShortArray, frequency: Int, from: Int, until: Int): Double {
        var real = 0.0
        var imaginary = 0.0
        for (i in from until until) {
            val angle = 2 * PI * frequency * i / 48000
            real += pcm[i] * cos(angle)
            imaginary += pcm[i] * sin(angle)
        }
        return sqrt(real * real + imaginary * imaginary) / (until - from)
    }
    private fun energy(pcm: ShortArray, fromMs: Int, untilMs: Int): Double =
        (fromMs * 48 until untilMs * 48).sumOf { abs(pcm[it].toInt()).toDouble() } /
            ((untilMs - fromMs) * 48)

    private fun finite(volume: Int, type: Int, duration: Int): ShortArray {
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, volume)
        val session = tone.audioSessionId
        check(session > 0)
        try {
            check(!tone.startTone(Int.MAX_VALUE, 1))
            check(tone.startTone(type, duration))
            await("finite tone") { status(session)[5] == 1L }
            val info = status(session)
            check(info[0] == AudioManager.STREAM_MUSIC.toLong() && info[1] == 48000L)
            check(info[2] > 0 && info[3] > 0) { "Tone never reached PCM output: ${info.contentToString()}" }
            return samples(session)
        } finally {
            tone.release()
            check(status(session)[4] == 0L)
            tone.release()
            check(runCatching { tone.startTone(type) }.exceptionOrNull() is RuntimeException)
            check(runCatching { tone.stopTone() }.exceptionOrNull() is RuntimeException)
        }
    }

    fun run() {
        beginCapture()
        val full = finite(100, ToneGenerator.TONE_DTMF_1, 600)
        check(full.size in 600 * 48..660 * 48) { "Tone duration: ${full.size} samples" }
        for (frequency in intArrayOf(697, 1209)) {
            check(amplitude(full, frequency, 2400, 12000) > 10 * amplitude(full, 1700, 2400, 12000)) {
                "Missing DTMF frequency $frequency"
            }
        }
        val quiet = finite(80, ToneGenerator.TONE_DTMF_1, 600)
        val ratio = energy(quiet, 50, 200) / energy(full, 50, 200)
        check(ratio in 0.30..0.33) { "Stock 0.5 dB volume steps: $ratio" }
        val silent = finite(0, ToneGenerator.TONE_DTMF_1, 300)
        check(silent.all { it == 0.toShort() })

        // Pinned proprietary double beep: 40 ms sound, 200 ms silence, 40 ms sound.
        val cadence = finite(100, ToneGenerator.TONE_PROP_BEEP2, -1)
        check(cadence.size >= 280 * 48)
        check(energy(cadence, 10, 30) > 100 && energy(cadence, 250, 270) > 100)
        check(energy(cadence, 70, 220) == 0.0)

        val tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 100)
        val session = tone.audioSessionId
        check(status(session)[0] == AudioManager.STREAM_DTMF.toLong())
        try {
            check(tone.startTone(ToneGenerator.TONE_DTMF_1))
            await("continuous PCM") { samples(session).size >= 16000 }
            check(tone.startTone(ToneGenerator.TONE_DTMF_2, 300))
            await("replacement completion") { status(session)[5] == 1L }
            val replaced = samples(session)
            val end = replaced.size - 1920
            check(amplitude(replaced, 1336, end - 4800, end) >
                10 * amplitude(replaced, 1209, end - 4800, end))
            // Repeat a completed short tone immediately, covering callback STOP
            // consumption before the following start request.
            repeat(8) {
                val before = samples(session).size
                check(tone.startTone(ToneGenerator.TONE_DTMF_0, 40))
                await("short restart $it") { samples(session).size > before && status(session)[5] == 1L }
            }
            check(tone.startTone(ToneGenerator.TONE_DTMF_5))
            Thread.sleep(30)
            tone.stopTone()
            await("explicit stop") { status(session)[5] == 1L }
            check(tone.startTone(ToneGenerator.TONE_DTMF_7))
            // Release while native callbacks are active; teardown must stop/join
            // the existing worker before freeing the original synthesizer.
        } finally {
            tone.release()
        }
        check(status(session)[4] == 0L)
        println("stock-tone: PASS DTMF PCM, duration, cadence, volume, session, replacement and release")
    }
}
