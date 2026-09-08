package dev.artzig

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * NdkMediaCodec.cpp's creation, buffer, format and callback ownership over the stock
 * Java MediaCodec. The original native client requires Codec2/OMX and Android
 * BufferQueue; this host instead shares the existing decoder through its Java client.
 */
internal object HostedNdkMediaCodec {
    private class Client(val codec: MediaCodec, val thread: HandlerThread) {
        val callbackLock = Any()
        var released = false
        var callbacks = LongArray(5)
    }
    private val nextId = AtomicLong(1)
    private val clients = ConcurrentHashMap<Long, Client>()
    private fun client(id: Long): Client = checkNotNull(clients[id]) { "Released NDK codec" }
    private external fun dispatch(id: Long, callback: Long, userdata: Long, kind: Int,
        index: Int, offset: Int, size: Int, timeUs: Long, flags: Int, format: Map<String, Any?>?,
        detail: String?)

    @JvmStatic fun create(name: String, byType: Boolean, encoder: Boolean): Long {
        val codec = if (!byType) MediaCodec.createByCodecName(name)
            else if (encoder) MediaCodec.createEncoderByType(name) else MediaCodec.createDecoderByType(name)
        val thread = HandlerThread("NDK MediaCodec_looper")
        try {
            thread.start()
            val id = nextId.getAndIncrement()
            clients[id] = Client(codec, thread)
            return id
        } catch (failure: Throwable) { codec.release(); thread.quit(); throw failure }
    }
    @JvmStatic fun delete(id: Long) {
        val value = clients.remove(id) ?: return
        // A callback can delete its own codec. Java's monitor is reentrant, and a
        // caller on another thread waits until the currently dispatched callback ends.
        synchronized(value.callbackLock) { value.released = true; value.callbacks.fill(0) }
        try { value.codec.release() } finally {
            value.thread.quit()
            if (Thread.currentThread() !== value.thread) value.thread.join()
        }
    }
    @JvmStatic fun configure(id: Long, values: Map<String, Any?>, hasWindow: Boolean, hasCrypto: Boolean, flags: Int) {
        if (hasWindow) unsupported("configure(ANativeWindow)")
        if (hasCrypto) unsupported("configure(AMediaCrypto)")
        client(id).codec.configure(format(values), null, null, flags)
    }
    @JvmStatic fun start(id: Long) { client(id).codec.start() }
    @JvmStatic fun stop(id: Long) { client(id).codec.stop() }
    @JvmStatic fun flush(id: Long) { client(id).codec.flush() }
    @JvmStatic fun name(id: Long): String = client(id).codec.name
    @JvmStatic fun dequeueInput(id: Long, timeout: Long): Int = client(id).codec.dequeueInputBuffer(timeout)
    @JvmStatic fun dequeueOutput(id: Long, timeout: Long, info: LongArray): Int {
        val bufferInfo = MediaCodec.BufferInfo()
        val result = client(id).codec.dequeueOutputBuffer(bufferInfo, timeout)
        if (result >= 0) {
            info[0] = bufferInfo.offset.toLong(); info[1] = bufferInfo.size.toLong()
            info[2] = bufferInfo.presentationTimeUs; info[3] = bufferInfo.flags.toLong()
        }
        return result
    }
    @JvmStatic fun buffer(id: Long, index: Int, input: Boolean): ByteBuffer? =
        if (input) client(id).codec.getInputBuffer(index) else client(id).codec.getOutputBuffer(index)
    @JvmStatic fun queueInput(id: Long, index: Int, offset: Int, size: Int, timeUs: Long, flags: Int) {
        client(id).codec.queueInputBuffer(index, offset, size, timeUs, flags)
    }
    @JvmStatic fun releaseOutput(id: Long, index: Int, render: Boolean, timed: Boolean, timestamp: Long) {
        if (timed) client(id).codec.releaseOutputBuffer(index, timestamp)
        else client(id).codec.releaseOutputBuffer(index, render)
    }
    @JvmStatic fun getFormat(id: Long, input: Boolean, index: Int): Map<String, Any?> {
        val codec = client(id).codec
        return values(if (input) codec.inputFormat else if (index >= 0) codec.getOutputFormat(index) else codec.outputFormat)
    }
    @JvmStatic fun parameters(id: Long, values: Map<String, Any?>) {
        val bundle = Bundle()
        for ((key, value) in values) when (value) {
            is Int -> bundle.putInt(key, value)
            is Long -> bundle.putLong(key, value)
            is Float -> bundle.putFloat(key, value)
            is String -> bundle.putString(key, value)
            else -> throw IllegalArgumentException("Invalid codec parameter $key")
        }
        client(id).codec.setParameters(bundle)
    }
    @JvmStatic fun callback(id: Long, callbacks: LongArray) {
        require(callbacks.size == 5)
        val value = client(id)
        synchronized(value.callbackLock) { value.callbacks = callbacks.copyOf() }
        fun event(kind: Int, index: Int = 0, info: MediaCodec.BufferInfo? = null,
            format: MediaFormat? = null, error: MediaCodec.CodecException? = null) {
            synchronized(value.callbackLock) {
                if (value.released) return
                val callback = value.callbacks[kind]
                if (callback == 0L) return
                dispatch(id, callback, value.callbacks[4], kind, index,
                    info?.offset ?: 0, info?.size ?: 0, info?.presentationTimeUs ?: 0,
                    info?.flags ?: (if (error?.isRecoverable == true) 2 else if (error?.isTransient == true) 1 else 0),
                    format?.let(::values), error?.diagnosticInfo)
            }
        }
        try {
            value.codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = event(0, index)
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) = event(1, index, info)
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = event(2, format = format)
                override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) = event(3, error.errorCode, error = error)
            }, Handler(value.thread.looper))
        } catch (failure: Throwable) {
            synchronized(value.callbackLock) { value.callbacks.fill(0) }
            throw failure
        }
    }
    @JvmStatic fun unsupported(operation: String): Nothing = throw ArtzigTestDiagnostics.unsupported(
        "media.ndk.MediaCodec", operation, "The existing hosted codec does not supply this native surface or secure-codec operation")

    private fun format(values: Map<String, Any?>): MediaFormat = MediaFormat().also { result ->
        for ((key, value) in values) when (value) {
            is Int -> result.setInteger(key, value)
            is Long -> result.setLong(key, value)
            is Float -> result.setFloat(key, value)
            is String -> result.setString(key, value)
            is ByteBuffer -> result.setByteBuffer(key, value)
            else -> throw IllegalArgumentException("Invalid media format value $key")
        }
    }
    private fun values(format: MediaFormat): Map<String, Any?> = format.keys.associateWith { key ->
        when (format.getValueTypeForKey(key)) {
            MediaFormat.TYPE_INTEGER -> format.getInteger(key)
            MediaFormat.TYPE_LONG -> format.getLong(key)
            MediaFormat.TYPE_FLOAT -> format.getFloat(key)
            MediaFormat.TYPE_STRING -> format.getString(key)
            MediaFormat.TYPE_BYTE_BUFFER -> format.getByteBuffer(key)
            else -> null
        }
    }
}
