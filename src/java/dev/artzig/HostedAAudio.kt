package dev.artzig

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * AAudioAudio/AudioStream and legacy AudioStreamTrack behavior over stock Java
 * AudioTrack. The original native client requires MMAP/AudioFlinger tracks.
 * This module owns callback submission and lifecycle over the existing PCM stream;
 * native AAudioStreamParameters retains validation and channel/format conversion.
 */
internal object HostedAAudio {
    private const val INVALID = -895
    private const val ARGUMENT = -898
    private const val UNAVAILABLE = -889
    private const val TIMEOUT = -885
    private const val NULL = -886
    private const val RANGE = -882
    private class Builder {
        // mask, rate, format, direction, capacity, usage, sharing, preset, content,
        // device, performance, frames/callback. Defaults follow original AAudio.
        val config = intArrayOf(0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 10, 0)
        val callbacks = LongArray(4)
        var sessionId = -1 // AAUDIO_SESSION_ID_NONE
    }
    private class Stream(val track: AudioTrack, val rate: Int, val channels: Int,
        val format: Int, val burst: Int, val callbackFrames: Int, val callbacks: LongArray,
        val callbackBuffer: ByteBuffer?, val sessionId: Int) {
        val lock = ReentrantLock()
        val changed = lock.newCondition()
        @Volatile var phase = 2
        @Volatile var closed = false
        var generation = 0L
        var written = 0L
        var readPosition = 0L
        var readLast = 0
        var timestampPosition = 0L
        var timestampLast = 0
        var initialHead = 0L
        // AudioStreamTrack::open and AudioStreamLegacy::onAudioDeviceUpdate
        // retain the last nonempty route. The existing host output has a fixed
        // route, obtained through stock AudioTrack when it becomes active.
        var deviceId = track.routedDevice?.id ?: 0
        var worker: Thread? = null
        var inCallback = false
        val frameBytes = channels * if (format == 1) 2 else 4
    }
    private val ids = AtomicLong(1)
    private val builders = ConcurrentHashMap<Long, Builder>()
    private val streams = ConcurrentHashMap<Long, Stream>()
    private external fun channelMask(count: Int): Int
    private external fun validate(config: IntArray): Int
    private external fun dataCallback(callback: Long, userdata: Long, stream: Long, buffer: ByteBuffer, frames: Int): Int
    private external fun errorCallback(callback: Long, userdata: Long, stream: Long, error: Int)
    @JvmStatic fun createBuilder(): Long = ids.getAndIncrement().also { builders[it] = Builder() }
    @JvmStatic fun deleteBuilder(id: Long): Int = if (builders.remove(id) != null) 0 else NULL
    @JvmStatic fun set(id: Long, key: Int, value: Int) {
        val builder = builders[id] ?: return
        synchronized(builder) {
            when (key) {
                12 -> builder.config[0] = channelMask(value)
                13 -> builder.sessionId = value
                else -> builder.config[key] = value
            }
        }
    }
    @JvmStatic fun callback(id: Long, error: Boolean, callback: Long, userdata: Long) {
        val builder = builders[id] ?: return
        synchronized(builder) { val at = if (error) 2 else 0; builder.callbacks[at] = callback; builder.callbacks[at + 1] = userdata }
    }
    @JvmStatic fun open(id: Long): Long {
        val builder = builders[id] ?: return NULL.toLong()
        val (config, callbacks, identity) = synchronized(builder) {
            Triple(builder.config.copyOf(), builder.callbacks.copyOf(), builder.sessionId)
        }
        val sessionId = identity
        if (sessionId < -1) return ARGUMENT.toLong()
        val validation = validate(config)
        if (validation != 0) return validation.toLong()
        if (config[10] !in 10..13) return ARGUMENT.toLong()
        if (config[11] !in 0..1048576) return RANGE.toLong()
        if (config[10] == 13) {
            if (config[3] != 0 || config[2] == 0 || config[1] == 0 || config[0] == 0) return ARGUMENT.toLong()
            return (-890).toLong() // no offloaded stream
        }
        if (config[3] != 0 || config[9] !in 0..1) return UNAVAILABLE.toLong()
        val format = config[2].takeIf { it != 0 } ?: 2
        if (format !in 1..2) return (-883).toLong()
        val rate = config[1].takeIf { it != 0 } ?: 48000
        val mask = config[0]
        val channels = Integer.bitCount(mask and Int.MAX_VALUE).takeIf { it != 0 } ?: 2
        val frameBytes = channels * if (format == 1) 2 else 4
        val burst = (rate / 100).coerceAtLeast(1)
        val frames = config[11].takeIf { it != 0 } ?: burst
        // AudioTrack's existing hosted allocation is bounded by its one-second ring.
        if (config[4] > rate || frames.toLong() * frameBytes > Int.MAX_VALUE) return UNAVAILABLE.toLong()
        val audioFormat = AudioFormat.Builder().setSampleRate(rate)
            .setEncoding(if (format == 1) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_FLOAT)
        if (mask == 0) audioFormat.setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
        else if (mask < 0) audioFormat.setChannelIndexMask(mask and Int.MAX_VALUE)
        else audioFormat.setChannelMask(mask shl 2)
        val attributes = AudioAttributes.Builder().setUsage(config[5].takeIf { it != 0 } ?: AudioAttributes.USAGE_MEDIA)
            .setContentType(config[8])
        val track = try {
            val trackBuilder = AudioTrack.Builder().setAudioAttributes(attributes.build()).setAudioFormat(audioFormat.build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(config[4].coerceAtLeast(burst * 8) * frameBytes)
            if (sessionId >= 0) trackBuilder.setSessionId(sessionId)
            trackBuilder.build()
        } catch (_: IllegalArgumentException) { return ARGUMENT.toLong() }
        catch (_: UnsupportedOperationException) { return UNAVAILABLE.toLong() }
        catch (_: IllegalStateException) { return UNAVAILABLE.toLong() }
        catch (_: OutOfMemoryError) { return -887L }
        // AudioStreamTrack allocates fixed callback storage during open, so an
        // allocation failure must fail open before publishing the stream handle.
        val buffer = try {
            if (callbacks[0] == 0L) null else ByteBuffer.allocateDirect(frames * frameBytes).order(ByteOrder.nativeOrder())
        } catch (_: OutOfMemoryError) { track.release(); return -887L }
        val stream = Stream(track, rate, channels, format, burst, frames, callbacks, buffer,
            if (sessionId == -1) -1 else track.audioSessionId)
        val handle = ids.getAndIncrement()
        streams[handle] = stream
        if (callbacks[0] != 0L) {
            stream.worker = Thread({ pump(handle, stream) }, "AAudio_$handle").apply { isDaemon = true }
            try { stream.worker!!.start() } catch (failure: Throwable) { streams.remove(handle); track.release(); throw failure }
        }
        return handle
    }
    private fun phase(stream: Stream, value: Int) { stream.phase = value; stream.changed.signalAll() }
    private fun update(stream: Stream) {
        if (stream.phase !in intArrayOf(3, 4, 5, 6, 9)) return
        val head = stream.track.playbackHeadPosition
        // Pinned AAudio MonotonicCounter.update32: subtract with 32-bit wrap and
        // only advance on positive deltas. Native AudioTrack exposes a 32-bit head.
        val delta = head - stream.readLast
        if (delta > 0) { stream.readPosition += delta; stream.readLast = head }
        if (stream.phase == 3 && (head.toLong() and 0xffffffffL) != stream.initialHead) phase(stream, 4)
    }
    private fun resetPosition(stream: Stream) {
        // AudioStreamTrack stop/flush catch up to submitted frames before reset32.
        stream.readPosition = maxOf(stream.readPosition, stream.written); stream.readLast = 0
        stream.timestampPosition = maxOf(stream.timestampPosition, stream.written); stream.timestampLast = 0
    }
    private fun inCallback(stream: Stream) = Thread.currentThread() === stream.worker && stream.inCallback
    @JvmStatic fun command(id: Long, operation: Int): Int {
        val stream = streams[id] ?: return INVALID
        if (inCallback(stream)) return INVALID
        return stream.lock.withLock {
            if (stream.closed) return INVALID
            when (operation) {
                0 -> {
                    if (stream.phase !in intArrayOf(2, 5, 6, 7, 8, 9, 10)) return INVALID
                    stream.initialHead = stream.track.playbackHeadPosition.toLong() and 0xffffffffL
                    stream.generation++; phase(stream, 3); stream.track.play()
                    stream.track.routedDevice?.let { stream.deviceId = it.id }
                }
                1 -> {
                    if (stream.phase !in intArrayOf(2, 3, 4, 5, 6, 8, 10)) return INVALID
                    stream.generation++
                    if (stream.phase in 3..4) stream.track.pause()
                    phase(stream, 6)
                }
                2 -> {
                    if (stream.phase !in intArrayOf(2, 6, 8, 10)) return INVALID
                    stream.generation++; resetPosition(stream)
                    stream.track.flush(); phase(stream, 8)
                }
                3 -> {
                    if (stream.phase !in intArrayOf(2, 3, 4, 6, 8, 9, 10)) return INVALID
                    stream.generation++
                    if (stream.phase in 3..4) {
                        resetPosition(stream); stream.track.stop()
                    }
                    phase(stream, 10)
                }
                else -> return ARGUMENT
            }
            0
        }
    }
    @JvmStatic fun close(id: Long): Int {
        val stream = streams[id] ?: return NULL
        if (inCallback(stream)) return INVALID
        stream.lock.withLock {
            if (stream.closed) return INVALID
            stream.closed = true; stream.generation++; phase(stream, 11)
            stream.track.release()
        }
        stream.worker?.join()
        stream.lock.withLock { phase(stream, 12) }
        streams.remove(id, stream)
        return 0
    }
    @JvmStatic fun get(id: Long, key: Int): Long {
        val stream = streams[id] ?: return INVALID.toLong()
        return stream.lock.withLock {
            if (stream.closed) return INVALID.toLong()
            update(stream)
            when (key) {
                0 -> stream.format.toLong()
                1 -> stream.rate.toLong()
                2 -> stream.channels.toLong()
                3 -> stream.burst.toLong()
                4 -> stream.track.bufferSizeInFrames.toLong()
                5 -> stream.track.bufferCapacityInFrames.toLong()
                6 -> stream.phase.toLong()
                7 -> stream.written
                8 -> stream.readPosition
                9 -> stream.track.underrunCount.toLong()
                10 -> 10L // actual host performance mode: no AudioFlinger FAST/deep-buffer path
                11 -> 1L // legacy track sharing mode is always SHARED
                12 -> 0L // output
                13 -> if (stream.callbacks[0] != 0L && stream.phase in 3..4) 1L else 0L
                14 -> stream.sessionId.toLong()
                15 -> stream.deviceId.toLong()
                else -> ARGUMENT.toLong()
            }
        }
    }
    @JvmStatic fun bufferSize(id: Long, frames: Int): Int {
        val stream = streams[id] ?: return INVALID
        return stream.lock.withLock { if (stream.closed) INVALID else stream.track.setBufferSizeInFrames(frames.coerceAtLeast(stream.burst)) }
    }
    @JvmStatic fun waitState(id: Long, current: Int, timeout: Long, output: IntArray): Int {
        val stream = streams[id] ?: return INVALID
        var remaining = timeout.coerceAtLeast(0)
        return stream.lock.withLock {
            while (true) {
                if (!stream.closed) update(stream)
                output[0] = stream.phase
                if (stream.phase != current) return 0
                if (remaining <= 0) return TIMEOUT
                val start = System.nanoTime()
                stream.changed.awaitNanos(minOf(remaining, 1_000_000L))
                remaining -= (System.nanoTime() - start).coerceAtLeast(0)
            }
            @Suppress("UNREACHABLE_CODE") TIMEOUT
        }
    }
    @JvmStatic fun timestamp(id: Long, clock: Int, output: LongArray): Int {
        if (clock != 1 && clock != 7) return ARGUMENT
        val stream = streams[id] ?: return INVALID
        return stream.lock.withLock {
            if (stream.closed) return INVALID
            update(stream)
            if (stream.phase != 4) return INVALID
            val stamp = AudioTimestamp()
            if (!stream.track.getTimestamp(stamp)) return INVALID
            val head = stamp.framePosition.toInt()
            val delta = head - stream.timestampLast
            if (delta > 0) { stream.timestampPosition += delta; stream.timestampLast = head }
            val position = stream.timestampPosition
            if (position >= stream.written) return INVALID
            output[0] = position
            output[1] = stamp.nanoTime + if (clock == 7) SystemClock.elapsedRealtimeNanos() - System.nanoTime() else 0L
            0
        }
    }
    @JvmStatic fun write(id: Long, data: ByteBuffer, frames: Int, timeout: Long): Int {
        val stream = streams[id] ?: return INVALID
        if (stream.callbacks[0] != 0L && stream.phase in 3..4) return INVALID
        // Pinned AudioStreamTrack::write treats a positive timeout as blocking;
        // its native AudioTrack path does not implement a finite write deadline.
        return write(stream, data, frames, if (timeout > 0) Long.MAX_VALUE else 0L, null)
    }
    private fun write(stream: Stream, data: ByteBuffer, frames: Int, timeout: Long, generation: Long?): Int {
        if (frames < 0 || frames.toLong() * stream.frameBytes > data.remaining()) return RANGE
        val requested = frames * stream.frameBytes
        var written = 0
        var remaining = timeout.coerceAtLeast(0)
        while (written < requested) {
            val start = System.nanoTime()
            val amount = stream.lock.withLock {
                if (stream.closed || (generation != null && generation != stream.generation))
                    return if (written > 0) written / stream.frameBytes else INVALID
                val bytes = stream.track.write(data, requested - written, AudioTrack.WRITE_NON_BLOCKING)
                if (bytes > 0) stream.written += bytes / stream.frameBytes
                update(stream)
                bytes
            }
            if (amount < 0) return if (written > 0) written / stream.frameBytes else INVALID
            written += amount
            if (written == requested || remaining <= 0) break
            if (amount == 0) Thread.sleep(0, minOf(remaining, 999999L).toInt())
            remaining -= (System.nanoTime() - start).coerceAtLeast(0)
        }
        return written / stream.frameBytes
    }
    private fun pump(id: Long, stream: Stream) {
        val buffer = checkNotNull(stream.callbackBuffer)
        try {
            while (true) {
                val generation = stream.lock.withLock {
                    while (!stream.closed && stream.phase !in 3..4) stream.changed.await()
                    if (stream.closed) return
                    stream.generation
                }
                buffer.clear()
                val selected = stream.lock.withLock {
                    if (stream.closed || stream.generation != generation) 0L
                    else { stream.inCallback = true; stream.callbacks[0] }
                }
                if (selected == 0L) continue
                val result = selected.let {
                    try { dataCallback(it, stream.callbacks[1], id, buffer, stream.callbackFrames) }
                    finally { stream.lock.withLock { stream.inCallback = false } }
                }
                if (result != 0) {
                    stream.lock.withLock {
                        if (!stream.closed && stream.generation == generation) {
                            stream.generation++; resetPosition(stream)
                            stream.track.stop(); phase(stream, 10)
                        }
                    }
                    continue
                }
                buffer.position(0)
                val written = write(stream, buffer, stream.callbackFrames, Long.MAX_VALUE, generation)
                if (written < 0 && !stream.closed && stream.generation == generation)
                    throw IllegalStateException("AAudio PCM submission failed")
            }
        } catch (failure: Exception) {
            val notify = stream.lock.withLock {
                if (stream.closed) false else { phase(stream, 13); true }
            }
            if (notify && stream.callbacks[2] != 0L) {
                stream.inCallback = true
                try { errorCallback(stream.callbacks[2], stream.callbacks[3], id, -896) }
                finally { stream.inCallback = false }
            }
        }
    }
}
