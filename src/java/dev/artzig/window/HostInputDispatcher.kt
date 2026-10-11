package dev.artzig.window

import android.os.IBinder

/**
 * The session's native desktop input integration around stock inputflinger
 * (src/native/HostInputDispatcher.cpp). Stock InputManagerService owns the
 * reader, the dispatcher and their policy; this adds only what a Wayland seat
 * supplies below Java: its devices and raw input_event values for the reader's
 * device source, the task owning each SurfaceFlinger input window (consulted by
 * the dispatcher's hosted drag targeting), and native drag targeting/handoff.
 */
class HostInputDispatcher(callbacks: Callbacks) : AutoCloseable {
    interface Callbacks {
        fun inputWindowTasks(tokens: Array<IBinder>): Array<IBinder?>
        /** The capture request stock InputReader applies (reader thread; post elsewhere). */
        fun pointerCapture(windowToken: IBinder?, sequence: Int)
    }

    private val handle = nativeCreate(callbacks)
    override fun close() = nativeClose(handle)

    data class DragTarget(val token: IBinder, val x: Float, val y: Float)

    companion object {
        // HostDeviceSpec::Kind.
        const val DEVICE_KEYBOARD = 0
        const val DEVICE_MOUSE = 1
        const val DEVICE_TOUCHSCREEN = 2
        const val DEVICE_STYLUS = 3
        /** HostEventHub's locations for the seat pointer and tablet tools, their reader input ports. */
        const val POINTER_PORT = "wayland-seat0/pointer"
        const val TABLET_PORT = "wayland-seat0/tablet"

        fun hostedDragTarget(task: IBinder, display: Int, x: Float, y: Float): DragTarget? {
            require(x.isFinite() && y.isFinite())
            val local = FloatArray(2)
            val token = nativeHostedDragTarget(task, display, x, y, local) ?: return null
            return DragTarget(token, local[0], local[1])
        }
        fun handoffNativeDrag(inputToken: IBinder): Boolean = nativeHandoffDrag(inputToken)

        /** A seat device for the reader; [keyCharacterMap] is stock KCM text and
         * [axes] a stylus tool's WaylandTabletInput capability bits. */
        fun addDevice(kind: Int, name: String, width: Int = 0, height: Int = 0, keyCharacterMap: String? = null,
            axes: Int = 0): Int = nativeAddDevice(kind, name, width, height, keyCharacterMap, axes)
        fun removeDevice(deviceId: Int) = nativeRemoveDevice(deviceId)
        fun setKeyCharacterMap(deviceId: Int, contents: String): Boolean = nativeSetKeyCharacterMap(deviceId, contents)
        /** (type, code, value) triples; EV_SYN/SYN_REPORT completes a packet. */
        fun events(deviceId: Int, whenNanos: Long, values: IntArray) = nativeEvents(deviceId, whenNanos, values)

        @JvmStatic private external fun nativeCreate(callbacks: Callbacks): Long
        @JvmStatic private external fun nativeClose(handle: Long)
        @JvmStatic private external fun nativeHostedDragTarget(task: IBinder, display: Int, x: Float, y: Float, local: FloatArray): IBinder?
        @JvmStatic private external fun nativeHandoffDrag(inputToken: IBinder): Boolean
        @JvmStatic private external fun nativeAddDevice(kind: Int, name: String, width: Int, height: Int, keyCharacterMap: String?, axes: Int): Int
        @JvmStatic private external fun nativeRemoveDevice(deviceId: Int)
        @JvmStatic private external fun nativeSetKeyCharacterMap(deviceId: Int, contents: String): Boolean
        @JvmStatic private external fun nativeEvents(deviceId: Int, whenNanos: Long, values: IntArray)
    }
}
