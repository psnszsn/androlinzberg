package dev.artzig

import android.internal.perfetto.protos.DataSourceConfigOuterClass.DataSourceConfig
import android.internal.perfetto.protos.TraceConfigOuterClass.TraceConfig
import android.internal.perfetto.protos.TraceOuterClass.Trace
import android.internal.perfetto.protos.TracePacketOuterClass.TracePacket
import android.os.PerfettoTrace
import android.tracing.perfetto.*
import android.util.CharsetUtils
import android.util.proto.ProtoInputStream
import android.util.proto.ProtoOutputStream
import dalvik.system.VMRuntime
import java.util.concurrent.atomic.AtomicInteger

internal object StockUtilityJniSmoke {
    private class Source : DataSource<DataSourceInstance, Any, Any>("artzig.utility.packets") {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        override fun createInstance(config: ProtoInputStream, index: Int) =
            object : DataSourceInstance(this, index) {
                override fun onStart(args: StartCallbackArguments) { starts.incrementAndGet() }
                override fun onStop(args: StopCallbackArguments) { stops.incrementAndGet() }
            }
        override fun createTlsState(args: CreateTlsStateArgs<DataSourceInstance>) = Any()
        override fun createIncrementalState(args: CreateIncrementalStateArgs<DataSourceInstance>) = Any()
    }

    fun run() {
        // Exercise the real DDM protocol response when allocator leak tracking
        // is unavailable; an empty successful heap dump would be misleading.
        val heapClass = android.ddm.DdmHandleNativeHeap::class.java
        val heap = heapClass.getDeclaredField("mInstance").apply { isAccessible = true }.get(null)
            as android.ddm.DdmHandleNativeHeap
        val response = heap.handleChunk(org.apache.harmony.dalvik.ddmc.Chunk(
            android.ddm.DdmHandleNativeHeap.CHUNK_NHGT, ByteArray(0), 0, 0))
        check(response.type == org.apache.harmony.dalvik.ddmc.ChunkHandler.type("FAIL"))
        val failure = java.nio.ByteBuffer.wrap(response.data, response.offset, response.length)
        check(failure.int == 1)
        val message = CharArray(failure.int) { failure.char }.concatToString()
        check(message == "Something went wrong" && !failure.hasRemaining())

        val runtime = VMRuntime.getRuntime()
        val bytes = runtime.newNonMovableArray(Byte::class.javaPrimitiveType, 128) as ByteArray
        val pointer = runtime.addressOf(bytes)
        for (text in listOf("plain", "é\u0000中", "😀")) {
            bytes.fill(0x55)
            val size = CharsetUtils.toModifiedUtf8Bytes(text, pointer, 3, bytes.size)
            check(size > 0 && bytes[2] == 0x55.toByte() && bytes[3 + size] == 0.toByte())
            bytes[3 + size] = 0x66
            check(CharsetUtils.fromModifiedUtf8Bytes(pointer, 3, size) == text)
            check(bytes[3 + size] == 0x66.toByte()) { "Decoder must restore the boundary byte" }
            bytes.fill(0x55)
            check(CharsetUtils.toModifiedUtf8Bytes(text, pointer, 3, 3 + size) == -size)
            check(bytes.all { it == 0x55.toByte() }) { "Insufficient capacity must not write" }
            check(CharsetUtils.toModifiedUtf8Bytes(text, pointer, 3, 4 + size) == size)
        }

        Producer.init(InitArguments.TESTING)
        PerfettoTrace.register(true)
        val source = Source()
        source.register(DataSourceParams.DEFAULTS)
        var emitted = 0
        source.trace { emitted++ }
        check(emitted == 0)
        repeat(2) { round ->
            val session = PerfettoTrace.Session(true, config(source.name))
            val capture: ByteArray
            try {
                check(source.starts.get() == round + 1)
                var tls: Any? = null
                var incremental: Any? = null
                repeat(3) { sample ->
                    source.trace { context ->
                        val nextTls = context.customTlsState
                        val nextIncremental = context.incrementalState
                        if (sample > 0) {
                            check(tls === nextTls && incremental === nextIncremental)
                        }
                        tls = nextTls
                        incremental = nextIncremental
                        context.newTracePacket().write(TracePacket.TIMESTAMP, 9000L + sample)
                        emitted++
                    }
                }
                // Stock DataSource.trace must end native iteration even if application code throws.
                val sentinel = IllegalStateException("trace callback sentinel")
                try { source.trace { throw sentinel }; error("Expected callback failure") }
                catch (failure: IllegalStateException) { check(failure === sentinel) }
                source.trace { it.newTracePacket().write(TracePacket.TIMESTAMP, 9003L); emitted++ }
                source.flush()
            } finally { capture = session.close() }
            check(source.stops.get() == round + 1)
            val timestamps = mutableListOf<Long>()
            val trace = ProtoInputStream(capture)
            while (trace.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                if (trace.fieldNumber != Trace.PACKET.toInt()) continue
                val packet = ProtoInputStream(trace.readBytes(Trace.PACKET))
                while (packet.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                    if (packet.fieldNumber == TracePacket.TIMESTAMP.toInt()) {
                        val timestamp = packet.readLong(TracePacket.TIMESTAMP)
                        if (timestamp in 9000L..9003L) timestamps.add(timestamp)
                    }
                }
            }
            check(timestamps == listOf(9000L, 9001L, 9002L, 9003L)) { "JNI packets: $timestamps" }
        }
        check(emitted == 8)
        source.trace { error("Stopped data source must not trace") }
        println("StockUtilityJniSmoke: PASS native heap failure protocol, charset boundaries and Perfetto packets, lifecycle, TLS and exception recovery")
    }

    private fun config(name: String): ByteArray {
        val out = ProtoOutputStream()
        val buffer = out.start(TraceConfig.BUFFERS)
        out.write(TraceConfig.BufferConfig.SIZE_KB, 1024)
        out.end(buffer)
        val source = out.start(TraceConfig.DATA_SOURCES)
        val config = out.start(TraceConfig.DataSource.CONFIG)
        out.write(DataSourceConfig.NAME, name)
        out.end(config)
        out.end(source)
        return out.bytes
    }
}
