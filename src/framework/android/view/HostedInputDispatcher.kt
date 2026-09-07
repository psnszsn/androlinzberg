package android.view

import android.os.SystemClock
import dev.artzig.HostedWindowStack

/**
 * Translates host pointer devices into Android MotionEvents and dispatches them at the hosted
 * ViewRootImpl seam.
 *
 * This follows AOSP ViewRootImpl.ViewPostImeInputStage.processPointerEvent(): pressed mouse
 * pointers and touchscreen contacts both enter View.dispatchPointerEvent(), while their source,
 * tool type, and button state remain distinct. The stock input stages cannot be compiled because
 * the host has no InputChannel, InputEventReceiver, or window-session input registration.
 */
object HostedInputDispatcher {
    /**
     * AOSP ViewRootImpl.enqueueInputEvent/doProcessInputEvents ordering and ownership.
     * The host has no InputChannel receiver; delivery uses the existing post-IME root seam.
     * A single asynchronous drain preserves arrival order even for non-monotonic timestamps
     * and events enqueued from an input callback. Pending events are recycled on root detach.
     */
    class EventQueue(private val root: ViewRootImpl) {
        private val handler = android.os.Handler.createAsync(HostedRootInput.handler(root).looper)
        private class Pending(val event: InputEvent, val unhandled: Boolean, val receiver: InputEventReceiver?)
        private val events = java.util.ArrayDeque<Pending>()
        private var scheduled = false
        private val drain = Runnable {
            while (true) {
                val pending = synchronized(events) {
                    events.pollFirst() ?: run {
                        scheduled = false
                        return@Runnable
                    }
                }
                val event = pending.event
                var handled = false
                try {
                    if (root.view != null) {
                        if (pending.unhandled) {
                            HostedRootInput.unhandled(root, event)
                        } else handled = when (event) {
                            is KeyEvent -> HostedRootInput.deliver(root, event)
                            is MotionEvent -> {
                                if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
                                    HostedRootInput.deliver(root, event)
                                } else if (root.hasPointerCapture()) {
                                    HostedRootInput.deliver(root, event)
                                } else {
                                    root.view?.dispatchGenericMotionEvent(event) ?: false
                                }
                            }
                            else -> false
                        }
                    }
                } finally {
                    if (pending.receiver != null) pending.receiver.finishInputEvent(event, handled)
                    else event.recycleIfNeededAfterDispatch()
                }
            }
        }

        fun enqueue(event: InputEvent) {
            enqueue(event, false, null)
        }

        fun enqueueUnhandled(event: InputEvent) {
            enqueue(event, true, null)
        }

        fun enqueue(event: InputEvent, receiver: InputEventReceiver?) {
            enqueue(event, false, receiver)
        }

        private fun enqueue(event: InputEvent, unhandled: Boolean, receiver: InputEventReceiver?) {
            synchronized(events) {
                events.addLast(Pending(event, unhandled, receiver))
                if (!scheduled) {
                    scheduled = true
                    handler.post(drain)
                }
            }
        }

        fun clear() {
            synchronized(events) {
                handler.removeCallbacks(drain)
                while (events.isNotEmpty()) {
                    val pending = events.removeFirst()
                    if (pending.receiver != null) pending.receiver.finishInputEvent(pending.event, false)
                    else pending.event.recycleIfNeededAfterDispatch()
                }
                scheduled = false
            }
        }
    }

    /**
     * Creates the character map installed on the hosted physical keyboard.
     *
     * AOSP TextKeyListener.getKeyListener() selects its editing behavior from the character-map
     * type. EventHub normally loads a FULL map from the device configuration, but the host has no
     * EventHub. The native seam uses AOSP KeyCharacterMap::loadContents() to preserve that contract;
     * characters still come from Wayland/XKB in HostedKeyEvent so the compositor's layout wins.
     */
    @JvmStatic
    fun createHostKeyCharacterMap(): KeyCharacterMap =
        nativeLoadKeyCharacterMap(KeyCharacterMap.VIRTUAL_KEYBOARD, "type FULL\n")

    @JvmStatic
    private external fun nativeLoadKeyCharacterMap(
        deviceId: Int,
        contents: String,
    ): KeyCharacterMap

    private class GestureState {
        var downTime: Long = 0
        var target: RootTarget? = null
        var lastX: Float = 0f
        var lastY: Float = 0f
        var buttonState: Int = 0
        val outsideWatchers = mutableListOf<HostedWindowStack.Entry>()

        fun reset() {
            downTime = 0
            target = null
            lastX = 0f
            lastY = 0f
            buttonState = 0
            outsideWatchers.clear()
        }
    }

    private data class TouchContact(
        val androidId: Int,
        var x: Float,
        var y: Float,
        var major: Float,
        var minor: Float,
        var orientation: Float,
    )

    private data class PressedKey(
        val root: ViewRootImpl?,
        val downTime: Long,
        val keyCode: Int,
        var repeatCount: Int,
    )

    private val mouseGesture = GestureState()
    private val touchGesture = GestureState()
    private var hoverTarget: RootTarget? = null
    private val touchContacts = linkedMapOf<Int, TouchContact>()
    private val pressedKeys = linkedMapOf<Int, PressedKey>()
    private var requestedCaptureRoot: ViewRootImpl? = null
    private var capturedRoot: ViewRootImpl? = null
    private var pointerCaptureActual = false
    private var lockedMetaState = 0
    private val digitKeyCodes = intArrayOf(
        KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3,
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6,
        KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9,
        KeyEvent.KEYCODE_0,
    )
    private val qwertyKeyCodes = intArrayOf(
        KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_E,
        KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_Y,
        KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_O,
        KeyEvent.KEYCODE_P,
    )
    private val homeRowKeyCodes = intArrayOf(
        KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D,
        KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H,
        KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L,
    )
    private val bottomRowKeyCodes = intArrayOf(
        KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C,
        KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_N,
        KeyEvent.KEYCODE_M,
    )

    @JvmStatic
    fun dispatchMouse(
        activityRoot: ViewRootImpl?,
        action: Int,
        buttonState: Int,
        actionButton: Int,
        screenX: Float,
        screenY: Float,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) {
            beginGesture(
                mouseGesture, now, activityRoot, screenX, screenY,
            )
        }
        mouseGesture.lastX = screenX
        mouseGesture.lastY = screenY
        mouseGesture.buttonState = buttonState
        val event = createMouseEvent(
            mouseGesture.downTime.takeIf { it != 0L } ?: now,
            now,
            action,
            buttonState,
            actionButton,
            screenX,
            screenY,
        )
        val handled = try {
            if (action == MotionEvent.ACTION_DOWN) dispatchOutsideWatchers(mouseGesture, event)
            dispatchGesture(activityRoot, mouseGesture, event)
        } finally {
            event.recycle()
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mouseGesture.reset()
            }
        }
        // AOSP CursorInputMapper::sync() follows a mouse ACTION_UP with HOVER_MOVE so the view
        // hierarchy re-enters hover state at the release location. Without it, the hosted stream
        // leaves the view hierarchy in a different post-drag state than a physical Android mouse.
        if (action == MotionEvent.ACTION_UP) {
            dispatchHover(activityRoot, MotionEvent.ACTION_HOVER_MOVE, screenX, screenY)
        }
        return handled
    }

    @JvmStatic
    fun cancelMouse(activityRoot: ViewRootImpl?) {
        val lastX = mouseGesture.lastX
        val lastY = mouseGesture.lastY
        if (mouseGesture.downTime != 0L) {
            dispatchMouse(
                activityRoot,
                MotionEvent.ACTION_CANCEL,
                mouseGesture.buttonState,
                0,
                lastX,
                lastY,
            )
        }
        hoverTarget?.let {
            dispatchHoverTo(it, MotionEvent.ACTION_HOVER_EXIT, lastX, lastY)
        }
        hoverTarget = null
    }

    @JvmStatic
    fun dispatchTouch(
        activityRoot: ViewRootImpl?,
        action: Int,
        screenX: Float,
        screenY: Float,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) {
            beginGesture(
                touchGesture, now, activityRoot, screenX, screenY,
            )
        }
        val event = createTouchEvent(
            touchGesture.downTime.takeIf { it != 0L } ?: now,
            now,
            action,
            screenX,
            screenY,
        )
        return try {
            if (action == MotionEvent.ACTION_DOWN) dispatchOutsideWatchers(touchGesture, event)
            dispatchGesture(activityRoot, touchGesture, event)
        } finally {
            event.recycle()
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touchGesture.reset()
            }
        }
    }

    /**
     * Translates wl_touch's contact lifecycle into one Android touchscreen gesture. Pointer ids
     * stay stable for the lifetime of each Wayland contact, and every pointer in the gesture is
     * delivered to the root selected by the first DOWN.
     */
    @JvmStatic
    fun dispatchTouchContact(
        activityRoot: ViewRootImpl?,
        action: Int,
        waylandId: Int,
        screenX: Float,
        screenY: Float,
        major: Float,
        minor: Float,
        orientation: Float,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        var actionIndex = -1
        val androidAction = when (action) {
            TOUCH_DOWN -> {
                if (touchContacts.containsKey(waylandId)) return false
                if (touchContacts.isEmpty()) {
                    beginGesture(touchGesture, now, activityRoot, screenX, screenY)
                }
                val androidId = (0 until MAX_TOUCH_CONTACTS).firstOrNull { candidate ->
                    touchContacts.values.none { it.androidId == candidate }
                } ?: return false
                touchContacts[waylandId] = TouchContact(
                    androidId, screenX, screenY, major, minor, orientation,
                )
                actionIndex = touchContacts.keys.indexOf(waylandId)
                if (touchContacts.size == 1) MotionEvent.ACTION_DOWN else {
                    MotionEvent.ACTION_POINTER_DOWN or
                        (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                }
            }
            TOUCH_MOVE, TOUCH_UPDATE -> {
                val contact = touchContacts[waylandId] ?: return false
                contact.x = screenX
                contact.y = screenY
                contact.major = major
                contact.minor = minor
                contact.orientation = orientation
                MotionEvent.ACTION_MOVE
            }
            TOUCH_UP -> {
                val contact = touchContacts[waylandId] ?: return false
                contact.x = screenX
                contact.y = screenY
                contact.major = major
                contact.minor = minor
                contact.orientation = orientation
                actionIndex = touchContacts.keys.indexOf(waylandId)
                if (touchContacts.size == 1) MotionEvent.ACTION_UP else {
                    MotionEvent.ACTION_POINTER_UP or
                        (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                }
            }
            else -> return false
        }

        val event = createMultiTouchEvent(
            touchGesture.downTime.takeIf { it != 0L } ?: now,
            now,
            androidAction,
            touchContacts.values.toList(),
        )
        return try {
            if (androidAction == MotionEvent.ACTION_DOWN) {
                dispatchOutsideWatchers(touchGesture, event)
            }
            dispatchGesture(activityRoot, touchGesture, event)
        } finally {
            event.recycle()
            if (action == TOUCH_UP) touchContacts.remove(waylandId)
            if (touchContacts.isEmpty()) touchGesture.reset()
        }
    }

    @JvmStatic
    fun cancelTouch(activityRoot: ViewRootImpl?) {
        if (touchContacts.isNotEmpty()) {
            val now = SystemClock.uptimeMillis()
            val event = createMultiTouchEvent(
                touchGesture.downTime.takeIf { it != 0L } ?: now,
                now,
                MotionEvent.ACTION_CANCEL,
                touchContacts.values.toList(),
            )
            try {
                dispatchGesture(activityRoot, touchGesture, event)
            } finally {
                event.recycle()
            }
        }
        touchContacts.clear()
        touchGesture.reset()
    }

    /**
     * AOSP EventHub/KeyboardInputMapper adapted at the host boundary. Wayland already supplies
     * the evdev scan code and XKB-resolved Unicode value, so this preserves Android KeyEvent
     * lifecycle and metadata without recreating Waydroid's Linux input FIFOs and InputFlinger.
     */
    @JvmStatic
    fun dispatchPhysicalKey(
        activityRoot: ViewRootImpl?,
        action: Int,
        scanCode: Int,
        unicode: Int,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val keyCode = androidKeyCodeForScanCode(scanCode)
        val root: ViewRootImpl?
        val downTime: Long
        val repeatCount: Int
        if (action == KeyEvent.ACTION_DOWN) {
            val existing = pressedKeys[scanCode]
            if (existing == null) {
                root = selectKeyRoot(activityRoot)
                downTime = now
                repeatCount = 0
                lockedMetaState = lockedMetaState xor lockMetaStateForKey(keyCode)
                pressedKeys[scanCode] = PressedKey(root, downTime, keyCode, repeatCount)
            } else {
                root = existing.root
                downTime = existing.downTime
                existing.repeatCount += 1
                repeatCount = existing.repeatCount
            }
        } else {
            val existing = pressedKeys.remove(scanCode)
            root = existing?.root ?: selectKeyRoot(activityRoot)
            downTime = existing?.downTime ?: now
            repeatCount = 0
        }
        val event = HostedKeyEvent(
            downTime,
            now,
            action,
            keyCode,
            repeatCount,
            currentMetaState(),
            scanCode,
            if (action == KeyEvent.ACTION_DOWN) unicode else 0,
        )
        return root?.let { HostedRootInput.deliver(it, event) } ?: false
    }

    @JvmStatic
    fun dispatchSyntheticKey(
        activityRoot: ViewRootImpl?,
        action: Int,
        keyCode: Int,
        unicode: Int,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val event = HostedKeyEvent(
            now,
            now,
            action,
            keyCode,
            0,
            0,
            0,
            if (action == KeyEvent.ACTION_DOWN) unicode else 0,
        )
        return selectKeyRoot(activityRoot)?.let { HostedRootInput.deliver(it, event) } ?: false
    }

    @JvmStatic
    fun resetKeyboard() {
        val releases = pressedKeys.toList().asReversed()
        val now = SystemClock.uptimeMillis()
        for ((scanCode, pressed) in releases) {
            pressedKeys.remove(scanCode)
            val event = HostedKeyEvent(
                pressed.downTime,
                now,
                KeyEvent.ACTION_UP,
                pressed.keyCode,
                0,
                currentMetaState(),
                scanCode,
                0,
                KeyEvent.FLAG_CANCELED,
            )
            pressed.root?.let { HostedRootInput.deliver(it, event) }
        }
    }

    @JvmStatic
    fun requestPointerCapture(root: ViewRootImpl, enabled: Boolean) {
        if (enabled) {
            requestedCaptureRoot = root
            if (pointerCaptureActual && capturedRoot !== root) {
                capturedRoot?.let { HostedRootInput.capture(it, false) }
                capturedRoot = root
                HostedRootInput.capture(root, true)
            }
        } else if (requestedCaptureRoot === root) {
            requestedCaptureRoot = null
        }
    }

    @JvmStatic
    fun wantsPointerCapture(): Boolean = requestedCaptureRoot != null

    @JvmStatic
    fun onPointerCaptureChanged(hasCapture: Boolean) {
        pointerCaptureActual = hasCapture
        val next = if (hasCapture) requestedCaptureRoot else null
        if (capturedRoot === next) return
        capturedRoot?.let { HostedRootInput.capture(it, false) }
        capturedRoot = next
        capturedRoot?.let { HostedRootInput.capture(it, true) }
    }

    @JvmStatic
    fun onRootDestroyed(root: ViewRootImpl) {
        if (requestedCaptureRoot === root) requestedCaptureRoot = null
        if (capturedRoot === root) {
            HostedRootInput.capture(root, false)
            capturedRoot = null
        }
        if (mouseGesture.target?.root === root) cancelMouse(root)
        if (touchGesture.target?.root === root) cancelTouch(root)
        if (hoverTarget?.root === root) {
            dispatchHoverTo(
                checkNotNull(hoverTarget),
                MotionEvent.ACTION_HOVER_EXIT,
                mouseGesture.lastX,
                mouseGesture.lastY,
            )
            hoverTarget = null
        }
        pressedKeys.entries.removeAll { it.value.root === root }
    }

    @JvmStatic
    fun dispatchCapturedPointer(dx: Float, dy: Float, buttonState: Int): Boolean {
        val root = capturedRoot ?: return false
        val now = SystemClock.uptimeMillis()
        val event = createRelativeMouseEvent(
            now, MotionEvent.ACTION_MOVE, dx, dy, buttonState, 0,
        )
        return try {
            HostedRootInput.deliver(root, event)
        } finally {
            event.recycle()
        }
    }

    @JvmStatic
    fun dispatchCapturedMouse(
        action: Int,
        buttonState: Int,
        actionButton: Int,
    ): Boolean {
        val root = capturedRoot ?: return false
        val event = createRelativeMouseEvent(
            SystemClock.uptimeMillis(), action, 0f, 0f, buttonState, actionButton,
        )
        return try {
            HostedRootInput.deliver(root, event)
        } finally {
            event.recycle()
        }
    }

    @JvmStatic
    fun dispatchHover(
        activityRoot: ViewRootImpl?,
        action: Int,
        screenX: Float,
        screenY: Float,
    ): Int {
        mouseGesture.lastX = screenX
        mouseGesture.lastY = screenY
        if (action == MotionEvent.ACTION_HOVER_EXIT) {
            hoverTarget?.let {
                dispatchHoverTo(it, MotionEvent.ACTION_HOVER_EXIT, screenX, screenY)
            }
            hoverTarget = null
            return PointerIcon.TYPE_ARROW
        }

        val target = targetAt(activityRoot, screenX, screenY)
        val previous = hoverTarget
        if (previous != null && previous != target) {
            dispatchHoverTo(previous, MotionEvent.ACTION_HOVER_EXIT, screenX, screenY)
        }
        val targetAction = if (previous == target && action != MotionEvent.ACTION_HOVER_ENTER) {
            MotionEvent.ACTION_HOVER_MOVE
        } else {
            MotionEvent.ACTION_HOVER_ENTER
        }
        dispatchHoverTo(target, targetAction, screenX, screenY)
        hoverTarget = target
        return target.root?.let(HostedRootInput::cursorType) ?: PointerIcon.TYPE_ARROW
    }

    @JvmStatic
    fun dispatchScroll(
        activityRoot: ViewRootImpl?,
        screenX: Float,
        screenY: Float,
        horizontal: Float,
        vertical: Float,
    ): Boolean {
        val target = targetAt(activityRoot, screenX, screenY)
        val now = SystemClock.uptimeMillis()
        val event = createMouseEvent(
            downTime = now,
            eventTime = now,
            action = MotionEvent.ACTION_SCROLL,
            buttonState = 0,
            actionButton = 0,
            x = target.localX(screenX),
            y = target.localY(screenY),
            horizontalScroll = horizontal,
            verticalScroll = vertical,
        )
        return try {
            target.root?.let { HostedRootInput.deliver(it, event) } ?: false
        } finally {
            event.recycle()
        }
    }

    @JvmStatic
    fun reset() {
        cancelMouse(null)
        cancelTouch(null)
        resetKeyboard()
        capturedRoot?.let { HostedRootInput.capture(it, false) }
        capturedRoot = null
        pointerCaptureActual = false
        requestedCaptureRoot = null
        mouseGesture.reset()
        touchGesture.reset()
        hoverTarget = null
    }

    private fun beginGesture(
        state: GestureState,
        now: Long,
        activityRoot: ViewRootImpl?,
        screenX: Float,
        screenY: Float,
    ) {
        state.reset()
        state.downTime = now
        val route = selectGestureRoute(activityRoot, screenX, screenY)
        state.target = route.target
        state.outsideWatchers += route.outsideWatchers
    }

    private fun dispatchGesture(
        activityRoot: ViewRootImpl?,
        state: GestureState,
        event: MotionEvent,
    ): Boolean {
        val target = state.target ?: RootTarget(activityRoot, null)
        return target.dispatch(event)
    }

    private fun dispatchOutsideWatchers(state: GestureState, downEvent: MotionEvent) {
        for (watcher in state.outsideWatchers) {
            val outsideEvent = MotionEvent.obtain(downEvent)
            try {
                outsideEvent.action = MotionEvent.ACTION_OUTSIDE
                outsideEvent.offsetLocation(
                    -watcher.frame.left.toFloat(),
                    -watcher.frame.top.toFloat(),
                )
                HostedRootInput.deliver(watcher.root, outsideEvent)
            } finally {
                outsideEvent.recycle()
            }
        }
    }

    private fun dispatchHoverTo(
        target: RootTarget,
        action: Int,
        screenX: Float,
        screenY: Float,
    ) {
        val now = SystemClock.uptimeMillis()
        val event = createMouseEvent(
            downTime = now,
            eventTime = now,
            action = action,
            buttonState = 0,
            actionButton = 0,
            x = target.localX(screenX),
            y = target.localY(screenY),
        )
        try {
            target.root?.let { HostedRootInput.deliver(it, event) }
        } finally {
            event.recycle()
        }
    }

    private data class RootTarget(
        val root: ViewRootImpl?,
        val window: HostedWindowStack.Entry?,
    ) {
        fun dispatch(event: MotionEvent): Boolean {
            window?.let {
                event.offsetLocation(-it.frame.left.toFloat(), -it.frame.top.toFloat())
            }
            return root?.let { HostedRootInput.deliver(it, event) } ?: false
        }

        fun localX(screenX: Float): Float = screenX - (window?.frame?.left ?: 0)
        fun localY(screenY: Float): Float = screenY - (window?.frame?.top ?: 0)
    }

    private data class GestureRoute(
        val target: RootTarget,
        val outsideWatchers: List<HostedWindowStack.Entry>,
    )

    /**
     * AOSP InputDispatcher::findTouchedWindowTargetsLocked() adapted to the in-process window
     * stack. The native dispatcher cannot be reused without SurfaceFlinger window handles and
     * input channels.
     */
    private fun selectGestureRoute(
        activityRoot: ViewRootImpl?,
        screenX: Float,
        screenY: Float,
    ): GestureRoute {
        val outsideWatchers = mutableListOf<HostedWindowStack.Entry>()
        val entries = HostedWindowStack.entries()
        for (index in entries.indices.reversed()) {
            val entry = entries[index]
            if ((entry.lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) continue
            if (entry.frame.contains(screenX.toInt(), screenY.toInt())) {
                return GestureRoute(RootTarget(entry.root, entry), outsideWatchers)
            }
            if (entry.lp.isModal) {
                return GestureRoute(RootTarget(entry.root, entry), outsideWatchers)
            }
            if ((entry.lp.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH) != 0) {
                outsideWatchers += entry
            }
        }
        return GestureRoute(RootTarget(activityRoot, null), outsideWatchers)
    }

    private fun targetAt(
        activityRoot: ViewRootImpl?,
        screenX: Float,
        screenY: Float,
    ): RootTarget {
        return selectGestureRoute(
            activityRoot, screenX, screenY,
        ).target
    }

    private fun selectKeyRoot(activityRoot: ViewRootImpl?): ViewRootImpl? {
        for (entry in HostedWindowStack.entries().asReversed()) {
            if ((entry.lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) {
                return entry.root
            }
        }
        return activityRoot
    }

    internal fun createMouseEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        buttonState: Int,
        actionButton: Int,
        x: Float,
        y: Float,
        horizontalScroll: Float = 0f,
        verticalScroll: Float = 0f,
    ): MotionEvent = createEvent(
        downTime,
        eventTime,
        action,
        InputDevice.SOURCE_MOUSE,
        MotionEvent.TOOL_TYPE_MOUSE,
        buttonState,
        actionButton,
        x,
        y,
        horizontalScroll,
        verticalScroll,
    )

    internal fun createTouchEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent = createEvent(
        downTime,
        eventTime,
        action,
        InputDevice.SOURCE_TOUCHSCREEN,
        MotionEvent.TOOL_TYPE_FINGER,
        0,
        0,
        x,
        y,
        0f,
        0f,
    )

    private fun createMultiTouchEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        contacts: List<TouchContact>,
    ): MotionEvent {
        val properties = Array(contacts.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = contacts[index].androidId
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(contacts.size) { index ->
            val contact = contacts[index]
            MotionEvent.PointerCoords().apply {
                x = contact.x
                y = contact.y
                pressure = 1f
                touchMajor = contact.major
                touchMinor = contact.minor
                orientation = contact.orientation
            }
        }
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            contacts.size,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    private fun createRelativeMouseEvent(
        eventTime: Long,
        action: Int,
        dx: Float,
        dy: Float,
        buttonState: Int,
        actionButton: Int,
    ): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = dx
            y = dy
            setAxisValue(MotionEvent.AXIS_RELATIVE_X, dx)
            setAxisValue(MotionEvent.AXIS_RELATIVE_Y, dy)
        }
        return MotionEvent.obtain(
            eventTime,
            eventTime,
            action,
            1,
            arrayOf(properties),
            arrayOf(coordinates),
            0,
            buttonState,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_MOUSE_RELATIVE,
            0,
        ).apply {
            if (action == MotionEvent.ACTION_BUTTON_PRESS ||
                action == MotionEvent.ACTION_BUTTON_RELEASE
            ) {
                setActionButton(actionButton)
            }
        }
    }

    private fun createEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        source: Int,
        toolType: Int,
        buttonState: Int,
        actionButton: Int,
        x: Float,
        y: Float,
        horizontalScroll: Float,
        verticalScroll: Float,
    ): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            this.toolType = toolType
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            // AOSP CursorInputMapper reports pressure 1 while a primary/secondary/tertiary mouse
            // button holds the pointer down, and 0 once the pointer returns to hover.
            pressure = if (buttonState and POINTER_DOWN_BUTTONS != 0) 1f else 0f
            setAxisValue(MotionEvent.AXIS_HSCROLL, horizontalScroll)
            setAxisValue(MotionEvent.AXIS_VSCROLL, verticalScroll)
        }
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            arrayOf(properties),
            arrayOf(coordinates),
            0,
            buttonState,
            1f,
            1f,
            0,
            0,
            source,
            0,
        ).apply {
            if (action == MotionEvent.ACTION_BUTTON_PRESS ||
                action == MotionEvent.ACTION_BUTTON_RELEASE
            ) {
                setActionButton(actionButton)
            }
        }
    }

    private class HostedKeyEvent : KeyEvent {
        private val unicode: Int
        constructor(downTime: Long, eventTime: Long, action: Int, keyCode: Int,
            repeatCount: Int, metaState: Int, scanCode: Int, unicode: Int, flags: Int = 0) :
            super(downTime, eventTime, action, keyCode, repeatCount, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, scanCode, flags, InputDevice.SOURCE_KEYBOARD) {
            this.unicode = unicode
        }
        // KeyEvent's copy constructor preserves event ID, nanosecond timestamps,
        // display, source and mutable flags; retain the host keymap's Unicode too.
        private constructor(other: HostedKeyEvent) : super(other) { unicode = other.unicode }
        override fun getUnicodeChar(): Int = unicode
        override fun getUnicodeChar(metaState: Int): Int = unicode
        override fun copy(): KeyEvent = HostedKeyEvent(this)
    }

    private fun currentMetaState(): Int {
        var meta = lockedMetaState
        for (pressed in pressedKeys.values) {
            meta = meta or when (pressed.keyCode) {
                KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEvent.META_SHIFT_LEFT_ON
                KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_RIGHT_ON
                KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_LEFT_ON
                KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_RIGHT_ON
                KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.META_CTRL_LEFT_ON
                KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_RIGHT_ON
                KeyEvent.KEYCODE_META_LEFT -> KeyEvent.META_META_LEFT_ON
                KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.META_META_RIGHT_ON
                else -> 0
            }
        }
        return KeyEvent.normalizeMetaState(meta)
    }

    private fun lockMetaStateForKey(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_CAPS_LOCK -> KeyEvent.META_CAPS_LOCK_ON
        KeyEvent.KEYCODE_NUM_LOCK -> KeyEvent.META_NUM_LOCK_ON
        KeyEvent.KEYCODE_SCROLL_LOCK -> KeyEvent.META_SCROLL_LOCK_ON
        else -> 0
    }

    /**
     * Linux evdev to Android keycode mapping from the vendored AOSP Generic.kl. The key layout
     * parser normally runs under EventHub, which is unavailable without Android's input daemon.
     */
    internal fun androidKeyCodeForScanCode(scanCode: Int): Int = when (scanCode) {
        1 -> KeyEvent.KEYCODE_ESCAPE
        in 2..11 -> digitKeyCodes[scanCode - 2]
        12 -> KeyEvent.KEYCODE_MINUS
        13 -> KeyEvent.KEYCODE_EQUALS
        14 -> KeyEvent.KEYCODE_DEL
        15 -> KeyEvent.KEYCODE_TAB
        in 16..25 -> qwertyKeyCodes[scanCode - 16]
        26 -> KeyEvent.KEYCODE_LEFT_BRACKET
        27 -> KeyEvent.KEYCODE_RIGHT_BRACKET
        28 -> KeyEvent.KEYCODE_ENTER
        29 -> KeyEvent.KEYCODE_CTRL_LEFT
        in 30..38 -> homeRowKeyCodes[scanCode - 30]
        39 -> KeyEvent.KEYCODE_SEMICOLON
        40 -> KeyEvent.KEYCODE_APOSTROPHE
        41 -> KeyEvent.KEYCODE_GRAVE
        42 -> KeyEvent.KEYCODE_SHIFT_LEFT
        43, 86 -> KeyEvent.KEYCODE_BACKSLASH
        in 44..50 -> bottomRowKeyCodes[scanCode - 44]
        51 -> KeyEvent.KEYCODE_COMMA
        52 -> KeyEvent.KEYCODE_PERIOD
        53 -> KeyEvent.KEYCODE_SLASH
        54 -> KeyEvent.KEYCODE_SHIFT_RIGHT
        55 -> KeyEvent.KEYCODE_NUMPAD_MULTIPLY
        56 -> KeyEvent.KEYCODE_ALT_LEFT
        57 -> KeyEvent.KEYCODE_SPACE
        58 -> KeyEvent.KEYCODE_CAPS_LOCK
        in 59..68 -> KeyEvent.KEYCODE_F1 + scanCode - 59
        69 -> KeyEvent.KEYCODE_NUM_LOCK
        70 -> KeyEvent.KEYCODE_SCROLL_LOCK
        71 -> KeyEvent.KEYCODE_NUMPAD_7
        72 -> KeyEvent.KEYCODE_NUMPAD_8
        73 -> KeyEvent.KEYCODE_NUMPAD_9
        74 -> KeyEvent.KEYCODE_NUMPAD_SUBTRACT
        75 -> KeyEvent.KEYCODE_NUMPAD_4
        76 -> KeyEvent.KEYCODE_NUMPAD_5
        77 -> KeyEvent.KEYCODE_NUMPAD_6
        78 -> KeyEvent.KEYCODE_NUMPAD_ADD
        79 -> KeyEvent.KEYCODE_NUMPAD_1
        80 -> KeyEvent.KEYCODE_NUMPAD_2
        81 -> KeyEvent.KEYCODE_NUMPAD_3
        82 -> KeyEvent.KEYCODE_NUMPAD_0
        83 -> KeyEvent.KEYCODE_NUMPAD_DOT
        87 -> KeyEvent.KEYCODE_F11
        88 -> KeyEvent.KEYCODE_F12
        95, 121 -> KeyEvent.KEYCODE_NUMPAD_COMMA
        96 -> KeyEvent.KEYCODE_NUMPAD_ENTER
        97 -> KeyEvent.KEYCODE_CTRL_RIGHT
        98 -> KeyEvent.KEYCODE_NUMPAD_DIVIDE
        99 -> KeyEvent.KEYCODE_SYSRQ
        100 -> KeyEvent.KEYCODE_ALT_RIGHT
        102 -> KeyEvent.KEYCODE_MOVE_HOME
        103 -> KeyEvent.KEYCODE_DPAD_UP
        104 -> KeyEvent.KEYCODE_PAGE_UP
        105 -> KeyEvent.KEYCODE_DPAD_LEFT
        106 -> KeyEvent.KEYCODE_DPAD_RIGHT
        107 -> KeyEvent.KEYCODE_MOVE_END
        108 -> KeyEvent.KEYCODE_DPAD_DOWN
        109 -> KeyEvent.KEYCODE_PAGE_DOWN
        110 -> KeyEvent.KEYCODE_INSERT
        111 -> KeyEvent.KEYCODE_FORWARD_DEL
        113 -> KeyEvent.KEYCODE_VOLUME_MUTE
        114 -> KeyEvent.KEYCODE_VOLUME_DOWN
        115 -> KeyEvent.KEYCODE_VOLUME_UP
        116 -> KeyEvent.KEYCODE_POWER
        117 -> KeyEvent.KEYCODE_NUMPAD_EQUALS
        119 -> KeyEvent.KEYCODE_BREAK
        120 -> KeyEvent.KEYCODE_APP_SWITCH
        125 -> KeyEvent.KEYCODE_META_LEFT
        126 -> KeyEvent.KEYCODE_META_RIGHT
        127, 139 -> KeyEvent.KEYCODE_MENU
        128, 166 -> KeyEvent.KEYCODE_MEDIA_STOP
        158 -> KeyEvent.KEYCODE_BACK
        159 -> KeyEvent.KEYCODE_FORWARD
        163 -> KeyEvent.KEYCODE_MEDIA_NEXT
        164 -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        165 -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        168 -> KeyEvent.KEYCODE_MEDIA_REWIND
        172 -> KeyEvent.KEYCODE_HOME
        200, 207 -> KeyEvent.KEYCODE_MEDIA_PLAY
        201 -> KeyEvent.KEYCODE_MEDIA_PAUSE
        208 -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        else -> KeyEvent.KEYCODE_UNKNOWN
    }

    private const val TOUCH_DOWN = 0
    private const val TOUCH_UP = 1
    private const val TOUCH_MOVE = 2
    private const val TOUCH_UPDATE = 3
    private const val MAX_TOUCH_CONTACTS = 10
    private const val POINTER_DOWN_BUTTONS =
        MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_TERTIARY
}
