package dev.artzig.window

/**
 * Assertions at the reader's device boundary: the evdev packets the Wayland
 * seat hands the host event hub. Stock InputReader turns these into Android
 * events; the UI tests cover that half.
 */
object WaylandTaskInputSmoke {
    private const val EV_SYN = 0; private const val EV_KEY = 1; private const val EV_REL = 2; private const val EV_ABS = 3

    private class Hub : WaylandTaskInput.Hub {
        val kinds = mutableMapOf<Int, Int>()
        val maps = mutableMapOf<Int, String?>()
        val packets = mutableListOf<Pair<Int, List<Int>>>()
        private var next = 1
        override fun addDevice(kind: Int, name: String, width: Int, height: Int, keyCharacterMap: String?, axes: Int): Int {
            check(kind != HostInputDispatcher.DEVICE_KEYBOARD || width == 0)
            check(kind == HostInputDispatcher.DEVICE_KEYBOARD || kind == HostInputDispatcher.DEVICE_MOUSE ||
                (width == 1920 && height == 1080)) { "Absolute device without the display's range" }
            return next++.also { kinds[it] = kind; maps[it] = keyCharacterMap }
        }
        override fun removeDevice(deviceId: Int) { checkNotNull(kinds.remove(deviceId)) }
        override fun setKeyCharacterMap(deviceId: Int, contents: String): Boolean {
            check(deviceId in kinds); maps[deviceId] = contents; return true
        }
        override fun events(deviceId: Int, whenNanos: Long, values: IntArray) {
            check(deviceId in kinds && values.size % 3 == 0)
            check(values.takeLast(3) == listOf(0, 0, 0)) { "Packet without SYN_REPORT" }
            packets.add(deviceId to values.dropLast(3))
        }
        fun device(kind: Int) = kinds.entries.single { it.value == kind }.key
        /** The packets since the last call, without their SYN_REPORT. */
        fun take(): List<Pair<Int, List<Int>>> = packets.toList().also { packets.clear() }
    }

    fun run() {
        val hub = Hub()
        val focus = mutableListOf<Int>()
        val repeats = mutableListOf<Triple<Int, Int, Boolean>>()
        var keyboardActive = false
        var hoverEnds = 0
        var time = 100L
        val seat = WaylandTaskInput(object : WaylandTaskInput.Android {
            override fun keyboardFocus(task: TaskGeometry.Task?) { keyboardActive = task != null }
            override fun focusTask(taskId: Int) { focus.add(taskId) }
            override fun repeatConfiguration(timeoutMillis: Int, delayMillis: Int, enabled: Boolean) {
                repeats.add(Triple(timeoutMillis, delayMillis, enabled))
            }
            override fun displaySize() = 1920 to 1080
            override fun endHover() { hoverEnds++ }
        }, { ++time }, hub)

        seat.seatCapabilities(7)
        val keyboard = hub.device(HostInputDispatcher.DEVICE_KEYBOARD)
        val mouse = hub.device(HostInputDispatcher.DEVICE_MOUSE)
        val touch = hub.device(HostInputDispatcher.DEVICE_TOUCHSCREEN)
        seat.seatCapabilities(7)
        check(hub.kinds.size == 3)

        seat.repeatConfiguration(50, 300)
        seat.repeatConfiguration(50, 300)
        check(repeats == listOf(Triple(300, 20, true)))
        seat.repeatConfiguration(0, 300)
        check(repeats.last().third == false)

        val characters = IntArray(WaylandKeyboardMap.SCAN_COUNT * WaylandKeyboardMap.STATE_COUNT)
        for (state in 0 until WaylandKeyboardMap.STATE_COUNT) characters[30 * 64 + state] = 'q'.code
        seat.keyboardLayout(2, characters)
        check(hub.maps[keyboard]!!.startsWith("type FULL") && "key A {" in hub.maps[keyboard]!!)
        seat.keyboardLayout(1, IntArray(1)) // Stale generation: not parsed, not applied.

        val first = seat.open(TaskGeometry.Task(1, 1))
        val second = seat.open(TaskGeometry.Task(2, 2))
        first.pointer(WaylandTaskInput.POINTER_BUTTON, 0x110, true, 10f, 20f)
        first.keyboardFocus(true, intArrayOf(29, 30))
        check(focus.isEmpty() && keyboardActive)
        // Ctrl held across enter keeps its meaning; A was pressed for another client.
        check(hub.take() == listOf(keyboard to listOf(EV_KEY, 29, 1)))
        first.key(31, true)
        check(hub.take().isEmpty()) // No delivery before a committed projection.
        first.committed(TaskGeometry.Bounds(100, 200, 400, 300), WaylandTaskShell.Size(200, 150))
        second.committed(TaskGeometry.Bounds(1000, 400, 400, 300), WaylandTaskShell.Size(200, 150))
        check(focus == listOf(1))

        // The mouse cursor is pinned to the origin once, then moved by exact deltas.
        first.pointer(WaylandTaskInput.POINTER_ENTER, 0, false, 10f, 20f)
        check(hub.take() == listOf(mouse to listOf(EV_REL, 0, -(1 shl 20), EV_REL, 1, -(1 shl 20)),
            mouse to listOf(EV_REL, 0, 120, EV_REL, 1, 240)))
        first.pointer(WaylandTaskInput.POINTER_MOVE, 0, false, 10f, 20f)
        check(hub.take().isEmpty())
        second.pointer(WaylandTaskInput.POINTER_BUTTON, 0x111, true, 0f, 0f)
        second.pointer(WaylandTaskInput.POINTER_BUTTON, 0x111, true, 0f, 0f)
        check(hub.take() == listOf(mouse to listOf(EV_REL, 0, 880, EV_REL, 1, 160), mouse to listOf(EV_KEY, 0x111, 1)))
        second.scroll(0f, 0f, 0f, .5f)
        second.scroll(0f, 0f, .004f, 0f) // Below one hi-res unit: carried, not sent.
        check(hub.take() == listOf(mouse to listOf(EV_REL, 0x0b, 60, EV_REL, 0x0c, 0)))
        // Another task's resize or close leaves this gesture alone.
        first.cancelPointer()
        check(hub.take().isEmpty())
        // Leaving mid-gesture keeps the compositor's grab; nothing ends.
        second.pointer(WaylandTaskInput.POINTER_LEAVE, 0, false, 0f, 0f)
        check(hoverEnds == 0 && hub.take().isEmpty())
        // An early end resets the mouse alone (Android cancels its gesture) and
        // releases the button inside the dropped packet.
        second.pointer(WaylandTaskInput.POINTER_CANCEL, 0, false, 0f, 0f)
        check(hub.take() == listOf(mouse to listOf(EV_SYN, 3, 0, EV_KEY, 0x111, 0)))
        second.pointer(WaylandTaskInput.POINTER_CANCEL, 0, false, 0f, 0f)
        check(hub.take().isEmpty())
        // A grab reaches past the display; the model follows Android's clamped cursor.
        first.pointer(WaylandTaskInput.POINTER_MOVE, 0, false, -80f, 20f)
        check(hub.take() == listOf(mouse to listOf(EV_REL, 0, -1000, EV_REL, 1, -160)))
        first.pointer(WaylandTaskInput.POINTER_MOVE, 0, false, 10f, 20f)
        check(hub.take() == listOf(mouse to listOf(EV_REL, 0, 120, EV_REL, 1, 0)))
        // Leaving for the desktop ends Android's hover; re-entering where it
        // left re-announces hover with a pixel round trip.
        first.pointer(WaylandTaskInput.POINTER_LEAVE, 0, false, 10f, 20f)
        check(hoverEnds == 1 && hub.take().isEmpty())
        first.pointer(WaylandTaskInput.POINTER_ENTER, 0, false, 10f, 20f)
        check(hub.take() == listOf(mouse to listOf(EV_REL, 0, 1, EV_REL, 1, 0), mouse to listOf(EV_REL, 0, -1, EV_REL, 1, 0)))
        first.pointer(WaylandTaskInput.POINTER_ENTER, 0, false, 10f, 20f)
        check(hub.take().isEmpty())

        // Keys reach the reader only from the focused task and end with its focus.
        second.key(30, true)
        first.key(30, true)
        first.key(30, true)
        check(hub.take() == listOf(keyboard to listOf(EV_KEY, 30, 1)))
        second.keyboardFocus(true)
        check(hub.take() == listOf(keyboard to listOf(EV_KEY, 30, 0), keyboard to listOf(EV_KEY, 29, 0)))
        first.key(30, false)
        check(hub.take().isEmpty())

        // A lock set while another client had the keyboard is replayed as its
        // key; the reader's own lock-key edges keep the two in agreement.
        seat.modifiers(android.view.KeyEvent.META_CAPS_LOCK_ON)
        check(hub.take() == listOf(keyboard to listOf(EV_KEY, 58, 1), keyboard to listOf(EV_KEY, 58, 0)))
        seat.modifiers(android.view.KeyEvent.META_CAPS_LOCK_ON)
        check(hub.take().isEmpty())
        second.key(58, true)
        second.key(58, false)
        check(hub.take() == listOf(keyboard to listOf(EV_KEY, 58, 1), keyboard to listOf(EV_KEY, 58, 0)))
        seat.modifiers(0)
        check(hub.take().isEmpty())
        // A latched Shift applies to the next key, as XKB's sticky modifiers do.
        seat.modifiers(0, android.view.KeyEvent.META_SHIFT_ON)
        second.key(30, true)
        second.key(30, false)
        check(hub.take() == listOf(keyboard to listOf(EV_KEY, 42, 1), keyboard to listOf(EV_KEY, 30, 1),
            keyboard to listOf(EV_KEY, 30, 0), keyboard to listOf(EV_KEY, 42, 0)))
        // Overlapping keys share the synthetic Shift, and a physical Shift joins it.
        second.key(30, true)
        second.key(31, true)
        second.key(30, false)
        second.key(31, false)
        check(hub.take() == listOf(keyboard to listOf(EV_KEY, 42, 1), keyboard to listOf(EV_KEY, 30, 1),
            keyboard to listOf(EV_KEY, 31, 1), keyboard to listOf(EV_KEY, 30, 0),
            keyboard to listOf(EV_KEY, 31, 0), keyboard to listOf(EV_KEY, 42, 0)))
        second.key(30, true)
        second.key(42, true)
        second.key(30, false)
        second.key(42, false)
        check(hub.take() == listOf(keyboard to listOf(EV_KEY, 42, 1), keyboard to listOf(EV_KEY, 30, 1),
            keyboard to listOf(EV_KEY, 30, 0), keyboard to listOf(EV_KEY, 42, 0)))
        seat.modifiers(0)

        // Multitouch: slots and tracking IDs per contact, BTN_TOUCH for the first and last.
        first.touch(0, 7, 10f, 20f, 2f, 1f, 0f)
        val down = hub.take().single()
        check(down.first == touch && down.second.take(9) == listOf(EV_ABS, 0x2f, 0, EV_ABS, 0x39, 0, EV_ABS, 0x37, 0))
        check(down.second.drop(9) == listOf(EV_ABS, 0x2f, 0, EV_ABS, 0x35, 120, EV_ABS, 0x36, 240,
            EV_ABS, 0x30, 4, EV_ABS, 0x31, 2, EV_ABS, 0x34, 0, EV_KEY, 0x14a, 1))
        first.touch(0, 8, 20f, 20f, 0f, 0f, 0f)
        check(hub.take().single().second.subList(3, 6) == listOf(EV_ABS, 0x39, 1))
        check(first.nativeTouchId(1) == 8 && second.nativeTouchId(1) == null)
        second.touch(2, 8, 0f, 0f, 0f, 0f, 0f) // Another task cannot move this contact.
        check(hub.take().isEmpty())
        first.touch(1, 7, 10f, 20f, 0f, 0f, 0f)
        check(hub.take().single().second.takeLast(3) == listOf(EV_ABS, 0x39, -1))
        first.cancelTouches()
        check(hub.take() == listOf(touch to listOf(EV_ABS, 0x2f, 1, EV_ABS, 0x37, 2),
            touch to listOf(EV_ABS, 0x2f, 1, EV_ABS, 0x39, -1, EV_KEY, 0x14a, 0)))

        // Tablet tools: one drawing-tablet device each, proximity through BTN_TOOL_*.
        val pen = WaylandTabletInput.Device(3, 1, 0x140, WaylandTabletInput.PRESSURE or WaylandTabletInput.TILT)
        seat.tabletDevice(pen, true)
        val stylus = hub.device(HostInputDispatcher.DEVICE_STYLUS)
        val axes = floatArrayOf(10f, 20f, .5f, .2f, 30f, -15f, 90f, -.5f, 0f)
        fun sample(action: Int) = WaylandTabletInput.Sample(pen.id, pen.generation, action, 0, false, axes)
        fun frame(window: WaylandTaskInput.Window, vararg actions: Int) {
            for (action in actions) window.tablet(sample(action))
            window.tablet(sample(WaylandTabletInput.FRAME))
        }
        first.tablet(sample(WaylandTabletInput.ENTER))
        check(hub.take().isEmpty()) // A frame is one packet.
        first.tablet(sample(WaylandTabletInput.FRAME))
        check(hub.take() == listOf(stylus to listOf(EV_ABS, 0, 120, EV_ABS, 1, 240, EV_ABS, 0x18, 32768,
            EV_ABS, 0x1a, 30, EV_ABS, 0x1b, -15, EV_KEY, 0x140, 1)))
        frame(first, WaylandTabletInput.DOWN)
        check(hub.take().single().second.takeLast(3) == listOf(EV_KEY, 0x14a, 1))
        frame(second, WaylandTabletInput.UP) // A release from another surface.
        check(hub.take().isEmpty())
        first.cancelTablet()
        check(hub.take() == listOf(stylus to listOf(EV_SYN, 3, 0, EV_KEY, 0x14a, 0, EV_KEY, 0x140, 0)))
        // Removing a tool in contact leaves the cancel to the reader's device reset.
        frame(first, WaylandTabletInput.DOWN)
        hub.take()
        seat.tabletDevice(pen, false)
        check(hub.take().isEmpty() && HostInputDispatcher.DEVICE_STYLUS !in hub.kinds.values)

        // Pointer capture: the compositor's relative motion, scaled to the display.
        first.keyboardFocus(true)
        hub.take()
        first.captureChanged(true)
        first.relativePointer(1.25f, 0f)
        first.relativePointer(.25f, -1f)
        first.pointer(WaylandTaskInput.POINTER_MOVE, 0, false, 50f, 50f)
        check(hub.take() == listOf(mouse to listOf(EV_REL, 0, 2, EV_REL, 1, 0), mouse to listOf(EV_REL, 0, 1, EV_REL, 1, -2)))
        second.relativePointer(5f, 5f)
        check(hub.take().isEmpty())
        first.captureChanged(false)
        first.relativePointer(5f, 5f)
        check(hub.take().isEmpty())

        first.close(); second.close()
        seat.seatCapabilities(0)
        check(hub.kinds.isEmpty())
        println("Wayland task input: seat devices, cursor pinning, buttons, scroll, key focus, multitouch, tablet and capture packets passed")
    }
}
