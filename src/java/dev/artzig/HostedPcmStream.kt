package dev.artzig

import java.nio.ByteBuffer

/** JNI entry points of the existing platform/audio.zig stream, unchanged by client migration. */
internal object HostedPcmStream {
    @JvmStatic external fun nativeOpen(sampleRate: Int, channels: Int): Long
    @JvmStatic external fun nativeWrite(handle: Long, buffer: ByteBuffer, offset: Int, size: Int): Int
    @JvmStatic external fun nativeWriteArray(handle: Long, data: ByteArray, offset: Int, size: Int): Int
    @JvmStatic external fun nativePlay(handle: Long)
    @JvmStatic external fun nativePause(handle: Long)
    @JvmStatic external fun nativeFlush(handle: Long)
    @JvmStatic external fun nativeGetPosition(handle: Long): Long
    @JvmStatic external fun nativeGetPositionNanos(handle: Long): Long
    @JvmStatic external fun nativeGetUnderruns(handle: Long): Int
    @JvmStatic external fun nativeSetVolume(handle: Long, volume: Float)
    @JvmStatic external fun nativeRelease(handle: Long)
}
