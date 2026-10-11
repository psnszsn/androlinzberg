package dev.artzig.window

/**
 * Tablet-v2 tools as evdev pen-display devices for stock InputReader. Each
 * Wayland tool is one host event hub device (a direct touch device with stylus
 * tools, since a tool reports absolute positions on the task); its proximity,
 * contact, buttons, pressure, distance and tilt become raw input_event values.
 * Stock TouchInputMapper owns hover/touch ordering, tool types and axis
 * conversion; Android's reader has no tablet wheel, slider or barrel axes, so
 * those protocol axes have no Android counterpart, as on a device.
 */
internal class WaylandTabletInput(private val android: Android) {
    interface Android {
        fun point(task: TaskGeometry.Task, x: Float, y: Float): Pair<Float, Float>?
        fun displaySize(): Pair<Int, Int>
        /** A drawing-tablet device on the reader's hub with this tool's axes. */
        fun addDevice(name: String, width: Int, height: Int, axes: Int): Int
        fun removeDevice(deviceId: Int)
        /** (type, code, value) triples for a hub device, ending in SYN_REPORT. */
        fun events(deviceId: Int, values: IntArray)
    }
    data class Device(val id: Int, val generation: Int, val type: Int, val capabilities: Int) {
        init {
            require(id in FIRST_DEVICE until FIRST_DEVICE + MAX_TOOLS && generation > 0)
            require(type in 0x140..0x147 && capabilities and 63 == capabilities)
        }
        /** Wayland tool types are the evdev BTN_TOOL_* codes. */
        val tool: Int get() = type
    }
    /** Axes: X/Y, normalized pressure/distance, tilt X/Y degrees, barrel
     * rotation degrees, signed slider, and scroll in discrete wheel units. */
    data class Sample(val deviceId: Int, val generation: Int, val action: Int,
        val button: Int, val pressed: Boolean, val axes: FloatArray) {
        init {
            require(action in ENTER..FRAME && axes.size == 9 && axes.all { it.isFinite() })
            require(axes[2] in 0f..1f && axes[3] in 0f..1f &&
                axes[4] in -90f..90f && axes[5] in -90f..90f && axes[6] in 0f..360f && axes[7] in -1f..1f)
        }
    }
    private val devices = mutableMapOf<Int, Device>()
    private val hubDevices = mutableMapOf<Int, Int>()
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
        // Removing the hub device resets its mapper, which cancels an active
        // contact as a disconnected tablet's would.
        streams.remove(value.id)
        generations[slot] = value.generation
        hubDevices.remove(value.id)?.let(android::removeDevice)
        if (present) {
            devices[value.id] = value
            val (width, height) = android.displaySize()
            hubDevices[value.id] = android.addDevice("Wayland tablet tool ${value.id}", width, height,
                value.capabilities and (PRESSURE or DISTANCE or TILT))
        } else devices.remove(value.id)
    }

    fun deviceGeneration(id: Int): Int? = devices[id]?.generation

    fun sample(task: TaskGeometry.Task, sample: Sample) {
        val device = devices[sample.deviceId]?.takeIf { it.generation == sample.generation } ?: return
        val hub = hubDevices[device.id] ?: return
        if (sample.action == CANCEL) { cancel(task, device.id); return }
        // A Wayland tablet frame is one evdev packet: the reader sees a frame's
        // proximity, contact and buttons together, as from the tablet's node.
        if (sample.action == FRAME) { streams[device.id]?.takeIf { it.task == task }?.flush(); return }
        var stream = streams[device.id]
        if (stream?.task != task) {
            // Releases from an earlier surface cannot end its successor's stream.
            if (sample.action == UP || sample.action == LEAVE || (sample.action == BUTTON && !sample.pressed)) return
            stream?.leave()
            stream = Stream(task, device, hub).also { streams[device.id] = it }
        }
        val point = android.point(task, sample.axes[0], sample.axes[1]) ?: return
        when (sample.action) {
            ENTER, MOVE -> stream.move(point, sample.axes)
            DOWN -> { stream.move(point, sample.axes); stream.contact(true) }
            UP -> { stream.move(point, sample.axes); stream.contact(false) }
            BUTTON -> { stream.move(point, sample.axes); stream.button(sample.button, sample.pressed) }
            LEAVE -> { stream.leave(); streams.remove(device.id) }
        }
    }

    fun inContact(): Boolean = streams.values.any { it.touching }
    fun inContact(task: TaskGeometry.Task, deviceId: Int = -1): Boolean = streams.any { (id, stream) ->
        stream.task == task && (deviceId == -1 || deviceId == id) && stream.touching
    }

    /** Cancels, not lifts: the tool's device is reset (WaylandTaskInput.DEVICE_RESET). */
    fun cancel(task: TaskGeometry.Task, deviceId: Int = -1) {
        for ((id, stream) in streams.toMap()) if (stream.task == task && (deviceId == -1 || deviceId == id)) {
            streams.remove(id)
            stream.leave(cancel = true)
        }
    }

    private inner class Stream(val task: TaskGeometry.Task, val device: Device, val hub: Int) {
        private var inProximity = false
        private var down = false
        val touching: Boolean get() = down
        private val buttons = mutableSetOf<Int>()
        private val pending = mutableListOf<Int>()
        private fun emit(vararg values: Int) { pending += values.asList() }
        fun flush() {
            if (pending.isEmpty()) return
            android.events(hub, (pending + listOf(EV_SYN, SYN_REPORT, 0)).toIntArray())
            pending.clear()
        }
        fun move(point: Pair<Float, Float>, axes: FloatArray) {
            val values = mutableListOf(EV_ABS, ABS_X, Math.round(point.first), EV_ABS, ABS_Y, Math.round(point.second))
            if (device.capabilities and PRESSURE != 0) values += listOf(EV_ABS, ABS_PRESSURE, Math.round(axes[2] * 65535))
            if (device.capabilities and DISTANCE != 0) values += listOf(EV_ABS, ABS_DISTANCE, Math.round(axes[3] * 65535))
            if (device.capabilities and TILT != 0) values += listOf(EV_ABS, ABS_TILT_X, Math.round(axes[4]), EV_ABS, ABS_TILT_Y, Math.round(axes[5]))
            if (!inProximity) { values += listOf(EV_KEY, device.tool, 1); inProximity = true }
            emit(*values.toIntArray())
        }
        fun contact(touching: Boolean) {
            if (down == touching) return
            down = touching
            emit(EV_KEY, BTN_TOUCH, if (touching) 1 else 0)
        }
        fun button(code: Int, pressed: Boolean) {
            if (code !in BUTTONS || (code in buttons) == pressed) return
            if (pressed) buttons += code else buttons -= code
            emit(EV_KEY, code, if (pressed) 1 else 0)
        }
        fun leave(cancel: Boolean = false) {
            val values = mutableListOf<Int>()
            if (cancel && down) { pending.clear(); values += WaylandTaskInput.DEVICE_RESET.asList() }
            for (code in buttons) values += listOf(EV_KEY, code, 0)
            buttons.clear()
            if (down) { values += listOf(EV_KEY, BTN_TOUCH, 0); down = false }
            if (inProximity) { values += listOf(EV_KEY, device.tool, 0); inProximity = false }
            emit(*values.toIntArray())
            flush()
        }
    }
    companion object {
        const val FIRST_DEVICE = 3
        const val MAX_TOOLS = 16
        const val PRESSURE = 1
        const val DISTANCE = 2
        const val TILT = 4
        const val ENTER = 0
        const val MOVE = 1
        const val DOWN = 2
        const val UP = 3
        const val BUTTON = 4
        const val LEAVE = 5
        const val CANCEL = 6
        const val FRAME = 7
        private const val EV_SYN = 0; private const val EV_KEY = 1; private const val EV_ABS = 3
        private const val SYN_REPORT = 0
        private const val ABS_X = 0x00; private const val ABS_Y = 0x01
        private const val ABS_PRESSURE = 0x18; private const val ABS_DISTANCE = 0x19
        private const val ABS_TILT_X = 0x1a; private const val ABS_TILT_Y = 0x1b
        private const val BTN_TOUCH = 0x14a
        // Stylus buttons and the pointer tools' mouse buttons.
        private val BUTTONS = setOf(0x14b, 0x14c, 0x110, 0x111, 0x112, 0x113, 0x114, 0x115, 0x116)
    }
}
