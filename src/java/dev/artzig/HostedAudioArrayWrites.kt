package dev.artzig

import android.media.AudioFormat
import android.media.AudioTrack

/** Pinned AudioTrack.write(short[]/float[], offset, size, mode) validation ordering.
 * The current PCM byte-ring adapter has no typed-array/blocking-write contract. Keep the
 * unavailable submission at this host boundary until AudioTrack's JNI client is migrated. */
object HostedAudioArrayWrites {
    private fun arguments(length: Int?, offset: Int, size: Int, mode: Int): Boolean =
        (mode == AudioTrack.WRITE_BLOCKING || mode == AudioTrack.WRITE_NON_BLOCKING) &&
            length != null && offset >= 0 && size >= 0 && offset.toLong() + size <= length

    @JvmStatic fun write(track: AudioTrack, data: ShortArray?, offset: Int, size: Int, mode: Int): Int {
        val encoding = track.format.encoding
        if (track.state == AudioTrack.STATE_UNINITIALIZED || encoding == AudioFormat.ENCODING_PCM_FLOAT ||
            encoding > AudioFormat.ENCODING_LEGACY_SHORT_ARRAY_THRESHOLD) return AudioTrack.ERROR_INVALID_OPERATION
        if (!arguments(data?.size, offset, size, mode)) return AudioTrack.ERROR_BAD_VALUE
        if (size == 0) return 0
        throw ArtzigTestDiagnostics.unsupported("android.media.AudioTrack", "write(short[],int,int,int)",
            "typed PCM array submission is not provided by the existing hosted audio backend")
    }
    @JvmStatic fun write(track: AudioTrack, data: FloatArray?, offset: Int, size: Int, mode: Int): Int {
        if (track.state == AudioTrack.STATE_UNINITIALIZED || track.format.encoding != AudioFormat.ENCODING_PCM_FLOAT)
            return AudioTrack.ERROR_INVALID_OPERATION
        if (!arguments(data?.size, offset, size, mode)) return AudioTrack.ERROR_BAD_VALUE
        if (size == 0) return 0
        throw ArtzigTestDiagnostics.unsupported("android.media.AudioTrack", "write(float[],int,int,int)",
            "typed PCM array submission is not provided by the existing hosted audio backend")
    }
}
