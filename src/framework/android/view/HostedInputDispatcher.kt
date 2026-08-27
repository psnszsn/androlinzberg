package android.view

import android.os.SystemClock

/**
 * Translates host pointer devices into Android MotionEvents and dispatches them at the hosted
 * ViewRootImpl seam.
 *
 * This follows AOSP ViewRootImpl.ViewPostImeInputStage.processPointerEvent(): pressed mouse
 * pointers and touchscreen contacts both enter View.dispatchPointerEvent(), while their source,
 * tool type, and button state remain distinct. The stock input stages cannot be compiled because
 * the host has no InputChannel, InputEventReceiver, or IWindowSession.
 */
object HostedInputDispatcher {
    private class GestureState {
        var downTime: Long = 0
        var target: RootTarget? = null
        val outsideWatchers = mutableListOf<ArtzigWindowStack.Entry>()

        fun reset() {
            downTime = 0
            target = null
            outsideWatchers.clear()
        }
    }

    private val mouseGesture = GestureState()
    private val touchGesture = GestureState()
    private var hoverTarget: RootTarget? = null

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
        val event = createMouseEvent(
            mouseGesture.downTime.takeIf { it != 0L } ?: now,
            now,
            action,
            buttonState,
            actionButton,
            screenX,
            screenY,
        )
        return try {
            if (action == MotionEvent.ACTION_DOWN) dispatchOutsideWatchers(mouseGesture, event)
            dispatchGesture(activityRoot, mouseGesture, event)
        } finally {
            event.recycle()
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mouseGesture.reset()
            }
        }
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

    @JvmStatic
    fun dispatchHover(
        activityRoot: ViewRootImpl?,
        action: Int,
        screenX: Float,
        screenY: Float,
    ): Int {
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
        return target.root?.resolvedPointerIconType ?: PointerIcon.TYPE_ARROW
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
            target.root?.dispatchPointerEvent(event) ?: false
        } finally {
            event.recycle()
        }
    }

    @JvmStatic
    fun reset() {
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
                watcher.root.dispatchPointerEvent(outsideEvent)
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
            target.root?.dispatchPointerEvent(event)
        } finally {
            event.recycle()
        }
    }

    private data class RootTarget(
        val root: ViewRootImpl?,
        val window: ArtzigWindowStack.Entry?,
    ) {
        fun dispatch(event: MotionEvent): Boolean {
            window?.let {
                event.offsetLocation(-it.frame.left.toFloat(), -it.frame.top.toFloat())
            }
            return root?.dispatchPointerEvent(event) ?: false
        }

        fun localX(screenX: Float): Float = screenX - (window?.frame?.left ?: 0)
        fun localY(screenY: Float): Float = screenY - (window?.frame?.top ?: 0)
    }

    private data class GestureRoute(
        val target: RootTarget,
        val outsideWatchers: List<ArtzigWindowStack.Entry>,
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
        val outsideWatchers = mutableListOf<ArtzigWindowStack.Entry>()
        val entries = ArtzigWindowStack.entries()
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
}
