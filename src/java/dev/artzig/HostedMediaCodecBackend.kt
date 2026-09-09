@file:Suppress("UNUSED_PARAMETER")

package dev.artzig

import android.app.ActivityThread
import android.content.pm.PackageManager
import android.media.*
import android.os.IHwBinder
import android.os.PersistableBundle
import android.view.Surface
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Pinned JMediaCodec and libstagefright MediaCodec lifecycle/ownership boundary.
 * Their Codec2/OMX service is unavailable here; HostedVideoOutput owns the stock
 * Surface/BufferQueue connection and its presentation timestamps.
 * Stock Java owns API validation, buffer invalidation and Handler callback delivery;
 * this module owns the existing decoder, buffers, execution generations and GPU frames.
 */
internal object HostedMediaCodecBackend {
    @JvmStatic private external fun codecException(code: Int, action: Int, message: String): MediaCodec.CodecException
    @JvmStatic private external fun postEvent(codec: MediaCodec, what: Int, arg1: Int, arg2: Int, value: Any?)
    private fun failed(message: String): Nothing = throw codecException(Int.MIN_VALUE, 0, message)
    private fun unavailable(member: String): Nothing = throw ArtzigTestDiagnostics.unsupported(
        "android.media.MediaCodec", member, "The existing decoder does not provide this native codec operation")
    @JvmStatic fun linearBlockOperation(member: String): Unit = throw ArtzigTestDiagnostics.unsupported(
        "android.media.MediaCodec.LinearBlock", member, "The existing decoder has no block-model buffer pool")
    private enum class Phase { INITIALIZED, CONFIGURED, EXECUTING, FLUSHED, RELEASED }
    private class Codec(owner: MediaCodec, val name: String, val engine: String) {
        val owner = WeakReference(owner)
        var phase = Phase.INITIALIZED
        var handle = 0L
        var video = false
        var surface: Surface? = null
        val videoOutput = HostedVideoOutput()
        var input = emptyArray<ByteBuffer>()
        var output = emptyArray<ByteBuffer>()
        val inputOwned = BooleanArray(8)
        val outputOwned = BooleanArray(8)
        val hardware = LongArray(8)
        val outputInfos = Array(8) { MediaCodec.BufferInfo() }
        val formats = HostedCodecFormats()
        var outputFormat = MediaFormat()
        val meta = IntArray(4)
        val pts = LongArray(1)
        val frame = LongArray(1)
        var formatReported = false
        var pendingOutput = -1
        var inputEos = false
        var outputEos = false
        var asynchronous = false
        var generation = 0L
        var pump: ScheduledFuture<*>? = null
    }
    private val ids = AtomicLong(1)
    private val codecs = ConcurrentHashMap<Long, Codec>()
    private val worker = ScheduledThreadPoolExecutor(1) { work ->
        Thread(work, "HostedMediaCodec").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private fun codec(id: Long) = codecs[id] ?: throw IllegalStateException("MediaCodec is released")
    private inline fun <T> withCodec(id: Long, action: (Codec) -> T): T {
        val value = codec(id)
        return synchronized(value) {
            check(value.phase != Phase.RELEASED) { "MediaCodec is released" }
            action(value)
        }
    }
    @JvmStatic fun setup(owner: MediaCodec, requested: String?, byType: Boolean, encoder: Boolean, pid: Int, uid: Int): Long {
        if (requested == null) throw NullPointerException("No codec name specified")
        if (pid != -1 || uid != -1) {
            val context = ActivityThread.currentApplication() ?: ActivityThread.currentActivityThread().systemContext
            if (context.checkSelfPermission(android.Manifest.permission.MEDIA_RESOURCE_OVERRIDE_PID) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException("MEDIA_RESOURCE_OVERRIDE_PID permission required")
        }
        val name = if (byType && !encoder) HostedCodecCatalog.decoderNameForMime(requested) else requested
        val engine = if (!encoder && name != null) HostedCodecCatalog.backendNameFor(name) else null
        require(engine != null && name != null) { "Failed to initialize $requested (NAME_NOT_FOUND)" }
        val id = ids.getAndIncrement()
        codecs[id] = Codec(owner, name, engine)
        return id
    }
    private fun cancel(value: Codec) {
        value.generation++
        value.pump?.cancel(false); value.pump = null
    }
    private fun discardBuffers(value: Codec) {
        for (i in value.hardware.indices) {
            HostedVideoOutput.releaseHardware(value.hardware[i]); value.hardware[i] = 0
        }
        value.inputOwned.fill(false); value.outputOwned.fill(false)
        value.pendingOutput = -1; value.inputEos = false; value.outputEos = false
    }
    private fun close(value: Codec) {
        cancel(value); discardBuffers(value)
        value.videoOutput.close()
        if (value.handle != 0L) HostedCodecEngine.nativeClose(value.handle)
        value.handle = 0
        value.input = emptyArray(); value.output = emptyArray()
        value.formats.clear(); value.surface = null
        // MediaCodec::setState(INITIALIZED/UNINITIALIZED) clears kFlagIsAsync and mCallback.
        value.asynchronous = false
    }
    @JvmStatic fun release(id: Long) {
        val value = codecs.remove(id) ?: return
        synchronized(value) { close(value); value.videoOutput.release(); value.phase = Phase.RELEASED }
    }
    @JvmStatic fun reset(id: Long) = withCodec(id) { close(it); it.phase = Phase.INITIALIZED }
    @JvmStatic fun configure(id: Long, keys: Array<String>?, values: Array<Any?>?, surface: Surface?,
        crypto: MediaCrypto?, descrambler: IHwBinder?, flags: Int) = withCodec(id) {
        check(it.phase == Phase.INITIALIZED) { "Codec is not uninitialized" }
        require(keys != null && values != null && keys.size == values.size)
        if (flags and MediaCodec.CONFIGURE_FLAG_ENCODE != 0) failed("The selected component is a decoder")
        if (flags and MediaCodec.CONFIGURE_FLAG_USE_BLOCK_MODEL != 0) unavailable("configure(block model)")
        if (crypto != null || descrambler != null) unavailable("configure(secure codec)")
        val format = MediaFormat()
        for (i in keys.indices) when (val v = values[i]) {
            is Int -> format.setInteger(keys[i], v)
            is Long -> format.setLong(keys[i], v)
            is Float -> format.setFloat(keys[i], v)
            is String -> format.setString(keys[i], v)
            is ByteBuffer -> format.setByteBuffer(keys[i], v)
            null -> Unit
            else -> throw IllegalArgumentException("Unsupported format value ${keys[i]}")
        }
        val mime = format.getString(MediaFormat.KEY_MIME, "")
        require(MediaCodecList.getInfoFor(it.name).supportedTypes.any { type -> type.equals(mime, true) }) {
            "Unsupported media type $mime for ${it.name}"
        }
        it.video = mime.startsWith("video/")
        if (it.video && surface == null) failed("The existing video decoder requires a Surface")
        val handle = HostedCodecEngine.nativeOpen(it.engine)
        if (handle == 0L) failed("Unable to open ${it.name}")
        try {
            val csd = java.io.ByteArrayOutputStream()
            for (i in 0 until (if (it.video) 4 else 1)) {
                val data = format.getByteBuffer("csd-$i")?.duplicate() ?: break
                val bytes = ByteArray(data.remaining()); data.get(bytes); csd.write(bytes)
            }
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0)
            if (HostedCodecEngine.nativeConfigure(handle, csd.toByteArray().takeIf { bytes -> bytes.isNotEmpty() }, rate, channels) < 0)
                failed("Unable to configure ${it.name}")
            it.input = Array(8) { ByteBuffer.allocateDirect(1 shl 20).order(ByteOrder.nativeOrder()) }
            it.output = Array(if (it.video) 3 else 8) { _ ->
                ByteBuffer.allocateDirect(if (it.video) 1 else 1 shl 19).order(ByteOrder.nativeOrder())
            }
            it.outputFormat = if (it.video) videoFormat(mime, format.getInteger(MediaFormat.KEY_WIDTH, 0), format.getInteger(MediaFormat.KEY_HEIGHT, 0))
                else MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_RAW, rate.takeIf { n -> n > 0 } ?: 44100,
                    channels.takeIf { n -> n > 0 } ?: 2).apply { setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) }
            it.videoOutput.configure(if (it.video) surface else null,
                if (it.video) format.getInteger(MediaFormat.KEY_ROTATION, 0) else 0)
            it.formats.configured(format, it.outputFormat)
            it.handle = handle; it.surface = surface; it.phase = Phase.CONFIGURED
            it.formatReported = false; discardBuffers(it)
        } catch (error: Throwable) {
            it.videoOutput.close()
            HostedCodecEngine.nativeClose(handle)
            it.input = emptyArray(); it.output = emptyArray(); it.formats.clear()
            throw error
        }
    }
    private fun videoFormat(mime: String, width: Int, height: Int) = MediaFormat.createVideoFormat(mime, width, height).apply {
        setInteger(MediaFormat.KEY_CROP_LEFT, 0); setInteger(MediaFormat.KEY_CROP_TOP, 0)
        setInteger(MediaFormat.KEY_CROP_RIGHT, (width - 1).coerceAtLeast(0)); setInteger(MediaFormat.KEY_CROP_BOTTOM, (height - 1).coerceAtLeast(0))
        setInteger(MediaFormat.KEY_STRIDE, width); setInteger(MediaFormat.KEY_SLICE_HEIGHT, height)
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    }
    @JvmStatic fun start(id: Long) = withCodec(id) {
        check(it.phase == Phase.CONFIGURED || (it.phase == Phase.FLUSHED && it.asynchronous))
        it.phase = Phase.EXECUTING
        it.videoOutput.start()
        cancel(it)
        if (it.asynchronous) schedule(id, it, it.generation, 0)
    }
    @JvmStatic fun stop(id: Long) = withCodec(id) { close(it); it.phase = Phase.INITIALIZED }
    @JvmStatic fun flush(id: Long) = withCodec(id) {
        check(it.phase == Phase.EXECUTING)
        it.videoOutput.flush(it.asynchronous)
        cancel(it); discardBuffers(it); HostedCodecEngine.nativeFlush(it.handle)
        it.phase = Phase.FLUSHED
    }
    @JvmStatic fun setCallback(id: Long, callback: MediaCodec.Callback?) = withCodec(id) {
        check(it.phase == Phase.INITIALIZED || it.phase == Phase.CONFIGURED || it.phase == Phase.FLUSHED)
        it.asynchronous = callback != null
    }
    private fun executing(value: Codec, synchronous: Boolean = false) {
        if (synchronous) {
            check(!value.asynchronous) { "Cannot dequeue in asynchronous mode" }
            if (value.phase == Phase.FLUSHED) value.phase = Phase.EXECUTING
        }
        check(value.phase == Phase.EXECUTING) { "Codec is not executing" }
    }
    private fun input(value: Codec): Int {
        if (value.inputEos) return -1
        val index = value.input.indices.firstOrNull { !value.inputOwned[it] } ?: return -1
        value.inputOwned[index] = true; value.input[index].clear()
        return index
    }
    @JvmStatic fun dequeueInputBuffer(id: Long, timeout: Long): Int = dequeue(id, timeout) { value -> input(value) }
    private inline fun dequeue(id: Long, timeout: Long, action: (Codec) -> Int): Int {
        val started = System.nanoTime()
        while (true) {
            val result = withCodec(id) { executing(it, true); action(it) }
            if (result != -1 || timeout == 0L || (timeout > 0 && (System.nanoTime() - started) / 1000 >= timeout)) return result
            try { Thread.sleep(1) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return -1 }
        }
    }
    @JvmStatic fun queueInputBuffer(id: Long, index: Int, offset: Int, size: Int, pts: Long, flags: Int) = withCodec(id) {
        executing(it)
        require(index in it.input.indices && it.inputOwned[index]) { "Input buffer is not client owned" }
        require(offset >= 0 && size >= 0 && offset.toLong() + size <= it.input[index].capacity())
        check(!it.inputEos) { "Input EOS already queued" }
        if (it.video) HostedCodecEngine.nativeSetVideoTarget(it.handle,
            0, 0)
        val configuration = flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        if (size > 0 && (!configuration || it.video)) {
            val result = HostedCodecEngine.nativeSend(it.handle, it.input[index], offset, size, pts, 0)
            if (result < 0) failed("Decoder rejected input: $result")
        }
        if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            val result = HostedCodecEngine.nativeSend(it.handle, it.input[index], 0, 0, pts, 1)
            if (result < 0) failed("Decoder rejected EOS: $result")
            it.inputEos = true
        }
        it.inputOwned[index] = false
    }
    @JvmStatic fun queueSecureInputBuffer(id: Long, index: Int, offset: Int, info: MediaCodec.CryptoInfo?, pts: Long, flags: Int) {
        withCodec(id) { executing(it) }; unavailable("queueSecureInputBuffer")
    }
    private fun output(value: Codec, info: MediaCodec.BufferInfo): Int {
        if (value.pendingOutput >= 0) {
            val index = value.pendingOutput; value.pendingOutput = -1
            val saved = value.outputInfos[index]; info.set(saved.offset, saved.size, saved.presentationTimeUs, saved.flags)
            return index
        }
        if (value.outputEos) return -1
        if (value.video && value.outputOwned.count { it } >= value.output.size) return -1
        val index = value.output.indices.firstOrNull { !value.outputOwned[it] } ?: return -1
        val buffer = value.output[index]
        buffer.clear()
        val result = if (value.video) HostedCodecEngine.nativeReceiveVideo(value.handle, value.meta, value.pts, value.frame)
            else HostedCodecEngine.nativeReceiveAudio(value.handle, buffer, buffer.capacity(), value.meta, value.pts)
        if (result == 1) return -1
        if (result < 0) failed("Decoder receive failed: $result")
        val saved = value.outputInfos[index]
        value.outputOwned[index] = true
        if (result == 2) {
            value.outputEos = true; buffer.limit(0)
            saved.set(0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            info.set(0, 0, 0, saved.flags); return index
        }
        if (value.video) {
            if (result != 3) failed("Video decoder returned a non-hardware frame")
            value.hardware[index] = value.frame[0]
        }
        val changed = !value.formatReported || if (value.video)
            value.meta[1] != value.outputFormat.getInteger(MediaFormat.KEY_WIDTH, 0) || value.meta[2] != value.outputFormat.getInteger(MediaFormat.KEY_HEIGHT, 0) ||
                (value.meta[3] ushr 16 and 63) != value.outputFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD, 0) ||
                (value.meta[3] ushr 22 and 31) != value.outputFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER, 0) ||
                (value.meta[3] ushr 27 and 7) != value.outputFormat.getInteger(MediaFormat.KEY_COLOR_RANGE, 0)
            else value.meta[1] != value.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0) || value.meta[2] != value.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0)
        if (changed) {
            value.outputFormat = if (value.video) videoFormat(value.outputFormat.getString(MediaFormat.KEY_MIME)!!, value.meta[1], value.meta[2])
                else MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_RAW, value.meta[1], value.meta[2]).apply {
                    setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                }
            if (value.video && value.meta[3] != 0) {
                value.outputFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, value.meta[3] ushr 16 and 63)
                value.outputFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, value.meta[3] ushr 22 and 31)
                value.outputFormat.setInteger(MediaFormat.KEY_COLOR_RANGE, value.meta[3] ushr 27 and 7)
            }
            value.formats.outputChanged(value.outputFormat); value.formatReported = true
        }
        if (!value.video) buffer.limit(value.meta[0])
        saved.set(0, value.meta[0], value.pts[0], 0)
        if (changed) { value.pendingOutput = index; return -2 }
        info.set(saved.offset, saved.size, saved.presentationTimeUs, saved.flags)
        return index
    }
    @JvmStatic fun dequeueOutputBuffer(id: Long, info: MediaCodec.BufferInfo?, timeout: Long): Int {
        requireNotNull(info)
        return dequeue(id, timeout) { output(it, info) }
    }
    @JvmStatic fun releaseOutputBuffer(id: Long, index: Int, render: Boolean, timed: Boolean, timestamp: Long) = withCodec(id) {
        executing(it)
        require(index in it.output.indices && it.outputOwned[index]) { "Output buffer is not client owned" }
        val frame = it.hardware[index]; it.hardware[index] = 0
        if (frame != 0L) {
            val surface = it.surface
            if (render && surface != null) {
                val pts = it.outputInfos[index].presentationTimeUs
                val requested = if (timed) timestamp else pts * 1000
                val status = it.videoOutput.queue(frame, requested, pts)
                if (status != 0) { it.outputOwned[index] = false; failed("Surface video queue failed: $status") }
            } else HostedVideoOutput.releaseHardware(frame)
        }
        it.outputOwned[index] = false
    }
    private fun schedule(id: Long, value: Codec, generation: Long, delay: Long) {
        value.pump = worker.schedule(work@{
            val events = ArrayList<Triple<Int, Int, Any?>>()
            val owner = value.owner.get() ?: return@work
            synchronized(value) {
                if (value.phase != Phase.EXECUTING || value.generation != generation) return@synchronized
                try {
                    for (n in 0 until 3) {
                        val info = MediaCodec.BufferInfo()
                        val index = output(value, info)
                        if (index == -2) events.add(Triple(4, 0, formatMap(value.formats.outputFormat())))
                        else if (index >= 0) events.add(Triple(2, index, info)) else break
                    }
                    var budget = if (value.video) (value.output.indices.count { !value.outputOwned[it] }).coerceAtLeast(0) else 8
                    while (budget-- > 0) {
                        val index = input(value)
                        if (index < 0) break
                        events.add(Triple(1, index, null))
                    }
                } catch (error: MediaCodec.CodecException) { events.add(Triple(3, 0, error)) }
                if (value.phase == Phase.EXECUTING && value.generation == generation) schedule(id, value, generation, 4)
            }
            // Java's listener lock must never be acquired while holding codec state.
            // As in AOSP, callers must discard output callbacks outstanding across flush.
            for ((event, index, payload) in events) {
                val current = synchronized(value) { value.phase == Phase.EXECUTING && value.generation == generation }
                if (current) postEvent(owner, 1, event, index, payload)
            }
        }, delay, TimeUnit.MILLISECONDS)
    }
    @JvmStatic fun getBuffers(id: Long, input: Boolean): Array<ByteBuffer?> = withCodec(id) {
        executing(it, true)
        if (input) Array(it.input.size) { index -> it.input[index] }
        else Array(it.output.size) { index -> if (it.video) null else it.output[index] }
    }
    @JvmStatic fun getBuffer(id: Long, input: Boolean, index: Int): ByteBuffer? = withCodec(id) {
        executing(it)
        val buffers = if (input) it.input else it.output
        val owned = if (input) it.inputOwned else it.outputOwned
        if (index !in buffers.indices || !owned[index] || (!input && it.video)) null else buffers[index]
    }
    @JvmStatic fun getImage(id: Long, input: Boolean, index: Int): Image? = withCodec(id) { executing(it); null }
    private fun formatMap(format: MediaFormat): Map<String, Any?> = format.keys.associateWith { key ->
        when (format.getValueTypeForKey(key)) {
            MediaFormat.TYPE_INTEGER -> format.getInteger(key)
            MediaFormat.TYPE_LONG -> format.getLong(key)
            MediaFormat.TYPE_FLOAT -> format.getFloat(key)
            MediaFormat.TYPE_STRING -> format.getString(key)
            MediaFormat.TYPE_BYTE_BUFFER -> format.getByteBuffer(key)
            else -> null
        }
    }
    @JvmStatic fun getFormatNative(id: Long, input: Boolean): Map<String, Any?> = withCodec(id) {
        formatMap(if (input) it.formats.inputFormat() else it.formats.outputFormat())
    }
    @JvmStatic fun getOutputFormatNative(id: Long, index: Int): Map<String, Any?> = withCodec(id) {
        executing(it); require(index in it.output.indices && it.outputOwned[index]); formatMap(it.formats.outputFormat())
    }
    @JvmStatic fun getCanonicalName(id: Long): String = withCodec(id) { it.name }
    @JvmStatic fun getOwnCodecInfo(id: Long): MediaCodecInfo = withCodec(id) { MediaCodecList.getInfoFor(it.name) }
    @JvmStatic fun setSurface(id: Long, surface: Surface?) = withCodec(id) {
        check(it.phase != Phase.INITIALIZED && it.video); requireNotNull(surface)
        it.videoOutput.setSurface(surface) // A failed connection retains the previous output.
        it.surface = surface
    }
    @JvmStatic fun setVideoScalingMode(id: Long, mode: Int) = withCodec(id) {
        require(mode == 1 || mode == 2) { "Unrecognized mode: $mode" }
        it.videoOutput.setScalingMode(mode)
    }
    @JvmStatic fun getMetrics(id: Long): PersistableBundle = withCodec(id) {
        PersistableBundle().apply { putString("android.media.mediacodec.codec", it.name) }
    }
    @JvmStatic fun setParameters(id: Long, keys: Array<String>?, values: Array<Any?>?) = withCodec(id) {
        require(keys != null && values != null && keys.size == values.size)
        // Operating rate is an advisory performance hint; this decoder has no resource governor.
        if (keys.any { key -> key != "operating-rate" }) unavailable("setParameters")
    }
    @JvmStatic fun getSupportedVendorParameters(id: Long): List<String> = withCodec(id) { emptyList() }
    @JvmStatic fun getParameterDescriptor(id: Long, name: String?): MediaCodec.ParameterDescriptor? = withCodec(id) { null }
    @JvmStatic fun subscribeToVendorParameters(id: Long, names: List<String>?) = withCodec(id) { require(names != null && names.isEmpty()) }
    @JvmStatic fun unsubscribeFromVendorParameters(id: Long, names: List<String>?) = withCodec(id) { require(names != null && names.isEmpty()) }

    // Operations requiring absent input surfaces, block buffers or resource services.
    @JvmStatic fun createPersistentInputSurface(): android.view.Surface? = unavailable("createPersistentInputSurface")
    @JvmStatic fun setInputSurface(a0: Long, a1: android.view.Surface?): Unit = unavailable("setInputSurface")
    @JvmStatic fun enableOnFirstTunnelFrameReadyListener(a0: Long, a1: Boolean): Unit = withCodec(a0) { if (a1) unavailable("enableOnFirstTunnelFrameReadyListener") }
    @JvmStatic fun enableOnFrameRenderedListener(id: Long, enabled: Boolean, handler: android.os.Handler?): Unit = withCodec(id) { value ->
        value.videoOutput.setFrameListener(if (!enabled) null else HostedVideoOutput.FrameListener { pts, rendered ->
            // Native timestamp polling shares the Java-selected listener looper.
            // A stalled app therefore retains at most 128 native records instead
            // of accumulating an unbounded stream of Java notification messages.
            val owner = value.owner.get()
            if (owner != null) postEvent(owner, 3, 0, 0,
                mapOf("0-media-time-us" to pts, "0-system-nano" to rendered))
        }, handler)
    }
    @JvmStatic fun detachOutputSurface(a0: Long): Unit = unavailable("detachOutputSurface")
    @JvmStatic fun createInputSurface(a0: Long): android.view.Surface? = unavailable("createInputSurface")
    @JvmStatic fun queueInputBuffers(a0: Long, a1: Int, a2: Array<Any?>?): Unit = unavailable("queueInputBuffers")
    @JvmStatic fun queueSecureInputBuffers(a0: Long, a1: Int, a2: Array<Any?>?, a3: Array<Any?>?): Unit = unavailable("queueSecureInputBuffers")
    @JvmStatic fun mapHardwareBuffer(a0: android.hardware.HardwareBuffer?): android.media.Image? = unavailable("mapHardwareBuffer")
    @JvmStatic fun closeMediaImage(a0: Long): Unit = if (a0 != 0L) unavailable("closeMediaImage") else Unit
    @JvmStatic fun queueLinearBlock(a0: Long, a1: Int, a2: android.media.MediaCodec.LinearBlock?, a3: Array<Any?>?, a4: Array<Any?>?, a5: java.util.ArrayList<Any?>?, a6: java.util.ArrayList<Any?>?): Unit = unavailable("queueLinearBlock")
    @JvmStatic fun queueHardwareBuffer(a0: Long, a1: Int, a2: android.hardware.HardwareBuffer?, a3: Long, a4: Int, a5: java.util.ArrayList<Any?>?, a6: java.util.ArrayList<Any?>?): Unit = unavailable("queueHardwareBuffer")
    @JvmStatic fun getOutputFrame(a0: Long, a1: android.media.MediaCodec.OutputFrame?, a2: Int): Unit = unavailable("getOutputFrame")
    @JvmStatic fun signalEndOfInputStream(a0: Long): Unit = withCodec(a0) { executing(it); throw IllegalStateException("Decoder has no input surface") }
    @JvmStatic fun setAudioPresentation(a0: Long, a1: Int, a2: Int): Unit = unavailable("setAudioPresentation")
    @JvmStatic fun getGloballyAvailableResources(): List<Any?>? = unavailable("getGloballyAvailableResources")
    @JvmStatic fun getRequiredResources(a0: Long): List<Any?>? = unavailable("getRequiredResources")
}
