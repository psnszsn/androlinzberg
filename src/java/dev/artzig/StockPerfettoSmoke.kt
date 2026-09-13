package dev.artzig

import android.internal.perfetto.protos.DataSourceConfigOuterClass.DataSourceConfig
import android.internal.perfetto.protos.DebugAnnotationOuterClass.DebugAnnotation
import android.internal.perfetto.protos.DebugAnnotationOuterClass.DebugAnnotationName
import android.internal.perfetto.protos.InternedDataOuterClass.InternedData
import android.internal.perfetto.protos.TraceConfigOuterClass.TraceConfig
import android.internal.perfetto.protos.TraceOuterClass.Trace
import android.internal.perfetto.protos.TracePacketOuterClass.TracePacket
import android.internal.perfetto.protos.TrackEventConfigOuterClass.TrackEventConfig
import android.internal.perfetto.protos.TrackEventOuterClass.EventCategory
import android.internal.perfetto.protos.TrackEventOuterClass.EventName
import android.internal.perfetto.protos.TrackEventOuterClass.TrackEvent
import android.os.PerfettoTrace
import android.os.PerfettoTrackEventExtra
import android.util.proto.ProtoInputStream
import android.util.proto.ProtoOutputStream

/** Exercises the pinned PerfettoTraceTest contracts against the real in-process Linux SDK. */
internal object StockPerfettoSmoke {
    fun run() {
        PerfettoTrace.register(true)
        val category = PerfettoTrace.Category("artzig.perfetto.smoke")
        val excluded = PerfettoTrace.Category("artzig.perfetto.excluded")
        check(!category.isRegistered && !category.isEnabled)
        check(category.register() === category)
        excluded.register()
        check(category.isRegistered && !category.isEnabled)
        val session = PerfettoTrace.Session(true, config())
        val bytes: ByteArray
        try {
            check(category.isEnabled == android.os.Flags.perfettoSdkTracingV2())
            check(!excluded.isEnabled)
            check(android.os.Trace.isTagEnabled(android.os.Trace.TRACE_TAG_APP))
            android.os.Trace.traceBegin(android.os.Trace.TRACE_TAG_APP, "jni|slice\nname")
            android.os.Trace.traceEnd(android.os.Trace.TRACE_TAG_APP)
            android.os.Trace.instant(android.os.Trace.TRACE_TAG_APP, "jni|instant\nname")
            PerfettoTrace.instant(category, "public-api").emit()
            // The stock public builder accepts an explicit enabled state. Exercise its JNI
            // even when the pinned build's framework tracing flag disables convenience APIs.
            event(TrackEvent.TYPE_INSTANT, category, "arguments")
                .setFlow(2L).setTerminatingFlow(3L)
                .addArg("integer", 10_000_000_000L).addArg("boolean", true)
                .addArg("double", 3.25).addArg("string", "stock tracing").emit()
            event(TrackEvent.TYPE_SLICE_BEGIN, category, "slice").usingProcessNamedTrack("smoke").emit()
            event(TrackEvent.TYPE_SLICE_END, category, "").usingProcessNamedTrack("smoke").emit()
            event(TrackEvent.TYPE_COUNTER, category, "").usingProcessCounterTrack("count").setCounter(42L).emit()
            event(TrackEvent.TYPE_COUNTER, category, "").usingProcessCounterTrack("fraction").setCounter(1.5).emit()
            event(TrackEvent.TYPE_INSTANT, excluded, "excluded-event").emit()
            PerfettoTrackEventExtra.builder(false).init(TrackEvent.TYPE_INSTANT, category)
                .setEventName("disabled-builder").emit()
        } finally {
            bytes = session.close()
        }
        check(!category.isEnabled)
        check(category.unregister() === category && !category.isRegistered)
        excluded.unregister()
        verify(bytes)
        val next = PerfettoTrace.getFlowId()
        check(PerfettoTrace.getFlowId() == next + 1)
        System.err.println("stock-perfetto: real trace capture, typed arguments, slices, counters and category filtering passed")
    }

    private fun event(type: Int, category: PerfettoTrace.Category, name: String) =
        PerfettoTrackEventExtra.builder(true).init(type, category).setEventName(name)

    private fun config(): ByteArray {
        val output = ProtoOutputStream()
        val buffer = output.start(TraceConfig.BUFFERS)
        output.write(TraceConfig.BufferConfig.SIZE_KB, 1024)
        output.end(buffer)
        val source = output.start(TraceConfig.DATA_SOURCES)
        val config = output.start(TraceConfig.DataSource.CONFIG)
        output.write(DataSourceConfig.NAME, "track_event")
        val track = output.start(DataSourceConfig.TRACK_EVENT_CONFIG)
        output.write(TrackEventConfig.DISABLED_CATEGORIES, "*")
        output.write(TrackEventConfig.ENABLED_CATEGORIES, "artzig.perfetto.smoke")
        output.write(TrackEventConfig.ENABLED_CATEGORIES, "app")
        output.end(track)
        output.end(config)
        output.end(source)
        return output.bytes
    }

    private fun verify(bytes: ByteArray) {
        check(bytes.isNotEmpty())
        val packets = messages(bytes, Trace.PACKET)
        val interned = packets.flatMap { messages(it, TracePacket.INTERNED_DATA) }
        val names = interned.flatMap { messages(it, InternedData.EVENT_NAMES) }
            .associate { long(it, EventName.IID) to string(it, EventName.NAME) }
        val categories = interned.flatMap { messages(it, InternedData.EVENT_CATEGORIES) }
            .map { string(it, EventCategory.NAME) }
        check("artzig.perfetto.smoke" in categories)
        check("artzig.perfetto.excluded" !in categories)
        val annotationNames = interned.flatMap { messages(it, InternedData.DEBUG_ANNOTATION_NAMES) }
            .associate { long(it, DebugAnnotationName.IID) to string(it, DebugAnnotationName.NAME) }
        val events = packets.flatMap { messages(it, TracePacket.TRACK_EVENT) }
        fun name(event: ByteArray) = string(event, TrackEvent.NAME)
            ?: names[long(event, TrackEvent.NAME_IID)]
        check(events.none { name(it) in setOf("excluded-event", "disabled-builder") })
        check(events.count { name(it) == "public-api" } == if (android.os.Flags.perfettoSdkTracingV2()) 1 else 0)
        val argsEvent = events.single { name(it) == "arguments" }
        check(int(argsEvent, TrackEvent.TYPE) == TrackEvent.TYPE_INSTANT)
        val args = messages(argsEvent, TrackEvent.DEBUG_ANNOTATIONS).associate {
            (string(it, DebugAnnotation.NAME) ?: annotationNames[long(it, DebugAnnotation.NAME_IID)]) to it
        }
        check(args.keys == setOf("integer", "boolean", "double", "string"))
        check(long(args.getValue("integer"), DebugAnnotation.INT_VALUE) == 10_000_000_000L)
        check(read(args.getValue("boolean"), DebugAnnotation.BOOL_VALUE) { readBoolean(it) } == listOf(true))
        check(read(args.getValue("double"), DebugAnnotation.DOUBLE_VALUE) { readDouble(it) } == listOf(3.25))
        check(string(args.getValue("string"), DebugAnnotation.STRING_VALUE) == "stock tracing")
        check(read(argsEvent, TrackEvent.FLOW_IDS) { readLong(it) }.size == 1)
        check(read(argsEvent, TrackEvent.TERMINATING_FLOW_IDS) { readLong(it) }.size == 1)
        check(events.any { name(it) == "jni slice name" && int(it, TrackEvent.TYPE) == TrackEvent.TYPE_SLICE_BEGIN })
        check(events.any { name(it) == "jni instant name" && int(it, TrackEvent.TYPE) == TrackEvent.TYPE_INSTANT })
        val begin = events.single { name(it) == "slice" && int(it, TrackEvent.TYPE) == TrackEvent.TYPE_SLICE_BEGIN }
        val end = events.single { int(it, TrackEvent.TYPE) == TrackEvent.TYPE_SLICE_END &&
            long(it, TrackEvent.TRACK_UUID) == long(begin, TrackEvent.TRACK_UUID) }
        check(name(begin) == "slice")
        check(long(begin, TrackEvent.TRACK_UUID) == long(end, TrackEvent.TRACK_UUID))
        val counters = events.filter { int(it, TrackEvent.TYPE) == TrackEvent.TYPE_COUNTER }
        check(counters.size == 2)
        check(counters.any { long(it, TrackEvent.COUNTER_VALUE) == 42L })
        check(counters.any { read(it, TrackEvent.DOUBLE_COUNTER_VALUE) { readDouble(it) } == listOf(1.5) })
    }

    private fun messages(bytes: ByteArray, field: Long) = read(bytes, field) { readBytes(it) }
    private fun string(bytes: ByteArray, field: Long) = read(bytes, field) { readString(it) }.singleOrNull()
    private fun long(bytes: ByteArray, field: Long) = read(bytes, field) { readLong(it) }.singleOrNull()
    private fun int(bytes: ByteArray, field: Long) = read(bytes, field) { readInt(it) }.singleOrNull()
    private fun <T> read(bytes: ByteArray, field: Long, value: ProtoInputStream.(Long) -> T): List<T> {
        val input = ProtoInputStream(bytes)
        val result = ArrayList<T>()
        while (input.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            if (input.fieldNumber == field.toInt()) result.add(input.value(field))
        }
        return result
    }
}
