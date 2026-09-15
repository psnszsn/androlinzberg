package dev.artzig

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.ServiceManager
import java.io.FileInputStream
import java.nio.file.Files

/** Public MediaPlayer lifecycle through the native media.player and Codec2 services. */
internal object StockMediaPlayerSmoke {
    fun mp3(): ByteArray {
        // MPEG-1 Layer III, 128 kb/s, 44.1 kHz stereo. Zero side/main data is silence.
        val frame = ByteArray(417).apply {
            byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0).copyInto(this)
        }
        return ByteArray(frame.size * 80) { frame[it % frame.size] }
    }

    fun run(drain: () -> Unit) {
        checkNotNull(ServiceManager.checkService("media.player"))
        val file = Files.createTempFile("stock-player-", ".mp3").toFile()
        file.writeBytes(mp3())
        val player = MediaPlayer()
        val failures = ArrayList<Pair<Int, Int>>()
        var prepared = false
        var completed = false
        var sought = false
        player.setOnErrorListener { _, what, extra -> failures.add(what to extra); true }
        player.setOnPreparedListener { prepared = true }
        player.setOnCompletionListener { completed = true }
        player.setOnSeekCompleteListener { sought = true }
        fun await(label: String, predicate: () -> Boolean) {
            val deadline = System.nanoTime() + 8_000_000_000L
            while (!predicate() && failures.isEmpty() && System.nanoTime() < deadline) {
                drain()
                Thread.sleep(5)
            }
            check(failures.isEmpty()) { "$label: playback errors $failures" }
            check(predicate()) { "Timed out: $label" }
        }
        try {
            check(player.audioSessionId > 0 && !player.isPlaying)
            check(runCatching { player.setNextMediaPlayer(player) }.exceptionOrNull() is IllegalArgumentException)
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            player.setVolume(0.25f, 0.75f)
            FileInputStream(file).use { input ->
                player.setDataSource(input.fd, 0, file.length())
                check(input.fd.valid())
            }
            player.prepareAsync()
            await("prepared") { prepared }
            check(player.duration in 1800..2300) { "Duration: ${player.duration}" }
            check(player.trackInfo.any { it.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO })
            check(player.videoWidth == 0 && player.videoHeight == 0)
            player.start()
            await("advancing audio") { player.isPlaying && player.currentPosition > 100 }
            player.pause()
            check(!player.isPlaying)
            player.seekTo(1000, MediaPlayer.SEEK_CLOSEST)
            await("seek callback") { sought }
            check(player.currentPosition in 900..1200)
            player.isLooping = true
            check(player.isLooping)
            player.isLooping = false
            player.start()
            await("completion") { completed }
            check(!player.isPlaying)
            player.stop()
            player.reset()
            check(!player.isPlaying)
            // The same native client can bind a new remote player after reset.
            FileInputStream(file).use { player.setDataSource(it.fd) }
            player.prepare()
            check(player.duration in 1800..2300)
        } finally {
            player.release()
            player.release()
            drain()
            check(file.delete())
        }
        check(runCatching { player.start() }.exceptionOrNull() is IllegalStateException)
        check(failures.isEmpty()) { "Playback errors: $failures" }
        println("stock-mediaplayer: PASS native service playback, preparation, seek, pause, completion, reset and release")
    }
}
