package dev.artzig

import android.media.AudioManager
import android.media.AudioSystem
import android.media.ToneGenerator

/** Stock ToneGenerator over stock AudioTrack: session, activity, replacement and release. */
internal object StockToneSmoke {
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!predicate()) {
            check(System.nanoTime() < deadline) { "Tone output timed out: $label" }
            Thread.sleep(5)
        }
    }

    private fun active(stream: Int) = AudioSystem.isStreamActive(stream, 0)

    fun run() {
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        val session = tone.audioSessionId
        check(session > 0)
        try {
            check(!tone.startTone(Int.MAX_VALUE, 1))
            // Tracks released by earlier smokes drain asynchronously on the output.
            await("idle output") { !active(AudioManager.STREAM_MUSIC) }
            // A finite tone starts the track on the policy output and stops it when done.
            check(tone.startTone(ToneGenerator.TONE_DTMF_1, 300))
            await("tone start") { active(AudioManager.STREAM_MUSIC) }
            await("finite tone completion") { !active(AudioManager.STREAM_MUSIC) }
            check(AudioSystem.isStreamActive(AudioManager.STREAM_MUSIC, 5000))
            // Replacing a continuous tone keeps the same track running.
            check(tone.startTone(ToneGenerator.TONE_DTMF_1))
            await("continuous tone") { active(AudioManager.STREAM_MUSIC) }
            check(tone.startTone(ToneGenerator.TONE_DTMF_2, 200))
            await("replacement completion") { !active(AudioManager.STREAM_MUSIC) }
            // Repeated short tones and an explicit stop.
            repeat(4) {
                check(tone.startTone(ToneGenerator.TONE_DTMF_0, 40))
                await("short tone $it") { !active(AudioManager.STREAM_MUSIC) }
            }
            check(tone.startTone(ToneGenerator.TONE_DTMF_5))
            await("stop candidate") { active(AudioManager.STREAM_MUSIC) }
            tone.stopTone()
            await("explicit stop") { !active(AudioManager.STREAM_MUSIC) }
            check(tone.startTone(ToneGenerator.TONE_DTMF_7))
            await("tone before release") { active(AudioManager.STREAM_MUSIC) }
            // Release while playing: the stock destructor loses its callback and
            // stops the track after its 3 s wait.
        } finally {
            tone.release()
        }
        await("release stops output") { !active(AudioManager.STREAM_MUSIC) }
        check(runCatching { tone.startTone(ToneGenerator.TONE_DTMF_1) }.exceptionOrNull() is RuntimeException)
        check(runCatching { tone.stopTone() }.exceptionOrNull() is RuntimeException)

        // Stock ToneGenerator plays voice-call tones on the DTMF stream.
        val call = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
        try {
            await("idle music output") { !active(AudioManager.STREAM_MUSIC) }
            check(call.startTone(ToneGenerator.TONE_SUP_DIAL))
            await("voice call tone") { active(AudioManager.STREAM_DTMF) }
            check(!active(AudioManager.STREAM_MUSIC))
            call.stopTone()
            await("voice call stop") { !active(AudioManager.STREAM_DTMF) }
        } finally {
            call.release()
        }
        println("stock-tone: PASS DTMF session, output activity, replacement, stop and release")
    }
}
