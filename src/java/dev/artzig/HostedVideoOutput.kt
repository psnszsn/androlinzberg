package dev.artzig

import android.view.Surface
import android.os.Handler

/** ACodec native-window ownership at the FFmpeg host seam. The stock codec
 * process is unavailable here; stock Surface/BufferQueue still own attachment,
 * backpressure, crop, dataspace and render timestamps. */
class HostedVideoOutput : AutoCloseable {
    private var surface: Surface? = null
    private var connection = 0L
    private var rotation = 0
    private var scalingMode = 1
    private var listener: FrameListener? = null
    private var callbackHandler: Handler? = null
    private var executing = false
    private var epoch = Any()
    private var pendingPoll: Runnable? = null

    fun interface FrameListener {
        fun rendered(presentationTimeUs: Long, nanoTime: Long)
    }

    /** Stock MediaCodec selects its Handler and delivers EVENT_FRAME_RENDERED.
     * Poll on that same handler to keep a stalled app within the native record
     * budget. This seam owns only timestamp collection and output lifetimes. */
    @Synchronized fun setFrameListener(value: FrameListener?, handler: Handler?) {
        invalidateCallbacks()
        listener = if (handler != null) value else null
        callbackHandler = handler
        updateTracking()
    }

    @Synchronized fun start() {
        executing = true
        invalidateCallbacks()
        updateTracking()
    }

    /** Synchronous codecs remain executing across flush; async codecs require start. */
    @Synchronized fun flush(asynchronous: Boolean) {
        if (asynchronous) executing = false
        invalidateCallbacks()
        updateTracking()
    }

    private fun invalidateCallbacks() {
        epoch = Any()
        pendingPoll?.let { callbackHandler?.removeCallbacks(it) }
        pendingPoll = null
    }
    private fun updateTracking() {
        if (connection != 0L) nativeSetFrameTracking(connection, executing && listener != null)
    }
    private fun schedulePoll(delayMs: Long) {
        if (pendingPoll != null || connection == 0L || !executing || listener == null) return
        val generation = epoch
        val poll = Runnable { pollRenderedFrames(generation) }
        pendingPoll = poll
        if (callbackHandler?.postDelayed(poll, delayMs) != true) {
            // A quit application looper cannot receive informational callbacks.
            // Disable tracking so it cannot permanently fill the native queue.
            pendingPoll = null
            listener = null
            updateTracking()
        }
    }
    private fun pollRenderedFrames(generation: Any) {
        val frames = synchronized(this) {
            if (epoch !== generation || connection == 0L || !executing) return
            pendingPoll = null
            nativeDrainRenderedFrames(connection).also {
                if (it[0] > 0) schedulePoll(10)
            }
        }
        var index = 1
        while (index < frames.size) {
            val callback = synchronized(this) {
                if (epoch !== generation || !executing) return
                listener ?: return
            }
            // Application callbacks can replace the listener, flush or release.
            // No native ownership lock is held while calling application code.
            callback.rendered(frames[index], frames[index + 1])
            index += 2
        }
    }

    @Synchronized fun configure(value: Surface?, rotationDegrees: Int) {
        close()
        rotation = rotationDegrees
        scalingMode = 1
        setSurface(value)
    }

    /** JMediaCodec.setVideoScalingMode updates the current native window. */
    @Synchronized fun setScalingMode(mode: Int) {
        require(mode == 1 || mode == 2) { "Unrecognized mode: $mode" }
        if (connection != 0L) nativeConfigure(connection, rotation, mode)
        scalingMode = mode
    }

    @Synchronized fun setSurface(value: Surface?) {
        if (surface === value) return
        // Connect the replacement first: a failed switch retains the old output.
        val next = if (value != null) nativeConnect(value) else 0L
        if (next != 0L) try { nativeConfigure(next, rotation, scalingMode) }
        catch (failure: RuntimeException) { nativeDisconnect(next); throw failure }
        invalidateCallbacks()
        if (connection != 0L) nativeDisconnect(connection)
        connection = next
        surface = value
        updateTracking()
    }

    /** Consumes the frame on every return, including a queue failure. */
    @Synchronized fun queue(frame: Long, timestampNs: Long, presentationTimeUs: Long): Int {
        if (frame == 0L) return 0
        val target = surface
        if (target == null) {
            releaseHardware(frame)
            return -19
        }
        val status = nativeQueue(connection, frame, timestampNs, presentationTimeUs)
        if (status == 0) schedulePoll(0)
        return status
    }

    @Synchronized override fun close() {
        executing = false
        invalidateCallbacks()
        if (connection != 0L) nativeDisconnect(connection)
        connection = 0L
        surface = null
    }

    @Synchronized fun release() {
        close()
        listener = null
        callbackHandler = null
    }

    companion object {
        @JvmStatic fun releaseHardware(frame: Long) {
            if (frame != 0L) nativeRelease(frame)
        }
        @JvmStatic private external fun nativeConnect(surface: Surface): Long
        @JvmStatic private external fun nativeDisconnect(connection: Long)
        @JvmStatic private external fun nativeConfigure(connection: Long, rotation: Int, scalingMode: Int)
        @JvmStatic private external fun nativeQueue(connection: Long, frame: Long, timestampNs: Long, presentationTimeUs: Long): Int
        @JvmStatic private external fun nativeSetFrameTracking(connection: Long, enabled: Boolean)
        @JvmStatic private external fun nativeDrainRenderedFrames(connection: Long): LongArray
        @JvmStatic private external fun nativeRelease(frame: Long)
    }
}
