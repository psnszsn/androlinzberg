package dev.artzig

import java.nio.ByteBuffer

/** Existing decoder JNI, moved below the stock MediaCodec client without changing decoding. */
internal object HostedCodecEngine {
    @JvmStatic external fun nativeOpen(name: String): Long
    @JvmStatic external fun nativeConfigure(handle: Long, csd: ByteArray?, rate: Int, channels: Int): Int
    @JvmStatic external fun nativeSetVideoTarget(handle: Long, width: Int, height: Int)
    @JvmStatic external fun nativeSend(handle: Long, buffer: ByteBuffer, offset: Int, size: Int, pts: Long, eof: Int): Int
    @JvmStatic external fun nativeReceiveAudio(handle: Long, buffer: ByteBuffer, capacity: Int, meta: IntArray, pts: LongArray): Int
    @JvmStatic external fun nativeReceiveVideo(handle: Long, meta: IntArray, pts: LongArray, hardware: LongArray): Int
    @JvmStatic external fun nativeFlush(handle: Long)
    @JvmStatic external fun nativeClose(handle: Long)
}
