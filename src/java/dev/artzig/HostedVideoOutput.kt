package dev.artzig

import android.view.ArtzigVideoLayer
import android.view.Surface
import dev.artzig.window.WindowBackend

/** ACodec native-window ownership at the FFmpeg host seam. The stock codec
 * process is unavailable here; stock Surface/BufferQueue still own attachment,
 * backpressure, crop, dataspace and render timestamps. */
class HostedVideoOutput : AutoCloseable {
    private var surface: Surface? = null
    private var connection = 0L
    private var rotation = 0
    private var scalingMode = 1

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
        val next = if (WindowBackend.stock && value != null) nativeConnect(value) else 0L
        if (next != 0L) try { nativeConfigure(next, rotation, scalingMode) }
        catch (failure: RuntimeException) { nativeDisconnect(next); throw failure }
        if (connection != 0L) nativeDisconnect(connection)
        connection = next
        surface = value
    }

    /** Consumes the frame on every return, including a queue failure. */
    @Synchronized fun queue(frame: Long, timestampNs: Long): Int {
        if (frame == 0L) return 0
        val target = surface
        if (target == null) {
            releaseHardware(frame)
            return -19
        }
        if (WindowBackend.stock) return nativeQueue(connection, frame, timestampNs)
        ArtzigVideoLayer.pushHardware(target, frame)
        return 0
    }

    @Synchronized override fun close() {
        if (connection != 0L) nativeDisconnect(connection)
        connection = 0L
        surface = null
    }

    companion object {
        @JvmStatic fun releaseHardware(frame: Long) {
            if (frame != 0L) nativeRelease(frame)
        }
        @JvmStatic private external fun nativeConnect(surface: Surface): Long
        @JvmStatic private external fun nativeDisconnect(connection: Long)
        @JvmStatic private external fun nativeConfigure(connection: Long, rotation: Int, scalingMode: Int)
        @JvmStatic private external fun nativeQueue(connection: Long, frame: Long, timestampNs: Long): Int
        @JvmStatic private external fun nativeRelease(frame: Long)
    }
}
