package dev.artzig

import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack

/** Plays a 2-second 440 Hz sine and checks that the playback clock advances. */
public final class AudioProbe {
    companion object {
        @Throws(Exception::class)
        @JvmStatic
        public fun main(args: Array<String>) {
            val rate: Int = 48000
            val t: AudioTrack =
                AudioTrack
                    .Builder()
                    .setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    ).setBufferSizeInBytes(
                        AudioTrack.getMinBufferSize(
                            rate,
                            AudioFormat.CHANNEL_OUT_STEREO,
                            AudioFormat.ENCODING_PCM_16BIT,
                        ),
                    ).build()
            System.out.println("state=" + t.getState())
            val seconds: Int = 2
            val pcm: ByteArray = ByteArray(rate * 4 * seconds)
            run {
                var i: Int = 0
                while (i < rate * seconds) {
                    val v: Short = (Math.sin(2 * Math.PI * 440 * i / rate) * 12000).toInt().toShort()
                    pcm[i * 4] = v.toByte()
                    pcm[i * 4 + 1] = (v.toInt() shr 8).toByte()
                    pcm[i * 4 + 2] = v.toByte()
                    pcm[i * 4 + 3] = (v.toInt() shr 8).toByte()
                    i++
                }
            }
            t.play()
            var off: Int = 0
            val deadline: Long = System.currentTimeMillis() + 4000
            while (off < pcm.size && System.currentTimeMillis() < deadline) {
                val n: Int = t.write(pcm, off, Math.min(65536, pcm.size - off))
                if (n > 0) {
                    off += n
                } else {
                    Thread.sleep(20)
                }
            }
            Thread.sleep(300)
            val ts: AudioTimestamp = AudioTimestamp()
            val ok: Boolean = t.getTimestamp(ts)
            System.out.println(
                "wrote=" + off + " pos=" + t.getPlaybackHeadPosition() +
                    " ts=" + ok + " tsFrames=" + ts.framePosition +
                    " underruns=" + t.getUnderrunCount(),
            )
            val p1: Long = t.getPlaybackHeadPosition().toLong()
            Thread.sleep(500)
            val p2: Long = t.getPlaybackHeadPosition().toLong()
            System.out.println(
                "clock advance over 500ms: " + (p2 - p1) + " frames" +
                    " (expect ~24000 while data remains, 0 when drained)",
            )
            t.release()
            System.out.println("audio probe done")
        }
    }
}
