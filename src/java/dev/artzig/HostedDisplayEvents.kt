package dev.artzig

import android.os.Looper
import android.os.MessageQueue
import android.os.ParcelFileDescriptor
import android.system.Os
import android.view.DisplayEventReceiver
import java.lang.ref.WeakReference
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Follows NativeDisplayEventReceiver / DisplayEventDispatcher in pinned AOSP.
 * SurfaceFlinger's event connection is replaced by the existing host frame source;
 * a pipe delivers events on the receiver's actual MessageQueue, including across
 * synchronization barriers. Stock Choreographer owns all callback scheduling.
 */
object HostedDisplayEvents {
    private const val INTERVAL = 16_666_666L // ArtzigDisplay's 60 Hz mode.
    private val lock = Any()
    private val receivers = HashMap<Long, Receiver>()
    private var nextHandle = 1L
    private var nextVsyncId = 1L
    private var frameNumber = 0
    private val clock by lazy {
        ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "host-display-events").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
    private val queuePointer by lazy {
        MessageQueue::class.java.getDeclaredField("mPtr").apply { isAccessible = true }
    }
    private val timelineConstructor by lazy {
        DisplayEventReceiver.VsyncEventData.FrameTimeline::class.java.getDeclaredConstructor(
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType
        ).apply { isAccessible = true }
    }
    private val dataConstructor by lazy {
        DisplayEventReceiver.VsyncEventData::class.java.getDeclaredConstructor(
            Array<DisplayEventReceiver.VsyncEventData.FrameTimeline>::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
    }

    private class Receiver(
        val receiver: WeakReference<DisplayEventReceiver>,
        val data: WeakReference<DisplayEventReceiver.VsyncEventData>,
        val queue: MessageQueue,
        val pipe: Array<ParcelFileDescriptor>,
    ) {
        var requested = false
        var ready = false
        var timestamp = 0L
        var vsyncId = 0L
        var frame = 0
        var timer: ScheduledFuture<*>? = null
    }

    @JvmStatic fun create(receiver: WeakReference<DisplayEventReceiver>,
        data: WeakReference<DisplayEventReceiver.VsyncEventData>, queue: MessageQueue,
        vsyncSource: Int, eventRegistration: Int, layerHandle: Long): Long {
        if (queuePointer.getLong(queue) == 0L) throw RuntimeException("MessageQueue is not initialized.")
        if (layerHandle != 0L) throw ArtzigTestDiagnostics.unsupported(
            "android.view.DisplayEventReceiver", "nativeInit", "Layer-specific display event connections are unavailable")
        require(vsyncSource == DisplayEventReceiver.VSYNC_SOURCE_APP ||
            vsyncSource == DisplayEventReceiver.VSYNC_SOURCE_SURFACE_FLINGER)
        // The hosted display has one fixed mode and no per-UID frame rate overrides.
        require(eventRegistration and 3.inv() == 0)
        val state = Receiver(receiver, data, queue, ParcelFileDescriptor.createPipe())
        synchronized(lock) {
            val handle = nextHandle++
            receivers[handle] = state
            try {
                queue.addOnFileDescriptorEventListener(state.pipe[0].fileDescriptor,
                    MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT) { _, events ->
                    receive(handle, events)
                }
            } catch (failure: Throwable) {
                dispose(handle)
                throw failure
            }
            return handle
        }
    }

    @JvmStatic fun schedule(handle: Long) {
        synchronized(lock) {
            val state = receivers[handle] ?: return
            if (state.requested || state.ready) return
            state.requested = true
            if (state.queue === Looper.getMainLooper()?.queue) {
                // Wake the native owner, which prepares windows before emitting a frame.
                state.queue.`wakeHost$artzig`()
            } else {
                state.timer = clock.schedule({
                    synchronized(lock) {
                        if (receivers[handle] === state && state.requested) {
                            signal(state, System.nanoTime(), ++frameNumber, nextVsyncId++)
                        }
                    }
                }, INTERVAL, TimeUnit.NANOSECONDS)
            }
        }
    }

    /** Called once the host has prepared this frame's window geometry. */
    @JvmStatic fun dispatchFrame(timestampNanos: Long) {
        val queue = Looper.myLooper()?.queue ?: return
        synchronized(lock) {
            val pending = receivers.values.filter { it.queue === queue && it.requested }
            if (pending.isEmpty()) return
            val frame = ++frameNumber
            val vsyncId = nextVsyncId++
            pending.forEach { signal(it, timestampNanos, frame, vsyncId) }
        }
    }

    @JvmStatic fun hasPendingFrame(): Boolean {
        val queue = Looper.myLooper()?.queue ?: return false
        return synchronized(lock) {
            receivers.values.any { it.queue === queue && (it.requested || it.ready) }
        }
    }

    // At most one byte is outstanding, so the owned pipe cannot fill. The lock
    // serializes writes and disposal; no descriptor can be reused under a writer.
    private fun signal(state: Receiver, timestamp: Long, frame: Int, vsyncId: Long) {
        if (!state.requested || state.ready) return
        Os.write(state.pipe[1].fileDescriptor, byteArrayOf(1), 0, 1)
        state.timestamp = timestamp
        state.vsyncId = vsyncId
        state.frame = frame
        state.requested = false
        state.ready = true
    }

    private fun receive(handle: Long, events: Int): Int {
        val state: Receiver
        val target: DisplayEventReceiver
        val data: DisplayEventReceiver.VsyncEventData
        val timestamp: Long
        val frame: Int
        synchronized(lock) {
            state = receivers[handle] ?: return 0
            if (events and MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT == 0) {
                dispose(handle)
                return 0
            }
            if (!state.ready) return MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT
            Os.read(state.pipe[0].fileDescriptor, ByteArray(1), 0, 1)
            state.ready = false
            target = state.receiver.get() ?: run { dispose(handle); return 0 }
            data = state.data.get() ?: run { dispose(handle); return 0 }
            timestamp = state.timestamp
            frame = state.frame
            data.frameInterval = INTERVAL
            data.frameTimelinesLength = 1
            data.preferredFrameTimelineIndex = 0
            data.numberQueuedBuffers = 0
            data.frameTimelines[0].apply {
                vsyncId = state.vsyncId
                deadline = timestamp + INTERVAL
                expectedPresentationTime = deadline
            }
        }
        // DisplayEventReceiver.dispatchVsync does exactly this virtual call.
        // Leave callback exceptions intact for MessageQueue's native poll to raise.
        target.onVsync(timestamp, 0L, frame, data)
        return synchronized(lock) {
            if (receivers[handle] === state) MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT else 0
        }
    }

    @JvmStatic fun latest(handle: Long): DisplayEventReceiver.VsyncEventData? = synchronized(lock) {
        if (!receivers.containsKey(handle)) return null
        val deadline = System.nanoTime() + INTERVAL
        val timeline = timelineConstructor.newInstance(nextVsyncId++, deadline, deadline)
        dataConstructor.newInstance(arrayOf(timeline), 0, 1, INTERVAL, 0)
    }

    @JvmStatic fun dispose(handle: Long) {
        synchronized(lock) {
            val state = receivers.remove(handle) ?: return
            state.timer?.cancel(false)
            try {
                state.queue.removeOnFileDescriptorEventListener(state.pipe[0].fileDescriptor)
            } finally {
                try { state.pipe[0].close() } finally { state.pipe[1].close() }
            }
        }
    }
}
