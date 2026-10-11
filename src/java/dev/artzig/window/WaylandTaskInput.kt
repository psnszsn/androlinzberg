package dev.artzig.window

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * The Wayland seat as evdev devices for stock InputReader. The compositor owns
 * physical devices and per-surface delivery; this translates each task
 * surface's seat events into raw input_event packets on the session's host
 * event hub ([Hub], HostEventHub in the session), where stock mappers, PointerChoreographer,
 * InputFilter and InputDispatcher produce and route every Android event.
 *
 * What remains here is the seat's task semantics: surface-to-display projection,
 * event generations, keyboard focus, and keeping each evdev device's state
 * consistent with Wayland enter/leave. The pointer is a relative mouse whose
 * deltas reproduce the compositor's absolute positions: the session disables
 * Android mouse scaling for its display (InputManagerInternal.setMouseScalingEnabled),
 * and the device's cursor model is pinned once to the display origin.
 * All calls run on the task shell's serial executor.
 */
internal class WaylandTaskInput(private val android: Android,
    private val clock: () -> Long = SystemClock::uptimeNanos,
    private val hub: Hub = HostHub) {
    /** The reader's device source: HostEventHub in the session. */
    interface Hub {
        fun addDevice(kind: Int, name: String, width: Int = 0, height: Int = 0, keyCharacterMap: String? = null, axes: Int = 0): Int
        fun removeDevice(deviceId: Int)
        fun setKeyCharacterMap(deviceId: Int, contents: String): Boolean
        /** (type, code, value) triples ending in SYN_REPORT. */
        fun events(deviceId: Int, whenNanos: Long, values: IntArray)
    }
    private object HostHub : Hub {
        override fun addDevice(kind: Int, name: String, width: Int, height: Int, keyCharacterMap: String?, axes: Int) =
            HostInputDispatcher.addDevice(kind, name, width, height, keyCharacterMap, axes)
        override fun removeDevice(deviceId: Int) = HostInputDispatcher.removeDevice(deviceId)
        override fun setKeyCharacterMap(deviceId: Int, contents: String) = HostInputDispatcher.setKeyCharacterMap(deviceId, contents)
        override fun events(deviceId: Int, whenNanos: Long, values: IntArray) = HostInputDispatcher.events(deviceId, whenNanos, values)
    }
    interface Android {
        fun focusTask(taskId: Int)
        fun keyboardFocus(task: TaskGeometry.Task?)
        /** Wayland's key repeat policy for the stock dispatcher. */
        fun repeatConfiguration(timeoutMillis: Int, delayMillis: Int, enabled: Boolean)
        /** The Android display's size, the range of absolute devices. */
        fun displaySize(): Pair<Int, Int>
        /** Stock InputManagerService.cancelCurrentTouch, which ends hover with
         * HOVER_EXIT. Called only while no device has an active gesture. */
        fun endHover()
    }

    private val windows = mutableMapOf<Int, Window>()
    private var focus: Window? = null
    private var layoutGeneration = 0
    private var keyCharacterMap: String? = null
    private var repeat: Pair<Int, Int>? = null

    // Seat devices on the host event hub, created from wl_seat capabilities.
    private var keyboard = -1
    private var mouse = -1
    private var touchscreen = -1
    // Keys whose down edge reached the reader, and their window.
    private val sentKeys = linkedMapOf<Int, Window>()
    // The compositor's modifier state the reader's keyboard must agree with:
    // its lock states, and modifiers latched or locked without a held key.
    private var layoutSymbols = IntArray(WaylandKeyboardMap.SCAN_COUNT)
    private var compositorLocks = 0
    private var readerLocks = 0
    private var persistentModifiers = 0
    // Modifier presses held for a key that a latched or locked modifier applies
    // to, and how many such keys hold each modifier down in the reader.
    private val persistentHeld = mutableMapOf<Int, List<Int>>()
    private val syntheticHolds = mutableMapOf<Int, Int>()
    private val unavailableLocks = mutableSetOf<Int>()
    // The mouse device's cursor model, integer display pixels, once pinned.
    private var cursor: Pair<Int, Int>? = null
    private var mouseButtons = 0
    // The window whose press started the mouse gesture (Wayland's implicit grab).
    private var buttonOwner: Window? = null
    // Android's hover ended when the pointer left; the next enter restores it.
    private var hoverEnded = false
    private var wheelRemainder = 0f
    private var hwheelRemainder = 0f
    private var relativeRemainder = 0f to 0f
    private data class Contact(val owner: Window, val slot: Int, val pointerId: Int)
    private val contacts = linkedMapOf<Int, Contact>()
    private var nextTrackingId = 0
    private val tablets = WaylandTabletInput(object : WaylandTabletInput.Android {
        override fun point(task: TaskGeometry.Task, x: Float, y: Float) =
            windows[task.id]?.takeIf { it.task == task }?.surfacePoint(x, y)
        override fun displaySize() = android.displaySize()
        override fun addDevice(name: String, width: Int, height: Int, axes: Int) =
            hub.addDevice(HostInputDispatcher.DEVICE_STYLUS, name, width, height, axes = axes)
        override fun removeDevice(deviceId: Int) = hub.removeDevice(deviceId)
        override fun events(deviceId: Int, values: IntArray) = hub.events(deviceId, clock(), values)
    })
    fun tabletDeviceGeneration(id: Int): Int? = tablets.deviceGeneration(id)
    fun tabletDevice(device: WaylandTabletInput.Device, present: Boolean) = tablets.device(device, present)

    /** wl_seat capabilities: pointer 1, keyboard 2, touch 4. */
    fun seatCapabilities(capabilities: Int) {
        val (width, height) = android.displaySize()
        if (capabilities and 2 != 0 && keyboard < 0) {
            keyboard = hub.addDevice(HostInputDispatcher.DEVICE_KEYBOARD, "Wayland keyboard",
                keyCharacterMap = keyCharacterMap)
            readerLocks = 0 // A new device's keyboard mapper starts unlocked.
            syncLocks()
        } else if (capabilities and 2 == 0 && keyboard >= 0) {
            releaseAllKeys()
            hub.removeDevice(keyboard); keyboard = -1
        }
        if (capabilities and 1 != 0 && mouse < 0) {
            mouse = hub.addDevice(HostInputDispatcher.DEVICE_MOUSE, "Wayland pointer")
            cursor = null; mouseButtons = 0; buttonOwner = null
        } else if (capabilities and 1 == 0 && mouse >= 0) {
            hub.removeDevice(mouse); mouse = -1; cursor = null; mouseButtons = 0; buttonOwner = null
        }
        if (capabilities and 4 != 0 && touchscreen < 0) {
            touchscreen = hub.addDevice(HostInputDispatcher.DEVICE_TOUCHSCREEN, "Wayland touch", width, height)
        } else if (capabilities and 4 == 0 && touchscreen >= 0) {
            contacts.clear()
            hub.removeDevice(touchscreen); touchscreen = -1
        }
    }

    /** XKB's effective meta state and its latched/locked modifiers. The
     * reader's keyboard mapper keeps lock state from lock-key edges alone, so a
     * lock set while another client had focus is replayed as that lock key's
     * press, as the keyboard itself would have delivered it. */
    fun modifiers(value: Int, persistent: Int = 0) {
        compositorLocks = value and LOCKS
        persistentModifiers = persistent
        syncLocks()
    }

    /** A scan code the current layout maps to an Android key code. */
    private fun scanFor(keyCode: Int): Int? = (0 until WaylandKeyboardMap.SCAN_COUNT).firstOrNull {
        WaylandKeyboardMap.keyCode(it, layoutSymbols[it]) == keyCode
    }
    private fun lockBit(scan: Int): Int = when (WaylandKeyboardMap.keyCode(scan, layoutSymbols[scan])) {
        KeyEvent.KEYCODE_CAPS_LOCK -> KeyEvent.META_CAPS_LOCK_ON
        KeyEvent.KEYCODE_NUM_LOCK -> KeyEvent.META_NUM_LOCK_ON
        else -> 0
    }

    /** Lock keys reach the focused Android window, so they are replayed only
     * while one of the session's tasks has the compositor's keyboard. */
    private fun syncLocks() {
        if (keyboard < 0 || focus == null) return
        for ((bit, keyCode) in LOCK_KEYS) {
            if ((compositorLocks xor readerLocks) and bit == 0) continue
            val scan = scanFor(keyCode)
            if (scan == null) {
                if (unavailableLocks.add(keyCode)) Log.w("WaylandSeat", "Lock state not synchronized",
                    dev.artzig.ArtzigTestDiagnostics.unsupported("WaylandSeat", "modifiers",
                        "The compositor keymap has no key for ${KeyEvent.keyCodeToString(keyCode)}"))
                continue
            }
            if (scan in sentKeys) continue // Its own release follows; the edge already toggled.
            emit(keyboard, EV_KEY, scan, 1)
            emit(keyboard, EV_KEY, scan, 0)
            readerLocks = readerLocks xor bit
        }
    }

    /** The key's down edge, preceded by presses of the latched or locked
     * modifiers that apply to it but are not held (XKB sticky modifiers). */
    /** Overlapping keys share a synthetic modifier press, and a physical
     * press of that modifier joins it: the reader sees one down and one up. */
    private fun pressKey(scan: Int) {
        val code = WaylandKeyboardMap.keyCode(scan, layoutSymbols[scan])
        if (!KeyEvent.isModifierKey(code)) {
            val held = PERSISTENT_KEYS.filter { (bit, _) -> persistentModifiers and bit == bit }
                .mapNotNull { (_, keyCode) -> scanFor(keyCode) }
                .filter { it !in sentKeys || it in syntheticHolds }
            for (modifier in held) {
                val holds = syntheticHolds[modifier] ?: 0
                if (holds == 0 && modifier !in sentKeys) emit(keyboard, EV_KEY, modifier, 1)
                syntheticHolds[modifier] = holds + 1
            }
            if (held.isNotEmpty()) persistentHeld[scan] = held
        }
        if (scan in syntheticHolds) return // Already down in the reader for another key.
        emit(keyboard, EV_KEY, scan, 1)
        readerLocks = readerLocks xor lockBit(scan)
    }
    /** Callers remove [scan] from the sent keys first. */
    private fun releaseKey(scan: Int) {
        if (scan !in syntheticHolds) emit(keyboard, EV_KEY, scan, 0)
        for (modifier in persistentHeld.remove(scan).orEmpty().asReversed()) {
            val holds = syntheticHolds.getValue(modifier) - 1
            if (holds > 0) { syntheticHolds[modifier] = holds; continue }
            syntheticHolds.remove(modifier)
            if (modifier !in sentKeys) emit(keyboard, EV_KEY, modifier, 0)
        }
    }

    fun keyboardLayout(generation: Int, characters: IntArray, symbols: IntArray = IntArray(WaylandKeyboardMap.SCAN_COUNT)) {
        if (generation <= layoutGeneration) return
        val contents = WaylandKeyboardMap.compileText(characters, symbols)
        // A changed layout re-adds the reader's keyboard: end its key streams first.
        releaseAllKeys()
        keyCharacterMap = contents
        layoutGeneration = generation
        layoutSymbols = symbols.copyOf()
        if (keyboard >= 0) {
            check(hub.setKeyCharacterMap(keyboard, contents)) {
                "Compositor keymap rejected by the stock KeyCharacterMap parser"
            }
            readerLocks = 0
            syncLocks()
        }
    }

    fun repeatConfiguration(rate: Int, delayMillis: Int) {
        require(rate >= 0 && delayMillis >= 0)
        val configuration = rate to delayMillis
        if (repeat == configuration) return
        repeat = configuration
        android.repeatConfiguration(delayMillis, if (rate == 0) 1 else maxOf(1, 1000 / rate), rate != 0)
    }

    fun open(task: TaskGeometry.Task): Window {
        check(windows[task.id] == null) { "Input task already exists" }
        return Window(task).also { windows[task.id] = it }
    }

    private fun emit(device: Int, vararg values: Int) {
        if (device < 0) return
        hub.events(device, clock(), values + intArrayOf(EV_SYN, SYN_REPORT, 0))
    }

    private fun releaseAllKeys() {
        for (scan in sentKeys.keys.toList().asReversed()) {
            sentKeys.remove(scan)
            releaseKey(scan)
        }
    }

    inner class Window internal constructor(val task: TaskGeometry.Task) : AutoCloseable {
        private var closed = false
        private var bounds: TaskGeometry.Bounds? = null
        private var output: WaylandTaskShell.Size? = null
        private var captured = false
        private fun live() = !closed && windows[task.id] === this

        fun committed(value: TaskGeometry.Bounds, size: WaylandTaskShell.Size) {
            if (!live()) return
            if (bounds != value || output != size) {
                cancelPointer()
                cancelTouches()
                cancelTablet()
            }
            bounds = value; output = size
            if (focus === this) android.focusTask(task.id)
        }

        fun keyboardFocus(gained: Boolean, heldScanCodes: IntArray = IntArray(0)) {
            if (!live()) return
            if (gained) {
                require(heldScanCodes.size <= 768 && heldScanCodes.all { it in 0..767 }) { "Invalid keyboard enter keys" }
                if (focus !== this) {
                    focus?.releaseKeys()
                    focus = this
                }
                android.keyboardFocus(task)
                if (bounds != null) android.focusTask(task.id)
                // Modifiers held across the enter keep their meaning; other
                // held keys were pressed for another client and stay silent.
                for (scan in heldScanCodes) if (scan in MODIFIER_SCANS && scan !in sentKeys) {
                    sentKeys[scan] = this
                    if (scan !in syntheticHolds) emit(keyboard, EV_KEY, scan, 1)
                }
                syncLocks()
            } else {
                releaseKeys()
                if (focus === this) {
                    focus = null
                    android.keyboardFocus(null)
                }
            }
        }

        /** Surface-local coordinates use the same projection as TaskSurfaceOutput.
         * Gestures may extend beyond the surface, so do not clamp their samples. */
        fun surfacePoint(x: Float, y: Float): Pair<Float, Float>? {
            if (!live() || !x.isFinite() || !y.isFinite()) return null
            val b = bounds ?: return null
            val s = output ?: return null
            val px = b.left + x * (b.width.toFloat() / s.width)
            val py = b.top + y * (b.height.toFloat() / s.height)
            return if (px.isFinite() && py.isFinite()) px to py else null
        }

        fun tablet(sample: WaylandTabletInput.Sample) { if (live()) tablets.sample(task, sample) }
        fun cancelTablet(deviceId: Int = -1) = tablets.cancel(task, deviceId)

        /** Moves the reader's cursor to a display point with exact deltas;
         * false when it is already there. */
        private fun moveCursor(x: Float, y: Float): Boolean {
            // MouseCursorController clamps to the display; so must the model
            // the next delta is computed from (grabs reach past the task).
            val (width, height) = android.displaySize()
            val target = Math.round(x).coerceIn(0, width - 1) to Math.round(y).coerceIn(0, height - 1)
            val current = cursor ?: run {
                // Clamping to the display resolves this to its origin exactly.
                emit(mouse, EV_REL, REL_X, -PIN_DISTANCE, EV_REL, REL_Y, -PIN_DISTANCE)
                (0 to 0).also { cursor = it }
            }
            val dx = target.first - current.first
            val dy = target.second - current.second
            if (dx == 0 && dy == 0) return false
            emit(mouse, EV_REL, REL_X, dx, EV_REL, REL_Y, dy)
            cursor = target
            hoverEnded = false
            return true
        }

        fun pointer(kind: Int, button: Int, pressed: Boolean, x: Float, y: Float) {
            if (kind == POINTER_CANCEL) { cancelPointer(); return }
            if (!live() || mouse < 0) return
            if (!captured) {
                val p = surfacePoint(x, y) ?: return
                // The reader reports no zero motion: re-entering where the
                // pointer left re-announces hover with a one-pixel round trip.
                if (!moveCursor(p.first, p.second) && kind == POINTER_ENTER && hoverEnded) {
                    val step = if (cursor!!.first < android.displaySize().first - 1) 1 else -1
                    emit(mouse, EV_REL, REL_X, step, EV_REL, REL_Y, 0)
                    emit(mouse, EV_REL, REL_X, -step, EV_REL, REL_Y, 0)
                    hoverEnded = false
                }
            }
            when (kind) {
                POINTER_ENTER, POINTER_MOVE -> Unit
                // The cursor left for the desktop, where Android has no display
                // position to move it to. The dispatcher's cancellation ends its
                // hover; it is global, so only while no device has a gesture (a
                // device reset here would also end hover, but the next drag from
                // that device then reaches WM's drag window offset).
                POINTER_LEAVE -> if (mouseButtons == 0 && contacts.isEmpty() && !tablets.inContact()) {
                    android.endHover()
                    hoverEnded = true
                }
                POINTER_BUTTON -> {
                    if (button !in MOUSE_BUTTONS) return
                    val bit = 1 shl (button - BTN_LEFT)
                    if ((mouseButtons and bit != 0) == pressed) return
                    if (mouseButtons == 0) buttonOwner = this
                    mouseButtons = if (pressed) mouseButtons or bit else mouseButtons and bit.inv()
                    if (mouseButtons == 0) buttonOwner = null
                    emit(mouse, EV_KEY, button, if (pressed) 1 else 0)
                }
                else -> throw IllegalArgumentException("Unknown pointer operation: $kind")
            }
        }

        fun scroll(x: Float, y: Float, horizontal: Float, vertical: Float) {
            if (!horizontal.isFinite() || !vertical.isFinite() || !live() || mouse < 0) return
            if (!captured) {
                val p = surfacePoint(x, y) ?: return
                moveCursor(p.first, p.second)
            }
            // Hi-resolution wheels report 1/120 detents (input-event-codes.h).
            wheelRemainder += vertical * 120f
            hwheelRemainder += horizontal * 120f
            val v = wheelRemainder.toInt(); val h = hwheelRemainder.toInt()
            wheelRemainder -= v; hwheelRemainder -= h
            if (v != 0 || h != 0) emit(mouse, EV_REL, REL_WHEEL_HI_RES, v, EV_REL, REL_HWHEEL_HI_RES, h)
        }

        fun captureChanged(enabled: Boolean) {
            if (!live() || captured == enabled) return
            // The reader changes the mouse mode itself; end this window's
            // button stream before the compositor's coordinates stop.
            cancelPointer()
            captured = enabled
            relativeRemainder = 0f to 0f
        }

        fun relativePointer(dx: Float, dy: Float) {
            if (!live() || !captured || focus !== this || !dx.isFinite() || !dy.isFinite() || mouse < 0) return
            val b = bounds ?: return
            val s = output ?: return
            val x = relativeRemainder.first + dx * (b.width.toFloat() / s.width)
            val y = relativeRemainder.second + dy * (b.height.toFloat() / s.height)
            val ix = x.toInt(); val iy = y.toInt()
            relativeRemainder = (x - ix) to (y - iy)
            if (ix != 0 || iy != 0) emit(mouse, EV_REL, REL_X, ix, EV_REL, REL_Y, iy)
        }

        /** Only the gesture's own window ends it; another task's resize or
         * close leaves it alone. */
        fun cancelPointer() {
            if (mouseButtons == 0 || buttonOwner !== this) return
            val releases = MOUSE_BUTTONS.filter { mouseButtons and (1 shl (it - BTN_LEFT)) != 0 }
                .flatMap { listOf(EV_KEY, it, 0) }
            emit(mouse, *(DEVICE_RESET + releases))
            mouseButtons = 0
            buttonOwner = null
        }

        fun key(scanCode: Int, down: Boolean, @Suppress("UNUSED_PARAMETER") repeatable: Boolean = true) {
            if (!live() || focus !== this || scanCode !in 0..767 || keyboard < 0) return
            if (bounds == null) return // No delivery before the task has a projection.
            if (down) {
                if (scanCode in sentKeys) return
                sentKeys[scanCode] = this
                pressKey(scanCode)
            } else {
                if (sentKeys.remove(scanCode) == null) return
                releaseKey(scanCode)
            }
        }

        internal fun releaseKeys() {
            for ((scan, owner) in sentKeys.toList().asReversed()) {
                if (owner !== this) continue
                sentKeys.remove(scan)
                releaseKey(scan)
            }
        }

        /** The reader assigns Android pointer IDs as the lowest free ID per new
         * contact (MultiTouchInputMapper tracking IDs); never resolve another
         * task's contact. */
        fun nativeTouchId(pointerId: Int): Int? {
            if (!live()) return null
            return contacts.entries.firstOrNull { it.value.owner === this && it.value.pointerId == pointerId }?.key
        }

        fun touch(action: Int, id: Int, x: Float, y: Float, major: Float, minor: Float, orientation: Float) {
            val p = surfacePoint(x, y) ?: return
            if (!major.isFinite() || !minor.isFinite() || !orientation.isFinite() || major < 0 || minor < 0) return
            if (touchscreen < 0) return
            var contact = contacts[id]
            if (action == 0) {
                if (contact != null || contacts.size == MAX_CONTACTS) return
                val slot = (0 until MAX_CONTACTS).first { candidate -> contacts.values.none { it.slot == candidate } }
                val pointerId = (0..31).first { candidate -> contacts.values.none { it.pointerId == candidate } }
                contact = Contact(this, slot, pointerId)
                contacts[id] = contact
            }
            contact ?: return
            if (contact.owner !== this) return
            // Transform the ellipse covariance under a nonuniform task
            // projection, as the surface's physical contact would appear.
            val b = bounds!!; val s = output!!
            val sx = b.width.toDouble() / s.width; val sy = b.height.toDouble() / s.height
            val sin = kotlin.math.sin(orientation.toDouble()); val cos = kotlin.math.cos(orientation.toDouble())
            val ux = major * sin * sx; val uy = major * cos * sy
            val vx = minor * cos * sx; val vy = -minor * sin * sy
            val xx = ux * ux + vx * vx; val yy = uy * uy + vy * vy; val xy = ux * uy + vx * vy
            val delta = kotlin.math.hypot(xx - yy, 2 * xy)
            val projectedMajor = kotlin.math.sqrt(((xx + yy + delta) / 2).coerceAtLeast(0.0))
            val projectedMinor = kotlin.math.sqrt(((xx + yy - delta) / 2).coerceAtLeast(0.0))
            val degrees = Math.toDegrees(kotlin.math.atan2(2 * xy, yy - xx) / 2)
            val position = intArrayOf(EV_ABS, ABS_MT_SLOT, contact.slot,
                EV_ABS, ABS_MT_POSITION_X, Math.round(p.first), EV_ABS, ABS_MT_POSITION_Y, Math.round(p.second),
                EV_ABS, ABS_MT_TOUCH_MAJOR, Math.round(projectedMajor).toInt(),
                EV_ABS, ABS_MT_TOUCH_MINOR, Math.round(projectedMinor).toInt(),
                EV_ABS, ABS_MT_ORIENTATION, Math.round(degrees).toInt().coerceIn(-90, 90))
            when (action) {
                0 -> emit(touchscreen, *(intArrayOf(EV_ABS, ABS_MT_SLOT, contact.slot,
                    EV_ABS, ABS_MT_TRACKING_ID, nextTrackingId++ and 0xffff, EV_ABS, ABS_MT_TOOL_TYPE, MT_TOOL_FINGER) +
                    position + (if (contacts.size == 1) intArrayOf(EV_KEY, BTN_TOUCH, 1) else IntArray(0))))
                1 -> {
                    emit(touchscreen, *(position + intArrayOf(EV_ABS, ABS_MT_TRACKING_ID, -1) +
                        (if (contacts.size == 1) intArrayOf(EV_KEY, BTN_TOUCH, 0) else IntArray(0))))
                    contacts.remove(id)
                }
                2, 3 -> emit(touchscreen, *position)
                else -> throw IllegalArgumentException("Unknown touch operation: $action")
            }
        }

        /** A palm-classified contact is how the reader cancels a touch
         * (TouchInputMapper: FLAG_CANCELED POINTER_UP, or CANCEL for the last). */
        fun cancelTouches() {
            for ((id, contact) in contacts.filterValues { it.owner === this }.toList()) {
                emit(touchscreen, EV_ABS, ABS_MT_SLOT, contact.slot, EV_ABS, ABS_MT_TOOL_TYPE, MT_TOOL_PALM)
                contacts.remove(id)
                emit(touchscreen, *(intArrayOf(EV_ABS, ABS_MT_SLOT, contact.slot, EV_ABS, ABS_MT_TRACKING_ID, -1) +
                    (if (contacts.isEmpty()) intArrayOf(EV_KEY, BTN_TOUCH, 0) else IntArray(0))))
            }
        }

        override fun close() {
            if (!live()) return
            cancelPointer()
            releaseKeys()
            cancelTouches()
            cancelTablet()
            if (focus === this) {
                focus = null
                android.keyboardFocus(null)
            }
            windows.remove(task.id)
            closed = true
        }
    }

    fun cancelTouch() {
        for (window in windows.values.toList()) window.cancelTouches()
    }

    companion object {
        const val POINTER_MOVE = 0
        const val POINTER_ENTER = 1
        const val POINTER_LEAVE = 2
        const val POINTER_BUTTON = 3
        const val POINTER_CANCEL = 4
        // linux/input-event-codes.h
        const val EV_SYN = 0; const val EV_KEY = 1; const val EV_REL = 2; const val EV_ABS = 3
        const val SYN_REPORT = 0; const val SYN_DROPPED = 3
        /** Ends a device's gestures alone: the reader drops the packet and resets
         * the device (InputDevice's buffer-overrun recovery), and the dispatcher
         * cancels that device's events, hover included (notifyDeviceReset).
         * Releases in the same packet bring its state to rest silently. */
        val DEVICE_RESET = intArrayOf(EV_SYN, SYN_DROPPED, 0)
        const val REL_X = 0x00; const val REL_Y = 0x01
        const val REL_WHEEL_HI_RES = 0x0b; const val REL_HWHEEL_HI_RES = 0x0c
        const val BTN_LEFT = 0x110; const val BTN_TOUCH = 0x14a
        const val ABS_MT_SLOT = 0x2f; const val ABS_MT_TOUCH_MAJOR = 0x30; const val ABS_MT_TOUCH_MINOR = 0x31
        const val ABS_MT_ORIENTATION = 0x34; const val ABS_MT_POSITION_X = 0x35; const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TOOL_TYPE = 0x37; const val ABS_MT_TRACKING_ID = 0x39
        const val MT_TOOL_FINGER = 0; const val MT_TOOL_PALM = 2
        private const val MAX_CONTACTS = 10
        private const val PIN_DISTANCE = 1 shl 20
        // BTN_LEFT, RIGHT, MIDDLE, SIDE, EXTRA, FORWARD, BACK.
        private val MOUSE_BUTTONS = 0x110..0x116
        // Generic.kl modifier positions: Ctrl, Shift, Alt and Meta, left and right.
        private val MODIFIER_SCANS = setOf(29, 42, 54, 56, 97, 100, 125, 126)
        private const val LOCKS = KeyEvent.META_CAPS_LOCK_ON or KeyEvent.META_NUM_LOCK_ON
        private val LOCK_KEYS = listOf(
            KeyEvent.META_CAPS_LOCK_ON to KeyEvent.KEYCODE_CAPS_LOCK,
            KeyEvent.META_NUM_LOCK_ON to KeyEvent.KEYCODE_NUM_LOCK)
        // The presenter's persistent meta bits (XKB Shift, Control, Mod1, Mod5, Mod4).
        private val PERSISTENT_KEYS = listOf(
            KeyEvent.META_SHIFT_ON to KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.META_CTRL_ON to KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.META_ALT_LEFT_ON to KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.META_ALT_RIGHT_ON to KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.META_META_ON to KeyEvent.KEYCODE_META_LEFT)
    }
}
