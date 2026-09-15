package dev.artzig

import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File

/** The companion runner captures this sequence from a private PipeWire sink monitor. */
object StockAudioDeliverySmoke {
    @JvmStatic fun main(args: Array<String>) {
        ApkHost.initHeadless()
        val issues = ArtzigTestDiagnostics.issueSequence()
        val logs = ArtzigTestDiagnostics.logSequence()
        val pattern = FloatArray(2048)
        for (channel in 0..1) {
            var state = if (channel == 0) 0x1234567 else 0x7654321
            repeat(1024) { frame ->
                state = state * 1664525 + 1013904223
                pattern[frame * 2 + channel] = ((state ushr 16) - 32768) / 262144f
            }
        }
        val track = AudioTrack.Builder().setAudioFormat(AudioFormat.Builder()
            .setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(4096 * 8).build()
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED)
            val gate = File(checkNotNull(System.getenv("AZK_AUDIO_TEST_GATE")))
            val readyDeadline = System.nanoTime() + 15_000_000_000L
            println("StockAudioDeliverySmoke: READY")
            while (!gate.exists()) {
                check(System.nanoTime() < readyDeadline) { "PipeWire capture links were not established" }
                Thread.sleep(10)
            }
            track.play()
            repeat(96) {
                check(track.write(pattern, 0, pattern.size, AudioTrack.WRITE_BLOCKING) == pattern.size)
            }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (track.playbackHeadPosition < 96 * 1024) {
                check(System.nanoTime() < deadline) { "AudioTrack did not present the sequence" }
                Thread.sleep(10)
            }
            track.stop()
        } finally { track.release() }
        val diagnostics = ArtzigTestDiagnostics.snapshot(logs, issues)
        File(checkNotNull(System.getenv("AZK_AUDIO_TEST_DIAGNOSTICS"))).writeText(diagnostics)
        check(ArtzigTestDiagnostics.issueSequence() == issues) { diagnostics }
        println("StockAudioDeliverySmoke: PASS 98304 stereo frames through stock AudioTrack")
    }
}
