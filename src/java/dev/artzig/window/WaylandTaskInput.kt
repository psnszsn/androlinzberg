package dev.artzig.window

import android.os.SystemClock
import android.view.Display
import android.view.HostedInputDispatcher
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * The Wayland seat replaces InputReader's hardware discovery, not Android's
 * window routing. Follows pinned CursorInputMapper.sync (button ordering),
 * KeyboardInputMapper.processKey/reset (held keys and cancellation), and
 * MotionEvent's pointer-index contract. Stock InputDispatcher owns hit testing,
 * modal windows, touch splitting, focus delivery and untrusted-overlay checks.
 * All calls run on the task shell's serial executor.
 */
internal class WaylandTaskInput(private val android: Android,
    private val clock: () -> Long = SystemClock::uptimeMillis) {
    interface Android {
        fun focusTask(taskId: Int)
        fun keyboardActive(active: Boolean)
        /** Borrowed for this call; retain a copy if dispatch is asynchronous. */
        fun dispatch(event: InputEvent, repeatable: Boolean = true)
        fun keyboardLayout(map: KeyCharacterMap)
        fun repeatConfiguration(timeoutMillis: Int, delayMillis: Int, enabled: Boolean)
    }
    private data class Pressed(val owner: Window, val code: Int, val time: Long)
    private data class Contact(val owner: Window, val id: Int, var x: Float, var y: Float,
        var major: Float, var minor: Float, var orientation: Float)
    private val windows = mutableMapOf<Int, Window>()
    private val keys = linkedMapOf<Int, Pressed>()
    // Physical state outlives a delivered stream's cancellation (layout/repeat
    // updates), and includes keys pressed before this client's keyboard enter.
    private val physicalKeys = linkedMapOf<Int, Int>()
    private var persistentMeta = 0
    private val contacts = linkedMapOf<Int, Contact>()
    private var focus: Window? = null
    private var lockedMeta = 0
    private var compositorMeta: Int? = null

    /** Compositor snapshots include modifiers held before keyboard enter. */
    fun modifiers(value: Int, persistent: Int = 0) {
        persistentMeta = KeyEvent.normalizeMetaState(persistent)
        compositorMeta = KeyEvent.normalizeMetaState(value)
        lockedMeta = value and (KeyEvent.META_CAPS_LOCK_ON or KeyEvent.META_NUM_LOCK_ON or KeyEvent.META_SCROLL_LOCK_ON)
    }
    private var touchDownTime = 0L
    private var repeat: Pair<Int, Int>? = null
    private var layoutGeneration = 0
    private var layoutSymbols = IntArray(WaylandKeyboardMap.SCAN_COUNT)

    fun keyboardLayout(generation: Int, characters: IntArray, symbols: IntArray = IntArray(WaylandKeyboardMap.SCAN_COUNT)) {
        if (generation <= layoutGeneration) return
        val map = WaylandKeyboardMap.compile(characters, symbols)
        focus?.releaseKeys()
        android.keyboardLayout(map)
        layoutSymbols = symbols.copyOf()
        layoutGeneration = generation
        for (scan in physicalKeys.keys.toList()) physicalKeys[scan] = keyCode(scan)
    }

    fun repeatConfiguration(rate: Int, delayMillis: Int) {
        require(rate >= 0 && delayMillis >= 0)
        val configuration = rate to delayMillis
        if (repeat == configuration) return
        // End the old repeat stream before changing its policy. InputDispatcher
        // retains an active repeat's state across configuration updates.
        focus?.releaseKeys()
        repeat = configuration
        android.repeatConfiguration(delayMillis, if (rate == 0) 1 else maxOf(1, 1000 / rate), rate != 0)
    }

    fun open(task: TaskGeometry.Task): Window {
        check(windows[task.id] == null) { "Input task already exists" }
        return Window(task).also { windows[task.id] = it }
    }

    inner class Window internal constructor(val task: TaskGeometry.Task) : AutoCloseable {
        private var closed = false
        private var bounds: TaskGeometry.Bounds? = null
        private var output: WaylandTaskShell.Size? = null
        private var buttons = 0
        private var pointerDownTime = 0L
        private var pointerX = 0f
        private var pointerY = 0f
        private var hovering = false
        private var captured = false
        private fun live() = !closed && windows[task.id] === this

        fun committed(value: TaskGeometry.Bounds, size: WaylandTaskShell.Size) {
            if (!live()) return
            if (bounds != value || output != size) {
                cancelPointer()
                if (contacts.values.any { it.owner === this }) cancelTouch()
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
                physicalKeys.clear()
                for (scan in heldScanCodes) physicalKeys[scan] = keyCode(scan)
                android.keyboardActive(true)
                if (bounds != null) android.focusTask(task.id)
            } else {
                releaseKeys()
                if (focus === this) {
                    focus = null
                    physicalKeys.clear()
                    android.keyboardActive(false)
                }
            }
        }

        /** Surface-local coordinates use the same projection as TaskSurfaceOutput.
         * Gestures may extend beyond the surface, so do not clamp their samples.
         */
        private fun point(x: Float, y: Float): Pair<Float, Float>? {
            if (!live() || !x.isFinite() || !y.isFinite()) return null
            val b = bounds ?: return null
            val s = output ?: return null
            val px = b.left + x * (b.width.toFloat() / s.width)
            val py = b.top + y * (b.height.toFloat() / s.height)
            return if (px.isFinite() && py.isFinite()) px to py else null
        }

        fun pointer(kind: Int, button: Int, pressed: Boolean, x: Float, y: Float) {
            if (kind == POINTER_CANCEL) { cancelPointer(); return }
            if (captured) {
                if (!live() || kind != POINTER_BUTTON) return
                pointerX = 0f; pointerY = 0f
            } else {
                val p = point(x, y) ?: return
                pointerX = p.first; pointerY = p.second
            }
            when (kind) {
                POINTER_ENTER -> {
                    if (!hovering && buttons and DOWN_BUTTONS == 0) {
                        hovering = true; mouse(MotionEvent.ACTION_HOVER_ENTER)
                    }
                }
                POINTER_LEAVE -> {
                    if (hovering) { mouse(MotionEvent.ACTION_HOVER_EXIT); hovering = false }
                }
                POINTER_MOVE -> mouse(if (buttons and DOWN_BUTTONS != 0)
                    MotionEvent.ACTION_MOVE else MotionEvent.ACTION_HOVER_MOVE)
                POINTER_BUTTON -> {
                    val bit = when (button) {
                        0x110 -> MotionEvent.BUTTON_PRIMARY
                        0x111 -> MotionEvent.BUTTON_SECONDARY
                        0x112 -> MotionEvent.BUTTON_TERTIARY
                        0x113, 0x116 -> MotionEvent.BUTTON_BACK
                        0x114, 0x115 -> MotionEvent.BUTTON_FORWARD
                        else -> return
                    }
                    if ((buttons and bit != 0) == pressed) return
                    val wasDown = buttons and DOWN_BUTTONS != 0
                    buttons = if (pressed) buttons or bit else buttons and bit.inv()
                    val down = buttons and DOWN_BUTTONS != 0
                    if (!wasDown && down) pointerDownTime = clock()
                    val keyCode = when (bit) {
                        MotionEvent.BUTTON_BACK -> KeyEvent.KEYCODE_BACK
                        MotionEvent.BUTTON_FORWARD -> KeyEvent.KEYCODE_FORWARD
                        else -> 0
                    }
                    if (pressed && keyCode != 0) buttonKey(keyCode, true)
                    if (!pressed) mouse(MotionEvent.ACTION_BUTTON_RELEASE, bit)
                    val action = if (wasDown != down) {
                        if (down) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP
                    } else if (down || captured) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_HOVER_MOVE
                    mouse(action)
                    if (pressed) mouse(MotionEvent.ACTION_BUTTON_PRESS, bit)
                    if (action == MotionEvent.ACTION_UP && !captured) mouse(MotionEvent.ACTION_HOVER_MOVE)
                    if (!pressed && keyCode != 0) buttonKey(keyCode, false)
                }
                else -> throw IllegalArgumentException("Unknown pointer operation: $kind")
            }
        }

        fun scroll(x: Float, y: Float, horizontal: Float, vertical: Float) {
            if (!horizontal.isFinite() || !vertical.isFinite()) return
            if (captured) {
                if (!live()) return
                pointerX = 0f; pointerY = 0f
            } else {
                val p = point(x, y) ?: return
                pointerX = p.first; pointerY = p.second
            }
            mouse(MotionEvent.ACTION_SCROLL, horizontal = horizontal, vertical = vertical)
        }

        fun captureChanged(enabled: Boolean) {
            if (!live() || captured == enabled) return
            // InputReader resets the outgoing source before changing mode.
            cancelPointer()
            captured = enabled
            pointerX = 0f; pointerY = 0f
        }

        fun relativePointer(dx: Float, dy: Float) {
            if (!live() || !captured || focus !== this || !dx.isFinite() || !dy.isFinite()) return
            val b = bounds ?: return
            val s = output ?: return
            pointerX = dx * (b.width.toFloat() / s.width)
            pointerY = dy * (b.height.toFloat() / s.height)
            if (!pointerX.isFinite() || !pointerY.isFinite()) return
            mouse(MotionEvent.ACTION_MOVE)
            pointerX = 0f; pointerY = 0f
        }

        private fun mouse(action: Int, actionButton: Int = 0, horizontal: Float = 0f, vertical: Float = 0f) {
            val event = HostedInputDispatcher.createMouseEvent(pointerDownTime, clock(), action,
                buttons, actionButton, pointerX, pointerY, horizontal, vertical, metaState())
            if (captured) {
                event.source = InputDevice.SOURCE_MOUSE_RELATIVE
                val coordinates = MotionEvent.PointerCoords()
                event.getPointerCoords(0, coordinates)
                coordinates.setAxisValue(MotionEvent.AXIS_RELATIVE_X, pointerX)
                coordinates.setAxisValue(MotionEvent.AXIS_RELATIVE_Y, pointerY)
                // Use the stock MotionEvent constructor so relative axes and
                // X/Y are carried together through the native input channel.
                val relative = MotionEvent.obtain(pointerDownTime, event.eventTime, action, 1,
                    arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }),
                    arrayOf(coordinates), metaState(), buttons, 1f, 1f, event.deviceId, 0,
                    InputDevice.SOURCE_MOUSE_RELATIVE, 0)
                relative.actionButton = actionButton
                event.recycle()
                dispatch(relative)
            } else dispatch(event)
        }

        private fun buttonKey(code: Int, down: Boolean) = dispatch(KeyEvent(pointerDownTime, clock(),
            if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0, metaState(),
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM,
            if (captured) InputDevice.SOURCE_MOUSE_RELATIVE else InputDevice.SOURCE_MOUSE), false)

        fun cancelPointer() {
            if (buttons and DOWN_BUTTONS != 0) mouse(MotionEvent.ACTION_CANCEL)
            if (hovering) mouse(MotionEvent.ACTION_HOVER_EXIT)
            for ((button, key) in listOf(MotionEvent.BUTTON_BACK to KeyEvent.KEYCODE_BACK,
                MotionEvent.BUTTON_FORWARD to KeyEvent.KEYCODE_FORWARD)) {
                if (buttons and button != 0) buttonKey(key, false)
            }
            buttons = 0; hovering = false
        }

        fun key(scanCode: Int, down: Boolean, repeatable: Boolean = true) {
            if (!live() || focus !== this || bounds == null || scanCode !in 0..767) return
            var held = keys[scanCode]
            val code = held?.code ?: physicalKeys[scanCode] ?: keyCode(scanCode)
            if (down) {
                if (held == null && physicalKeys.containsKey(scanCode)) return
                physicalKeys[scanCode] = code
                if (held == null) {
                    held = Pressed(this, code, clock())
                    keys[scanCode] = held
                    lockedMeta = lockedMeta xor when (code) {
                        KeyEvent.KEYCODE_CAPS_LOCK -> KeyEvent.META_CAPS_LOCK_ON
                        KeyEvent.KEYCODE_NUM_LOCK -> KeyEvent.META_NUM_LOCK_ON
                        KeyEvent.KEYCODE_SCROLL_LOCK -> KeyEvent.META_SCROLL_LOCK_ON
                        else -> 0
                    }
                }
            } else {
                physicalKeys.remove(scanCode)
                keys.remove(scanCode)
            }
            // wl_keyboard may omit a modifiers event if the effective mask
            // stayed unchanged. Count all physical keys, including inherited
            // ones, and retain compositor latched/locked modifiers on key-up.
            compositorMeta?.let { current ->
                val bit = modifierGroup(code)
                val active = physicalKeys.values.any { modifierGroup(it) == bit } || persistentMeta and bit != 0
                compositorMeta = if (bit != 0) {
                    if (active) current or bit else current and bit.inv()
                } else (current and LOCKS.inv()) or lockedMeta
            }
            held ?: return // Update physical state without inventing a delivered key-up.
            dispatch(KeyEvent(held!!.time, clock(), if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                held.code, 0, metaState(), KeyCharacterMap.VIRTUAL_KEYBOARD, scanCode,
                KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD), repeatable)
        }

        internal fun releaseKeys() {
            for ((scan, held) in keys.toList().asReversed()) {
                if (held.owner !== this) continue
                keys.remove(scan)
                dispatch(KeyEvent(held.time, clock(), KeyEvent.ACTION_UP, held.code, 0, metaState(),
                    KeyCharacterMap.VIRTUAL_KEYBOARD, scan, KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_CANCELED,
                    InputDevice.SOURCE_KEYBOARD))
            }
        }

        fun touch(action: Int, id: Int, x: Float, y: Float, major: Float, minor: Float, orientation: Float) {
            val p = point(x, y) ?: return
            if (!major.isFinite() || !minor.isFinite() || !orientation.isFinite() || major < 0 || minor < 0) return
            var contact = contacts[id]
            if (action == 0) {
                if (contact != null || contacts.size == 10) return
                if (contacts.isEmpty()) touchDownTime = clock()
                val pointerId = (0..31).first { candidate -> contacts.values.none { it.id == candidate } }
                contact = Contact(this, pointerId, p.first, p.second, major, minor, orientation)
                contacts[id] = contact
            }
            contact ?: return
            if (contact.owner !== this) return
            contact.x = p.first; contact.y = p.second
            // Transform the ellipse covariance, including rotated contacts under
            // a nonuniform task projection. Scaling its two lengths separately
            // would rotate/distort the physical contact incorrectly.
            val b = bounds!!; val s = output!!
            val sx = b.width.toDouble() / s.width; val sy = b.height.toDouble() / s.height
            val sin = kotlin.math.sin(orientation.toDouble()); val cos = kotlin.math.cos(orientation.toDouble())
            val ux = major * sin * sx; val uy = major * cos * sy
            val vx = minor * cos * sx; val vy = -minor * sin * sy
            val xx = ux * ux + vx * vx; val yy = uy * uy + vy * vy; val xy = ux * uy + vx * vy
            val delta = kotlin.math.hypot(xx - yy, 2 * xy)
            contact.major = kotlin.math.sqrt(((xx + yy + delta) / 2).coerceAtLeast(0.0)).toFloat()
            contact.minor = kotlin.math.sqrt(((xx + yy - delta) / 2).coerceAtLeast(0.0)).toFloat()
            contact.orientation = (kotlin.math.atan2(2 * xy, yy - xx) / 2).toFloat()
            val index = contacts.keys.indexOf(id)
            val androidAction = when (action) {
                0 -> if (contacts.size == 1) MotionEvent.ACTION_DOWN else
                    MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                1 -> if (contacts.size == 1) MotionEvent.ACTION_UP else
                    MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                2, 3 -> MotionEvent.ACTION_MOVE
                else -> throw IllegalArgumentException("Unknown touch operation: $action")
            }
            touchEvent(androidAction)
            if (action == 1) contacts.remove(id)
        }

        override fun close() {
            if (!live()) return
            cancelPointer()
            releaseKeys()
            if (contacts.values.any { it.owner === this }) cancelTouch()
            if (focus === this) {
                focus = null
                physicalKeys.clear()
                android.keyboardActive(false)
            }
            windows.remove(task.id)
            closed = true
        }
    }

    fun cancelTouch() {
        if (contacts.isNotEmpty()) touchEvent(MotionEvent.ACTION_CANCEL)
        contacts.clear()
    }

    private fun touchEvent(action: Int) {
        val values = contacts.values.toList()
        val properties = values.map { contact -> MotionEvent.PointerProperties().apply {
            id = contact.id; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coordinates = values.map { contact -> MotionEvent.PointerCoords().apply {
            x = contact.x; y = contact.y; pressure = 1f; size = 1f
            touchMajor = contact.major; touchMinor = contact.minor; orientation = contact.orientation
        } }.toTypedArray()
        dispatch(MotionEvent.obtain(touchDownTime, clock(), action, values.size, properties, coordinates,
            metaState(), 0, 1f, 1f, 2, 0, InputDevice.SOURCE_TOUCHSCREEN, 0).apply {
            displayId = Display.DEFAULT_DISPLAY
        })
    }

    private fun keyCode(scan: Int): Int = if (layoutGeneration != 0)
        WaylandKeyboardMap.keyCode(scan, layoutSymbols.getOrElse(scan) { 0 }) else
        HostedInputDispatcher.androidKeyCodeForScanCode(scan)

    private fun metaState(): Int {
        var result = compositorMeta ?: lockedMeta
        for (code in physicalKeys.values) {
            if (compositorMeta != null && result and modifierGroup(code) == 0) continue
            result = result or when (code) {
                KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEvent.META_SHIFT_LEFT_ON
                KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_RIGHT_ON
                KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.META_CTRL_LEFT_ON
                KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_RIGHT_ON
                KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_LEFT_ON
                KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_RIGHT_ON
                KeyEvent.KEYCODE_META_LEFT -> KeyEvent.META_META_LEFT_ON
                KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.META_META_RIGHT_ON
                else -> 0
            }
        }
        return KeyEvent.normalizeMetaState(result)
    }

    private fun modifierGroup(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON
        KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.META_META_ON
        else -> 0
    }

    private fun dispatch(event: InputEvent, repeatable: Boolean = true) {
        event.displayId = Display.DEFAULT_DISPLAY
        try { android.dispatch(event, repeatable) } finally { event.recycle() }
    }

    companion object {
        const val POINTER_MOVE = 0
        const val POINTER_ENTER = 1
        const val POINTER_LEAVE = 2
        const val POINTER_BUTTON = 3
        const val POINTER_CANCEL = 4
        private const val LOCKS = KeyEvent.META_CAPS_LOCK_ON or KeyEvent.META_NUM_LOCK_ON or KeyEvent.META_SCROLL_LOCK_ON
        private const val DOWN_BUTTONS = MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_TERTIARY
    }
}
