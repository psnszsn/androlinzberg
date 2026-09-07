package dev.artzig

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.MessageQueue
import android.view.Choreographer
import android.view.DisplayEventReceiver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the original receiver and Choreographer APIs at the display connection. */
internal object StockDisplayEventsSmoke {
    private fun drain() {
        val queue = checkNotNull(Looper.myLooper()).queue
        while (true) {
            val message = queue.`pollDueMessage$artzig`() ?: return
            try { message.target.dispatchMessage(message) }
            finally { MessageQueue.`recycleDispatched$artzig`(message) }
        }
    }
    private fun frame() { drain(); HostedDisplayEvents.dispatchFrame(System.nanoTime()); drain() }

    fun run() {
        val looper = checkNotNull(Looper.myLooper())
        val timestamps = ArrayList<Long>()
        val ids = ArrayList<Long>()
        var lastFrame = 0
        val receiver = object : DisplayEventReceiver(looper) {
            override fun onVsync(timestamp: Long, display: Long, count: Int, data: VsyncEventData) {
                check(Looper.myLooper() === looper)
                check(count > lastFrame)
                lastFrame = count
                check(data.frameInterval == 16_666_666L && data.frameTimelinesLength == 1)
                val timeline = data.preferredFrameTimeline()
                check(timeline.deadline == timestamp + data.frameInterval)
                check(timeline.expectedPresentationTime == timeline.deadline)
                check(timeline.vsyncId > (ids.lastOrNull() ?: 0))
                timestamps += timestamp
                ids += timeline.vsyncId
                if (timestamps.size == 1) scheduleVsync()
            }
        }
        try {
            repeat(100) { receiver.scheduleVsync() }
            drain()
            check(timestamps.isEmpty()) { "Frame ran before the host prepared windows" }
            frame()
            check(timestamps.size == 1)
            frame()
            check(timestamps.size == 2)
            frame()
            check(timestamps.size == 2) { "Unrequested vsync" }
            receiver.scheduleVsync()
            HostedDisplayEvents.dispatchFrame(System.nanoTime())
            receiver.dispose()
            drain()
            check(timestamps.size == 2) { "Disposed receiver received an event" }
        } finally { receiver.dispose() }

        val choreographer = Choreographer.getInstance()
        val beforeLatest = System.nanoTime()
        check(choreographer.latestExpectedPresentTimeNanos > beforeLatest)
        var callbacks = 0
        choreographer.postFrameCallback {
            check(it > 0 && choreographer.frameTimeNanos == it)
            callbacks++
            choreographer.postFrameCallback { callbacks++ }
        }
        frame()
        check(callbacks == 1)
        frame()
        check(callbacks == 2)
        val canceled = Choreographer.FrameCallback { error("Canceled callback ran") }
        choreographer.postFrameCallback(canceled)
        choreographer.removeFrameCallback(canceled)
        frame()
        var delayed = false
        choreographer.postFrameCallbackDelayed({ delayed = true }, 30)
        frame()
        check(!delayed)
        Thread.sleep(40)
        frame()
        check(delayed)
        var timelineDelivered = false
        choreographer.postVsyncCallback { data ->
            check(data.frameTimeNanos > 0)
            check(data.frameTimelines.size == 1)
            check(data.preferredFrameTimeline.vsyncId > 0)
            check(data.preferredFrameTimeline.deadlineNanos >= data.frameTimeNanos)
            timelineDelivered = true
        }
        frame()
        check(timelineDelivered)
        var appVsyncId = 0L
        choreographer.postVsyncCallback { appVsyncId = it.preferredFrameTimeline.vsyncId }
        var sfVsyncId = 0L
        var sfDelivered = false
        Choreographer.getSfInstance().postVsyncCallback {
            sfVsyncId = it.preferredFrameTimeline.vsyncId
            sfDelivered = true
        }
        frame()
        check(sfDelivered && sfVsyncId == appVsyncId)

        val failure = IllegalStateException("display receiver callback marker")
        val throwing = object : DisplayEventReceiver(looper) {
            override fun onVsync(timestamp: Long, display: Long, count: Int, data: VsyncEventData) {
                throw failure
            }
        }
        try {
            throwing.scheduleVsync()
            HostedDisplayEvents.dispatchFrame(System.nanoTime())
            try { drain(); error("Lost receiver callback exception") }
            catch (caught: IllegalStateException) { check(caught === failure) }
        } finally { throwing.dispose() }

        // Construct on this thread for another thread's queue: nativeInit must use
        // the supplied queue, not the constructing thread's current Looper.
        val worker = HandlerThread("stock-display-event-smoke").apply { start() }
        val workerLooper = checkNotNull(worker.looper)
        val delivered = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable>()
        val remote = object : DisplayEventReceiver(workerLooper) {
            override fun onVsync(timestamp: Long, display: Long, count: Int, data: VsyncEventData) {
                try { check(Looper.myLooper() === workerLooper && count > 0) }
                catch (failure: Throwable) { workerFailure.set(failure) }
                finally { delivered.countDown() }
            }
        }
        try {
            Handler(workerLooper).post { repeat(10) { remote.scheduleVsync() } }
            check(delivered.await(5, TimeUnit.SECONDS)) { "Background queue never received vsync" }
            workerFailure.get()?.let { throw it }
        } finally {
            remote.dispose()
            worker.quitSafely()
            worker.join(5000)
            check(!worker.isAlive)
        }
        try {
            object : DisplayEventReceiver(workerLooper) {}
            error("Accepted an uninitialized MessageQueue")
        } catch (failure: RuntimeException) {
            check(failure.message == "MessageQueue is not initialized.") { failure }
        }
        println("stock display events and Choreographer scheduling: PASS")
    }
}
