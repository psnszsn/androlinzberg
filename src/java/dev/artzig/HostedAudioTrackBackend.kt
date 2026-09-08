@file:Suppress("UNUSED_PARAMETER")

package dev.artzig

import android.media.*
import android.os.Parcel
import android.os.PersistableBundle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Pinned android_media_AudioTrack.cpp and libaudioclient AudioTrack streaming boundary.
 * Their AudioFlinger shared-memory track is unavailable here. Original AudioTrack Java
 * owns validation, PlayerBase registration, callbacks and public lifecycle; this module
 * owns the existing PCM stream, submission accounting, drain/flush and native errors.
 * PipeWire stream implementation and its buffer/timing operations remain unchanged.
 */
internal object HostedAudioTrackBackend {
    private class Track(val stream: Long, val rate: Int, val channels: Int, val encoding: Int,
        val streamType: Int, val capacity: Int) {
        var bufferSize = capacity
        var released = false
        var playing = false
        var stopped = true
        var draining = false
        var written = 0L
        var origin = 0L
        var playerId = -1
        var logSessionId = ""
        var fallbackMode = PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT
        var stretchMode = PlaybackParams.AUDIO_STRETCH_MODE_DEFAULT
    }
    private val nextId = AtomicLong(1)
    private val tracks = ConcurrentHashMap<Long, Track>()
    private val drainWorker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "HostedAudioTrackDrain").apply { isDaemon = true }
    }
    private fun track(id: Long) = tracks[id] ?: throw IllegalStateException("AudioTrack not initialized")
    private inline fun <T> withTrack(id: Long, action: (Track) -> T): T {
        val value = track(id)
        return synchronized(value) {
            check(!value.released) { "AudioTrack not initialized" }
            action(value)
        }
    }
    private fun unavailable(member: String): Nothing = throw ArtzigTestDiagnostics.unsupported(
        "android.media.AudioTrack", member, "The existing PCM stream does not provide this native audio operation")

    @JvmStatic fun setup(weak: Any?, attributes: Any?, rates: IntArray?, mask: Int, indexMask: Int,
        encoding: Int, bufferBytes: Int, mode: Int, sessions: IntArray?, attribution: Parcel?,
        nativeTrack: Long, offload: Boolean, encapsulation: Int, tuner: Any?, packageName: String?): Long {
        if (sessions == null || sessions.isEmpty() || rates == null || rates.isEmpty()) return -1
        if (nativeTrack != 0L) unavailable("native_setup(native track)")
        val attrs = attributes as? AudioAttributes ?: return -1
        val channels = Integer.bitCount(if (indexMask != 0) indexMask else mask)
        if (channels !in 1..32) return -17
        if (encoding !in intArrayOf(AudioFormat.ENCODING_PCM_8BIT, AudioFormat.ENCODING_PCM_16BIT,
                AudioFormat.ENCODING_PCM_FLOAT)) return -18
        if (mode != AudioTrack.MODE_STREAM || offload || encapsulation != AudioTrack.ENCAPSULATION_MODE_NONE ||
            attrs.allFlags and AudioAttributes.FLAG_HW_AV_SYNC != 0) unavailable("native_setup(audio policy mode)")
        val rate = rates[0].takeIf { it != 0 } ?: 48000
        val requested = bufferBytes / (channels * AudioFormat.getBytesPerSample(encoding))
        // Do not report a successful allocation smaller than the caller requested.
        if (requested > rate) return -20
        val session = if (sessions[0] == 0) AudioSystem.newAudioSessionId() else sessions[0]
        if (session <= 0) return -20
        val stream = HostedPcmStream.nativeOpen(rate, channels)
        if (stream == 0L) return -20
        // Reserve the requested track queue within the backend's one-second PCM ring.
        // Both writes and resizing enforce this allocation; unused ring space isn't client capacity.
        val capacity = requested.coerceAtLeast((rate / 4).coerceAtLeast(1))
        val value = Track(stream, rate, channels, encoding, AudioAttributes.toLegacyStreamType(attrs), capacity)
        val id = nextId.getAndIncrement()
        tracks[id] = value
        rates[0] = rate; sessions[0] = session
        return id
    }
    @JvmStatic fun streamType(id: Long): Int = withTrack(id) { it.streamType }
    @JvmStatic fun release(id: Long) {
        val value = tracks.remove(id) ?: return
        synchronized(value) {
            value.released = true
            HostedPcmStream.nativeRelease(value.stream)
        }
    }
    @JvmStatic fun start(id: Long) = withTrack(id) {
        if (it.stopped) it.origin = HostedPcmStream.nativeGetPosition(it.stream)
        it.stopped = false; it.playing = true; it.draining = false
        HostedPcmStream.nativePlay(it.stream)
    }
    @JvmStatic fun pause(id: Long) = withTrack(id) {
        if (it.playing) {
            it.playing = false
            HostedPcmStream.nativePause(it.stream)
        }
    }
    @JvmStatic fun stop(id: Long) = withTrack(id) {
        if (!it.stopped) {
            it.playing = false; it.stopped = true; it.draining = true
            HostedPcmStream.nativePlay(it.stream)
            scheduleDrain(id)
        }
    }
    private fun scheduleDrain(id: Long) {
        drainWorker.schedule({
            val value = tracks[id]
            if (value != null) synchronized(value) {
                if (!value.released && value.draining) {
                    if (HostedPcmStream.nativeGetPosition(value.stream) >= value.written) {
                        HostedPcmStream.nativePause(value.stream); value.draining = false
                    } else scheduleDrain(id)
                }
            }
        }, 5, TimeUnit.MILLISECONDS)
    }
    @JvmStatic fun flush(id: Long) = withTrack(id) {
        // Original native streaming flush ignores an active track and resets position otherwise.
        if (!it.playing) {
            HostedPcmStream.nativePause(it.stream); HostedPcmStream.nativeFlush(it.stream)
            it.origin = HostedPcmStream.nativeGetPosition(it.stream); it.written = it.origin
            it.stopped = true; it.draining = false
        }
    }
    private fun write(id: Long, source: ByteBuffer, format: Int, blocking: Boolean): Int {
        val value = track(id)
        val sampleBytes = AudioFormat.getBytesPerSample(format)
        val requested = source.remaining() / (sampleBytes * value.channels) * sampleBytes * value.channels
        var consumed = 0
        while (consumed < requested) {
            val amount = synchronized(value) {
                if (value.released) return if (consumed > 0) consumed else AudioTrack.ERROR_INVALID_OPERATION
                val position = HostedPcmStream.nativeGetPosition(value.stream)
                // Stop drains only frames submitted before stop. Newly submitted
                // data waits for that drain, as the native client stop position requires.
                val available = if (value.draining) 0 else
                    (value.bufferSize - (value.written - position)).coerceAtLeast(0).toInt()
                val frames = minOf(available, (requested - consumed) / (sampleBytes * value.channels), 4096)
                if (frames == 0) 0 else {
                    val pcm = ByteBuffer.allocate(frames * value.channels * 2).order(ByteOrder.LITTLE_ENDIAN)
                    val input = source.duplicate().order(ByteOrder.nativeOrder())
                    input.position(source.position() + consumed)
                    repeat(frames * value.channels) {
                        pcm.putShort(when (format) {
                            AudioFormat.ENCODING_PCM_8BIT -> (((input.get().toInt() and 255) - 128) shl 8).toShort()
                            AudioFormat.ENCODING_PCM_FLOAT -> {
                                val sample = input.float
                                if (sample.isNaN()) 0 else (sample.coerceIn(-1f, 1f) * 32768f).toInt().coerceIn(-32768, 32767).toShort()
                            }
                            else -> input.short
                        })
                    }
                    val bytes = HostedPcmStream.nativeWriteArray(value.stream, pcm.array(), 0, pcm.capacity())
                    if (bytes < 0) return if (consumed > 0) consumed else bytes
                    value.written += bytes / (2 * value.channels)
                    bytes / 2 * sampleBytes
                }
            }
            consumed += amount
            if ((!blocking && amount == 0) || Thread.currentThread().isInterrupted) break
            if (amount == 0) try { Thread.sleep(2) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); break
            }
        }
        return consumed
    }
    @JvmStatic fun write_byte(id: Long, data: ByteArray?, offset: Int, size: Int, format: Int, blocking: Boolean): Int {
        if (data == null || offset < 0 || size < 0 || offset.toLong() + size > data.size) return -2
        return write(id, ByteBuffer.wrap(data, offset, size), format, blocking)
    }
    @JvmStatic fun write_native_bytes(id: Long, data: ByteBuffer?, offset: Int, size: Int, format: Int, blocking: Boolean): Int {
        if (data == null || offset < 0 || size < 0 || offset.toLong() + size > data.capacity()) return -2
        val input = data.duplicate(); input.clear(); input.position(offset); input.limit(offset + size)
        return write(id, input, format, blocking)
    }
    @JvmStatic fun write_short(id: Long, data: ShortArray?, offset: Int, size: Int, format: Int, blocking: Boolean): Int {
        if (data == null || offset < 0 || size < 0 || offset.toLong() + size > data.size) return -2
        val input = ByteBuffer.allocate(size * 2).order(ByteOrder.nativeOrder())
        input.asShortBuffer().put(data, offset, size)
        val result = write(id, input, format, blocking)
        return if (result < 0) result else result / 2
    }
    @JvmStatic fun write_float(id: Long, data: FloatArray?, offset: Int, size: Int, format: Int, blocking: Boolean): Int {
        if (data == null || offset < 0 || size < 0 || offset.toLong() + size > data.size) return -2
        val input = ByteBuffer.allocate(size * 4).order(ByteOrder.nativeOrder())
        input.asFloatBuffer().put(data, offset, size)
        val result = write(id, input, format, blocking)
        return if (result < 0) result else result / 4
    }
    @JvmStatic fun get_position(id: Long): Int = withTrack(id) {
        if (it.stopped) 0 else (HostedPcmStream.nativeGetPosition(it.stream) - it.origin).toInt()
    }
    @JvmStatic fun get_timestamp(id: Long, values: LongArray?): Int {
        // Unlike most JNI getters, the pinned timestamp query returns ERROR for a null track.
        val value = tracks[id] ?: return -1
        return synchronized(value) {
            if (value.released) return -1
            if (values == null || values.size < 2) return -2
            val position = HostedPcmStream.nativeGetPosition(value.stream)
            val time = HostedPcmStream.nativeGetPositionNanos(value.stream)
            if (value.stopped || time == 0L) return -3
            values[0] = (position - value.origin) and 0xffffffffL; values[1] = time; 0
        }
    }
    @JvmStatic fun get_underrun_count(id: Long): Int = withTrack(id) { HostedPcmStream.nativeGetUnderruns(it.stream) }
    @JvmStatic fun setVolume(id: Long, left: Float, right: Float) = withTrack(id) {
        if (left != right) unavailable("native_setVolume(independent channels)")
        HostedPcmStream.nativeSetVolume(it.stream, left)
    }
    @JvmStatic fun get_buffer_size_frames(id: Long): Int = withTrack(id) { it.bufferSize }
    @JvmStatic fun get_buffer_capacity_frames(id: Long): Int = withTrack(id) { it.capacity }
    @JvmStatic fun set_buffer_size_frames(id: Long, frames: Int): Int = withTrack(id) {
        frames.coerceIn(1, it.capacity).also { size -> it.bufferSize = size }
    }
    @JvmStatic fun get_playback_rate(id: Long): Int = withTrack(id) { it.rate }
    @JvmStatic fun set_playback_rate(id: Long, rate: Int): Int = withTrack(id) {
        if (rate <= 0) -2 else if (rate == it.rate) 0 else -3
    }
    @JvmStatic fun get_playback_params(id: Long): PlaybackParams = withTrack(id) {
        PlaybackParams().allowDefaults().setAudioFallbackMode(it.fallbackMode).setAudioStretchMode(it.stretchMode)
    }
    @JvmStatic fun set_playback_params(id: Long, params: PlaybackParams?) = withTrack(id) {
        requireNotNull(params)
        val speed = runCatching { params.speed }.getOrDefault(1f)
        val pitch = runCatching { params.pitch }.getOrDefault(1f)
        val fallback = runCatching { params.audioFallbackMode }.getOrDefault(it.fallbackMode)
        val stretch = runCatching { params.audioStretchMode }.getOrDefault(it.stretchMode)
        // Original JNI merges only supplied fields and throws if the native rate cannot apply.
        require(speed == 1f && pitch == 1f && fallback in 0..2 && stretch in 0..1) { "arguments out of range" }
        it.fallbackMode = fallback; it.stretchMode = stretch
    }
    @JvmStatic fun get_flags(id: Long): Int = withTrack(id) { 0 }
    @JvmStatic fun get_output_sample_rate(stream: Int): Int = 48000
    @JvmStatic fun get_min_buff_size(rate: Int, channels: Int, format: Int): Int {
        if (format !in intArrayOf(2, 3, 4)) return -1
        return maxOf(4096, rate * channels * AudioFormat.getBytesPerSample(format) / 4)
    }
    @JvmStatic fun is_direct_output_supported(encoding: Int, rate: Int, mask: Int, index: Int,
        content: Int, usage: Int, flags: Int) = false
    @JvmStatic fun setOutputDevice(id: Long, device: Int): Boolean {
        val value = tracks[id] ?: return false
        return synchronized(value) { !value.released && (device == 0 || device == 1) }
    }
    @JvmStatic fun getRoutedDeviceIds(id: Long): IntArray? {
        val value = tracks[id] ?: return null
        return synchronized(value) {
            if (value.released) null else if (value.playing) intArrayOf(1) else intArrayOf()
        }
    }
    // The existing output route never changes; registration does not invent route events.
    @JvmStatic fun enableDeviceCallback(id: Long) {}
    @JvmStatic fun disableDeviceCallback(id: Long) {}
    @JvmStatic fun getPortId(id: Long): Int = withTrack(id) { unavailable("native_getPortId") }
    @JvmStatic fun setPlayerIId(id: Long, player: Int) = withTrack(id) { it.playerId = player }
    @JvmStatic fun setLogSessionId(id: Long, session: String?) = withTrack(id) { it.logSessionId = session.orEmpty() }
    @JvmStatic fun getMetrics(id: Long): PersistableBundle = withTrack(id) {
        PersistableBundle().apply {
            putInt(AudioTrack.MetricsConstants.SAMPLE_RATE, it.rate)
            putInt(AudioTrack.MetricsConstants.FRAME_COUNT, it.capacity)
            putString("android.media.audiotrack.logSessionId", it.logSessionId)
        }
    }
    @JvmStatic fun attachAuxEffect(id: Long, effect: Int): Int = withTrack(id) { if (effect == 0) 0 else -2 }
    @JvmStatic fun setAuxEffectSendLevel(id: Long, level: Float): Int = withTrack(id) { if (level.isNaN() || level !in 0f..1f) -2 else 0 }
    @JvmStatic fun reload_static(id: Long): Int = withTrack(id) { -3 }
    @JvmStatic fun set_position(id: Long, position: Int): Int = withTrack(id) { -3 }
    @JvmStatic fun set_loop(id: Long, start: Int, end: Int, count: Int): Int = withTrack(id) { -3 }
    @JvmStatic fun get_marker_pos(id: Long): Int = withTrack(id) { 0 }
    @JvmStatic fun set_marker_pos(id: Long, marker: Int): Int = withTrack(id) { if (marker == 0) 0 else unavailable("native_set_marker_pos") }
    @JvmStatic fun get_pos_update_period(id: Long): Int = withTrack(id) { 0 }
    @JvmStatic fun set_pos_update_period(id: Long, period: Int): Int = withTrack(id) { if (period == 0) 0 else unavailable("native_set_pos_update_period") }
    @JvmStatic fun get_latency(id: Long): Int = withTrack(id) {
        // Pinned AudioTrack::updateLatency_l uses the allocated frame count, not queued frames
        // or the current writable size. There is no additional AudioFlinger queue here.
        // The existing host backend exposes no measurement of downstream device latency.
        (1000L * it.capacity / it.rate).toInt()
    }
    @JvmStatic fun setPresentation(id: Long, presentation: Int, program: Int): Int = withTrack(id) { -3 }
    @JvmStatic fun applyVolumeShaper(id: Long, config: VolumeShaper.Configuration?, op: VolumeShaper.Operation?): Int {
        if (tracks[id] == null) return -38
        return withTrack(id) { unavailable("native_applyVolumeShaper") }
    }
    @JvmStatic fun getVolumeShaperState(id: Long, shaper: Int): VolumeShaper.State? = null
    @JvmStatic fun set_delay_padding(id: Long, delay: Int, padding: Int) { track(id); unavailable("native_set_delay_padding") }
    @JvmStatic fun set_audio_description_mix_level_db(id: Long, level: Float): Int = withTrack(id) { -3 }
    @JvmStatic fun get_audio_description_mix_level_db(id: Long, level: FloatArray?): Int = withTrack(id) { -3 }
    @JvmStatic fun set_dual_mono_mode(id: Long, mode: Int): Int = withTrack(id) { if (mode == 0) 0 else -3 }
    @JvmStatic fun get_dual_mono_mode(id: Long, modes: IntArray?): Int = withTrack(id) {
        if (modes == null || modes.isEmpty()) -2 else { modes[0] = 0; 0 }
    }
    @JvmStatic fun setStartThresholdInFrames(id: Long, frames: Int): Int = withTrack(id) { unavailable("native_setStartThresholdInFrames") }
    @JvmStatic fun getStartThresholdInFrames(id: Long): Int = withTrack(id) { unavailable("native_getStartThresholdInFrames") }
}
