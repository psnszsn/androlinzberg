package dev.artzig.window

import android.view.Display
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent

/** Tablet-v2 replaces hardware acquisition in pinned TouchInputMapper. Its
 * dispatchHoverExit/dispatchTouches/dispatchHoverEnterAndMove ordering and
 * pressure/tilt conversion remain Android's contract. InputDispatcher retains
 * window hit testing, security and delivery; this module never changes focus.
 */
internal class WaylandTabletInput(private val android: Android, private val clock: () -> Long) {
    interface Android {
        fun point(task: TaskGeometry.Task, x: Float, y: Float): Pair<Float, Float>?
        fun metaState(): Int
        /** Borrowed for this call. */
        fun dispatch(event: MotionEvent)
        fun dispatchKey(event: KeyEvent, task: TaskGeometry.Task)
    }
    data class Device(val id: Int, val generation: Int, val type: Int, val capabilities: Int) {
        init {
            require(id in FIRST_DEVICE until FIRST_DEVICE + MAX_TOOLS && generation > 0)
            require(type in 0x140..0x147 && capabilities and 63 == capabilities)
        }
        val toolType: Int get() = when (type) {
            0x141 -> MotionEvent.TOOL_TYPE_ERASER
            0x145 -> MotionEvent.TOOL_TYPE_FINGER
            0x146, 0x147 -> MotionEvent.TOOL_TYPE_MOUSE
            else -> MotionEvent.TOOL_TYPE_STYLUS
        }
        val source: Int get() = when (toolType) {
            MotionEvent.TOOL_TYPE_MOUSE -> InputDevice.SOURCE_MOUSE
            MotionEvent.TOOL_TYPE_FINGER -> InputDevice.SOURCE_TOUCHSCREEN
            // Pinned TouchInputMapper.configureInputDevice: a drawing tablet
            // is a pointer device with stylus capability, retaining pen/eraser tool type.
            else -> InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_STYLUS
        }
    }
    /** Axes: X/Y, normalized pressure/distance, tilt X/Y degrees, barrel
     * rotation degrees, signed slider, and scroll in discrete wheel units. */
    data class Sample(val deviceId: Int, val generation: Int, val action: Int,
        val button: Int, val pressed: Boolean, val axes: FloatArray) {
        init {
            require(action in ENTER..CANCEL && axes.size == 9 && axes.all { it.isFinite() })
            require(axes[2] in 0f..1f && axes[3] in 0f..1f &&
                axes[4] in -90f..90f && axes[5] in -90f..90f && axes[6] in 0f..360f && axes[7] in -1f..1f)
        }
    }
    private var captured = false
    private val devices = mutableMapOf<Int, Device>()
    private val generations = IntArray(MAX_TOOLS)
    private val streams = mutableMapOf<Int, Stream>()

    fun device(value: Device, present: Boolean) {
        val slot = value.id - FIRST_DEVICE
        if (value.generation < generations[slot]) return
        if (present && value.generation == generations[slot]) {
            if (devices[value.id] == null) return // A removed generation cannot reappear.
            check(devices[value.id] == value) { "Tablet device generation changed contents" }
            return
        }
        if (!present && devices[value.id]?.generation != value.generation) return
        streams.remove(value.id)?.cancel()
        generations[slot] = value.generation
        if (present) devices[value.id] = value else devices.remove(value.id)
    }

    /** Pinned TouchInputMapper.configureInputDevice disables pointer tablets
     * while capture is active; absolute tool coordinates cannot become deltas. */
    fun pointerCapture(enabled: Boolean) {
        if (captured == enabled) return
        captured = enabled
        if (enabled) for ((id, stream) in streams.toMap()) {
            if (stream.device.source and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE) {
                streams.remove(id); stream.cancel()
            }
        }
    }
    fun deviceGeneration(id: Int): Int? = devices[id]?.generation

    fun sample(task: TaskGeometry.Task, sample: Sample) {
        val device = devices[sample.deviceId]?.takeIf { it.generation == sample.generation } ?: return
        if (captured && device.source and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE) return
        if (sample.action == CANCEL) { cancel(task, device.id); return }
        var stream = streams[device.id]
        if (stream?.task != task) {
            // Releases from an earlier surface cannot cancel its successor.
            if (sample.action == UP || sample.action == LEAVE ||
                (sample.action == BUTTON && !sample.pressed)) return
            val point = android.point(task, sample.axes[0], sample.axes[1]) ?: return
            stream?.cancel()
            stream = Stream(task, device, sample.axes.copyOf()).also {
                it.x = point.first; it.y = point.second; streams[device.id] = it
            }
        }
        stream ?: return
        val point = android.point(task, sample.axes[0], sample.axes[1]) ?: return
        stream.axes = sample.axes.copyOf(); stream.x = point.first; stream.y = point.second
        when (sample.action) {
            ENTER -> stream.hoverEnter()
            MOVE -> {
                if (!stream.down) stream.hoverEnter()
                stream.emit(if (stream.down) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_HOVER_MOVE)
            }
            DOWN -> if (!stream.down) {
                stream.hoverExit()
                stream.down = true; stream.downTime = clock()
                stream.emit(MotionEvent.ACTION_DOWN)
            }
            UP -> if (stream.down) {
                stream.emit(MotionEvent.ACTION_UP)
                stream.down = false
                stream.hoverEnter()
            }
            BUTTON -> {
                val bit = when (sample.button) {
                    0x14b -> MotionEvent.BUTTON_STYLUS_PRIMARY
                    0x14c -> MotionEvent.BUTTON_STYLUS_SECONDARY
                    0x110 -> MotionEvent.BUTTON_PRIMARY
                    0x111 -> MotionEvent.BUTTON_SECONDARY
                    0x112 -> MotionEvent.BUTTON_TERTIARY
                    0x113, 0x116 -> MotionEvent.BUTTON_BACK
                    0x114, 0x115 -> MotionEvent.BUTTON_FORWARD
                    else -> 0
                }
                if (bit != 0 && (stream.buttons and bit != 0) != sample.pressed) {
                    if (device.toolType == MotionEvent.TOOL_TYPE_MOUSE) stream.mouseButton(bit, sample.pressed)
                    else {
                        if (!stream.down) stream.hoverEnter()
                        stream.buttons = if (sample.pressed) stream.buttons or bit else stream.buttons and bit.inv()
                        stream.emit(if (sample.pressed) MotionEvent.ACTION_BUTTON_PRESS else MotionEvent.ACTION_BUTTON_RELEASE, bit)
                    }
                }
            }
            LEAVE -> { stream.cancel(); streams.remove(device.id) }
        }
        if (sample.axes[8] != 0f && sample.action != LEAVE)
            stream.emit(MotionEvent.ACTION_SCROLL)
    }

    fun cancel(task: TaskGeometry.Task, deviceId: Int = -1) {
        for ((id, stream) in streams.toMap()) if (stream.task == task && (deviceId == -1 || deviceId == id)) {
            streams.remove(id)
            stream.cancel()
        }
    }

    private inner class Stream(val task: TaskGeometry.Task, val device: Device, var axes: FloatArray) {
        var x = 0f; var y = 0f
        var down = false
        var hovering = false
        var buttons = 0
        var downTime = 0L
        fun hoverEnter() {
            if (!hovering && !down) { hovering = true; emit(MotionEvent.ACTION_HOVER_ENTER) }
        }
        fun hoverExit() {
            if (hovering) { emit(MotionEvent.ACTION_HOVER_EXIT); hovering = false }
        }
        // Pinned CursorInputMapper.sync ordering for tablet mice/lenses:
        // release edge before UP, press edge after DOWN, Back/Forward keys.
        fun mouseButton(bit: Int, pressed: Boolean) {
            val wasDown = down
            buttons = if (pressed) buttons or bit else buttons and bit.inv()
            val nextDown = buttons and 7 != 0
            if (!wasDown && nextDown) downTime = clock()
            val key = when (bit) {
                MotionEvent.BUTTON_BACK -> KeyEvent.KEYCODE_BACK
                MotionEvent.BUTTON_FORWARD -> KeyEvent.KEYCODE_FORWARD
                else -> 0
            }
            if (key != 0 && pressed) key(key, true)
            if (!pressed) emit(MotionEvent.ACTION_BUTTON_RELEASE, bit)
            if (wasDown != nextDown) {
                if (nextDown) { hoverExit(); down = true; emit(MotionEvent.ACTION_DOWN) }
                else { emit(MotionEvent.ACTION_UP); down = false; hoverEnter() }
            } else {
                if (!down) hoverEnter()
                emit(if (down) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_HOVER_MOVE)
            }
            if (pressed) emit(MotionEvent.ACTION_BUTTON_PRESS, bit)
            if (key != 0 && !pressed) key(key, false)
        }
        fun key(code: Int, pressed: Boolean, canceled: Boolean = false) {
            val event = KeyEvent(downTime, clock(), if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                code, 0, android.metaState(), device.id, 0,
                KeyEvent.FLAG_FROM_SYSTEM or if (canceled) KeyEvent.FLAG_CANCELED else 0, device.source)
            event.displayId = Display.DEFAULT_DISPLAY
            try { android.dispatchKey(event, task) } finally { event.recycle() }
        }
        fun cancel() {
            if (device.toolType == MotionEvent.TOOL_TYPE_MOUSE) {
                if (buttons and MotionEvent.BUTTON_BACK != 0) key(KeyEvent.KEYCODE_BACK, false, true)
                if (buttons and MotionEvent.BUTTON_FORWARD != 0) key(KeyEvent.KEYCODE_FORWARD, false, true)
            }
            if (down) emit(MotionEvent.ACTION_CANCEL, flags = MotionEvent.FLAG_CANCELED)
            down = false
            hoverExit()
            buttons = 0
        }
        fun emit(action: Int, actionButton: Int = 0, flags: Int = 0) {
            val tiltX = Math.toRadians(axes[4].toDouble())
            val tiltY = Math.toRadians(axes[5].toDouble())
            val coords = MotionEvent.PointerCoords().apply {
                x = this@Stream.x; y = this@Stream.y
                pressure = if (device.capabilities and PRESSURE != 0) axes[2] else if (down) 1f else 0f
                setAxisValue(MotionEvent.AXIS_DISTANCE, axes[3])
                if (device.capabilities and TILT != 0) {
                    orientation = kotlin.math.atan2(-kotlin.math.sin(tiltX), kotlin.math.sin(tiltY)).toFloat()
                    setAxisValue(MotionEvent.AXIS_TILT,
                        kotlin.math.acos((kotlin.math.cos(tiltX) * kotlin.math.cos(tiltY)).coerceIn(-1.0, 1.0)).toFloat())
                }
                // Preserve tablet-only controls without confusing barrel rotation
                // with Android's tilt azimuth. RZ is in radians; generic axis 1
                // retains the protocol's signed slider range.
                setAxisValue(MotionEvent.AXIS_RZ, Math.toRadians(axes[6].toDouble()).toFloat())
                setAxisValue(MotionEvent.AXIS_GENERIC_1, axes[7])
                if (action == MotionEvent.ACTION_SCROLL) setAxisValue(MotionEvent.AXIS_VSCROLL, axes[8])
            }
            val event = MotionEvent.obtain(downTime, clock(), action, 1,
                arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = device.toolType }),
                arrayOf(coords), android.metaState(), buttons, 1f, 1f, device.id, 0, device.source, flags)
            event.displayId = Display.DEFAULT_DISPLAY
            event.actionButton = actionButton
            try { android.dispatch(event) } finally { event.recycle() }
        }
    }
    companion object {
        const val FIRST_DEVICE = 3
        const val MAX_TOOLS = 16
        const val PRESSURE = 1
        const val TILT = 4
        const val ENTER = 0
        const val MOVE = 1
        const val DOWN = 2
        const val UP = 3
        const val BUTTON = 4
        const val LEAVE = 5
        const val CANCEL = 6
    }
}
