package dev.artzig

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.nio.ByteBuffer

internal object StockAudioTrackSmoke {
    fun run() {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val format = AudioFormat.Builder().setSampleRate(8000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        check(runCatching { AudioTrack(attrs, format, 3, AudioTrack.MODE_STREAM, 0) }.exceptionOrNull() is IllegalArgumentException)
        check(AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0)
        val track = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format)
            .setBufferSizeInBytes(4096).build()
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED)
            check(track.audioSessionId > 0 && track.streamType == AudioManager.STREAM_MUSIC)
            check(track.bufferCapacityInFrames == 2048)
            check(track.latency == 256)
            check(track.setBufferSizeInFrames(64) == 64)
            check(track.latency == 256)
            track.setVolume(0f)
            val input = ByteBuffer.allocateDirect(200).apply { position(8) }
            check(track.write(input, 128, AudioTrack.WRITE_NON_BLOCKING) == 128)
            check(input.position() == 136)
            check(track.write(input, 32, AudioTrack.WRITE_NON_BLOCKING) == 0)
            check(input.position() == 136)
            check(track.playbackHeadPosition == 0)
            track.flush()
            check(track.write(shortArrayOf(0, 1, -1, 32767), 1, 2, AudioTrack.WRITE_NON_BLOCKING) == 2)
            check(track.write(shortArrayOf(0), 1, 1, AudioTrack.WRITE_NON_BLOCKING) == AudioTrack.ERROR_BAD_VALUE)
            track.play()
            val deadline = System.nanoTime() + 2_000_000_000L
            while (track.playbackHeadPosition < 2 && System.nanoTime() < deadline) Thread.sleep(5)
            check(track.playbackHeadPosition >= 2)
            track.pause()
            check(track.playState == AudioTrack.PLAYSTATE_PAUSED)
            track.flush()
            check(track.playbackHeadPosition == 0)
            check(track.metrics.getInt(AudioTrack.MetricsConstants.SAMPLE_RATE) == 8000)
            val params = android.media.PlaybackParams().setAudioFallbackMode(android.media.PlaybackParams.AUDIO_FALLBACK_MODE_FAIL)
            track.playbackParams = params
            track.playbackParams = android.media.PlaybackParams().setAudioStretchMode(android.media.PlaybackParams.AUDIO_STRETCH_MODE_VOICE)
            params.setAudioFallbackMode(android.media.PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT)
            check(track.playbackParams.audioFallbackMode == android.media.PlaybackParams.AUDIO_FALLBACK_MODE_FAIL)
            check(track.playbackParams.audioStretchMode == android.media.PlaybackParams.AUDIO_STRETCH_MODE_VOICE)
            check(runCatching { track.playbackParams = android.media.PlaybackParams().setSpeed(2f) }.exceptionOrNull() is IllegalArgumentException)
            track.stop()
        } finally { track.release() }
        check(track.state == AudioTrack.STATE_UNINITIALIZED)
        check(track.write(byteArrayOf(0, 0), 0, 2) == AudioTrack.ERROR_INVALID_OPERATION)
        check(runCatching { track.play() }.exceptionOrNull() is IllegalStateException)
        check(!track.getTimestamp(android.media.AudioTimestamp()))
        check(track.routedDevice == null)
        track.release()
        val floating = AudioTrack.Builder().setAudioFormat(AudioFormat.Builder(format)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build()).setBufferSizeInBytes(4096).build()
        try {
            check(floating.write(floatArrayOf(-1f, 0f, 1f), 0, 3, AudioTrack.WRITE_NON_BLOCKING) == 3)
            check(floating.write(shortArrayOf(0), 0, 1, AudioTrack.WRITE_NON_BLOCKING) == AudioTrack.ERROR_INVALID_OPERATION)
        } finally { floating.release() }
        releaseUnblocksWriter(format)
        println("stock-audiotrack: PASS stock validation, buffer ownership, typed writes, playback position and release")
    }

    private fun releaseUnblocksWriter(format: AudioFormat) {
        val track = AudioTrack.Builder().setAudioFormat(format).setBufferSizeInBytes(4096).build()
        val started = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<Result<Int>>()
        val writer = Thread({
            started.countDown()
            result.set(runCatching { track.write(byteArrayOf(0, 0), 0, 2, AudioTrack.WRITE_BLOCKING) })
            finished.countDown()
        }, "AudioTrackBlockingWriteTest").apply { isDaemon = true }
        try {
            val bytes = ByteArray(track.bufferSizeInFrames * 2)
            check(track.write(bytes, 0, bytes.size, AudioTrack.WRITE_NON_BLOCKING) == bytes.size)
            writer.start()
            check(started.await(1, java.util.concurrent.TimeUnit.SECONDS))
            check(!finished.await(50, java.util.concurrent.TimeUnit.MILLISECONDS))
            track.release()
            check(finished.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val observed = checkNotNull(result.get())
            // JNI may observe the released context before entering write, or an interrupted write.
            check(observed.getOrNull() == AudioTrack.ERROR_INVALID_OPERATION ||
                observed.exceptionOrNull() is IllegalStateException) { observed }
        } finally { track.release(); writer.interrupt(); writer.join(1000) }
    }
}
