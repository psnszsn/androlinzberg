package dev.artzig

import com.android.settingslib.flags.Flags as SettingsLibFlags
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Point
import android.os.Bundle
import android.os.Looper
import android.view.HostedInputDispatcher
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Gravity
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManagerImpl
import android.view.ViewRootImpl
import android.widget.FrameLayout
import android.widget.EditText

/** Temporary regression suite for the legacy window backend. Migrate these
 * contracts to real APK windows before deleting the legacy implementation. */
internal object LegacyWindowSmoke {
    @JvmStatic fun main(args: Array<String>) {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        HostContexts.bootstrap(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")))
        check(args.size == 2)
        val installer = checkNotNull(HostedServiceClient.installerOrNull())
        val packageName = installer.installPackage(args[0], null)
        check(packageName == "org.example.permissions")
        // The activity fixture models an already-bound app. Establish that
        // state explicitly, as the original general suite did before these tests.
        HostedServiceClient.attachApplication(packageName)

        com.android.server.uri.StockUriPermissionSmoke.withService {
            testWindowConfigurationCallbacks()
            testQueuedRootInput()
            testHostedInputEvents()
            testHostedInputDispatch()
            testHostedPhysicalInputLifecycle()
            testHostedInputWindowRouting()
            FrameworkModuleSmoke.runLegacyActivityFixtures(args[1])
        }
        println("legacy window regression smoke: PASS")
    }

    private fun testWindowConfigurationCallbacks() {
        val base = HostContexts.systemContext(System.getenv("ARTZIG_FRAMEWORK_RES"), 100, 100, 120, 0)
        val configurationContext = base.createConfigurationContext(Configuration())
        val context = object : ContextWrapper(configurationContext) {
            override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo(super.getApplicationInfo()).apply {
                flags = flags or ApplicationInfo.FLAG_SUPPORTS_RTL
                targetSdkVersion = android.os.Build.VERSION_CODES.CUR_DEVELOPMENT
            }
        }
        val resources = context.resources
        val original = Configuration(resources.configuration)
        val initial = Configuration(original).apply { seq = 100 }
        resources.updateConfiguration(initial, resources.displayMetrics)
        val events = ArrayList<String>()
        val view = object : View(context) {
            override fun onConfigurationChanged(config: Configuration) {
                events.add("view:${config.seq}")
                super.onConfigurationChanged(config)
            }
        }
        view.layoutDirection = View.LAYOUT_DIRECTION_INHERIT
        val root = HostedWindowRoots.attach(view, 100, 100)
        val first = ViewRootImpl.ConfigChangedCallback { events.add("first:${it.seq}") }
        val second = ViewRootImpl.ConfigChangedCallback { events.add("second:${it.seq}") }
        ViewRootImpl.addConfigCallback(first)
        ViewRootImpl.addConfigCallback(second)
        root.setActivityConfigCallback(object : ViewRootImpl.ActivityConfigCallback {
            override fun onConfigurationChanged(overrideConfig: Configuration, displayId: Int) {
                check(displayId == android.view.Display.INVALID_DISPLAY)
                events.add("activity")
                root.updateConfiguration(displayId)
            }
        })
        try {
            root.doTraversal()
            for ((sequence, locale, direction) in listOf(
                Triple(200, java.util.Locale.forLanguageTag("ar"), View.LAYOUT_DIRECTION_RTL),
                Triple(300, java.util.Locale.US, View.LAYOUT_DIRECTION_LTR))) {
                val updated = Configuration(initial).apply {
                    seq = sequence
                    setLocales(android.os.LocaleList(locale))
                }
                resources.updateConfiguration(updated, resources.displayMetrics)
                events.clear()
                android.view.WindowManagerGlobal.getInstance().reportNewConfiguration(updated)
                updated.seq = 999 // Stock global registry must queue its own copy.
                check(events.isEmpty())
                runHostedFrame()
                check(events == listOf("second:$sequence", "first:$sequence", "activity", "view:$sequence")) {
                    "configuration callback order: $events"
                }
                check(view.layoutDirection == direction) {
                    "layout direction: actual=${view.layoutDirection}, expected=$direction, raw=${view.rawLayoutDirection}"
                }
            }
            events.clear()
            root.requestUpdateConfiguration(initial)
            runHostedFrame()
            check(events == listOf("second:300", "first:300", "activity")) { events.toString() }
        } finally {
            ViewRootImpl.removeConfigCallback(first)
            ViewRootImpl.removeConfigCallback(second)
            root.destroy()
            resources.updateConfiguration(original, resources.displayMetrics)
        }
    }

    private fun testQueuedRootInput() {
        val context = HostContexts.systemContext(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 200, 200, 120, 0)
        val root = android.view.ViewRootImpl(context)
        val delivered = ArrayList<Int>()
        val now = android.os.SystemClock.uptimeMillis()
        fun event(code: Int, time: Long) = KeyEvent(now, time, KeyEvent.ACTION_DOWN, code, 0)
        val view = object : View(context) {
            override fun onKeyDown(code: Int, keyEvent: KeyEvent): Boolean {
                delivered.add(code)
                if (code == KeyEvent.KEYCODE_A) root.enqueueInputEvent(event(KeyEvent.KEYCODE_C, now + 20))
                return true
            }
        }.apply { isFocusableInTouchMode = true }
        root.setView(view, 200, 200)
        view.requestFocus()
        try {
            check(root.inputToken == null)
            root.enqueueInputEvent(event(KeyEvent.KEYCODE_A, now + 30))
            root.enqueueInputEvent(event(KeyEvent.KEYCODE_B, now + 10))
            check(delivered.isEmpty()) { "queued input was dispatched synchronously" }
            drainMainQueue()
            check(delivered == listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_C))
            root.enqueueInputEvent(event(KeyEvent.KEYCODE_D, now))
        } finally { root.destroy() }
        drainMainQueue()
        check(delivered.size == 3) { "detached root received pending input" }
    }

    private fun testHostedInputEvents() {
        check(HostedInputDispatcher.createHostKeyCharacterMap().keyboardType ==
            KeyCharacterMap.FULL) {
            "hosted physical keyboard must select AOSP's full-keyboard text path"
        }

        val mouse = HostedInputDispatcher.createMouseEvent(
            downTime = 10,
            eventTime = 20,
            action = MotionEvent.ACTION_BUTTON_PRESS,
            buttonState = MotionEvent.BUTTON_PRIMARY,
            actionButton = MotionEvent.BUTTON_PRIMARY,
            x = 12f,
            y = 34f,
        )
        try {
            check(MotionEvent.FLAG_TARGET_ACCESSIBILITY_FOCUS == 0x40000000) {
                "MotionEvent flags must come from the matching AOSP input AIDL"
            }
            check(mouse.isFromSource(InputDevice.SOURCE_MOUSE))
            check(mouse.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE)
            check(mouse.buttonState == MotionEvent.BUTTON_PRIMARY)
            check(mouse.actionButton == MotionEvent.BUTTON_PRIMARY)
            check(mouse.pressure == 1f)
            check(!mouse.isFromSource(InputDevice.SOURCE_TOUCHSCREEN))
            check(!mouse.isTargetAccessibilityFocus)
            mouse.setTargetAccessibilityFocus(true)
            check(mouse.isTargetAccessibilityFocus)
            mouse.setTargetAccessibilityFocus(false)
            check(!mouse.isTargetAccessibilityFocus)
        } finally {
            mouse.recycle()
        }

        val touch = HostedInputDispatcher.createTouchEvent(
            downTime = 30,
            eventTime = 40,
            action = MotionEvent.ACTION_DOWN,
            x = 56f,
            y = 78f,
        )
        try {
            check(touch.isFromSource(InputDevice.SOURCE_TOUCHSCREEN))
            check(touch.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER)
            check(touch.buttonState == 0)
            check(!touch.isFromSource(InputDevice.SOURCE_MOUSE))
        } finally {
            touch.recycle()
        }
    }

    private fun testHostedInputDispatch() {
        val frameworkResources = System.getenv("ARTZIG_FRAMEWORK_RES")
            ?: "zig-out/res/framework-res.apk"
        val context = HostContexts.systemContext(frameworkResources, 200, 200, 120, 0)
        val observed = mutableListOf<String>()
        val view = object : View(context) {
            init {
                isClickable = true
                contentDescription = "input probe"
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                observed += listOf(
                    "touch",
                    event.actionMasked.toString(),
                    event.source.toString(),
                    event.getToolType(0).toString(),
                    event.buttonState.toString(),
                    event.actionButton.toString(),
                ).joinToString(":")
                return true
            }

            override fun onGenericMotionEvent(event: MotionEvent): Boolean {
                observed += listOf(
                    "generic",
                    event.actionMasked.toString(),
                    event.source.toString(),
                    event.getToolType(0).toString(),
                    event.buttonState.toString(),
                    event.actionButton.toString(),
                    event.getAxisValue(MotionEvent.AXIS_HSCROLL).toString(),
                    event.getAxisValue(MotionEvent.AXIS_VSCROLL).toString(),
                ).joinToString(":")
                return true
            }
        }
        val root = ViewRootImpl(context)
        root.setView(view, 200, 200)
        view.layout(0, 0, 200, 200)
        try {
            root.ensureTouchMode(false)
            view.viewTreeObserver.addOnTouchModeChangeListener { inTouchMode ->
                observed += "touch-mode:$inTouchMode"
            }
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_DOWN,
                MotionEvent.BUTTON_PRIMARY, 0, 20f, 30f,
            )
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_BUTTON_PRESS,
                MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY, 20f, 30f,
            )
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_BUTTON_RELEASE,
                0, MotionEvent.BUTTON_PRIMARY, 20f, 30f,
            )
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_UP, 0, 0, 20f, 30f,
            )
            HostedInputDispatcher.dispatchTouch(
                root, MotionEvent.ACTION_DOWN, 40f, 50f,
            )
            HostedInputDispatcher.dispatchTouch(
                root, MotionEvent.ACTION_UP, 40f, 50f,
            )
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_DOWN,
                MotionEvent.BUTTON_SECONDARY, 0, 60f, 70f,
            )
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_BUTTON_PRESS,
                MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_SECONDARY, 60f, 70f,
            )
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_BUTTON_RELEASE,
                0, MotionEvent.BUTTON_SECONDARY, 60f, 70f,
            )
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_UP, 0, 0, 60f, 70f,
            )
            HostedInputDispatcher.dispatchHover(
                root, MotionEvent.ACTION_HOVER_ENTER, 80f, 90f,
            )
            HostedInputDispatcher.dispatchHover(
                root, MotionEvent.ACTION_HOVER_MOVE, 81f, 91f,
            )
            HostedInputDispatcher.dispatchScroll(
                root, 81f, 91f, 2f, -3f,
            )
            HostedInputDispatcher.dispatchHover(
                root, MotionEvent.ACTION_HOVER_EXIT, 81f, 91f,
            )

            val mousePrefix = "${InputDevice.SOURCE_MOUSE}:${MotionEvent.TOOL_TYPE_MOUSE}"
            val touchPrefix =
                "${InputDevice.SOURCE_TOUCHSCREEN}:${MotionEvent.TOOL_TYPE_FINGER}"
            check(observed == listOf(
                "touch-mode:true",
                "touch:${MotionEvent.ACTION_DOWN}:$mousePrefix:${MotionEvent.BUTTON_PRIMARY}:0",
                "generic:${MotionEvent.ACTION_BUTTON_PRESS}:$mousePrefix:" +
                    "${MotionEvent.BUTTON_PRIMARY}:${MotionEvent.BUTTON_PRIMARY}:0.0:0.0",
                "generic:${MotionEvent.ACTION_BUTTON_RELEASE}:$mousePrefix:0:" +
                    "${MotionEvent.BUTTON_PRIMARY}:0.0:0.0",
                "touch:${MotionEvent.ACTION_UP}:$mousePrefix:0:0",
                "generic:${MotionEvent.ACTION_HOVER_ENTER}:$mousePrefix:0:0:0.0:0.0",
                "touch:${MotionEvent.ACTION_DOWN}:$touchPrefix:0:0",
                "touch:${MotionEvent.ACTION_UP}:$touchPrefix:0:0",
                "touch:${MotionEvent.ACTION_DOWN}:$mousePrefix:${MotionEvent.BUTTON_SECONDARY}:0",
                "generic:${MotionEvent.ACTION_BUTTON_PRESS}:$mousePrefix:" +
                    "${MotionEvent.BUTTON_SECONDARY}:${MotionEvent.BUTTON_SECONDARY}:0.0:0.0",
                "generic:${MotionEvent.ACTION_BUTTON_RELEASE}:$mousePrefix:0:" +
                    "${MotionEvent.BUTTON_SECONDARY}:0.0:0.0",
                "touch:${MotionEvent.ACTION_UP}:$mousePrefix:0:0",
                "generic:${MotionEvent.ACTION_HOVER_MOVE}:$mousePrefix:0:0:0.0:0.0",
                "generic:${MotionEvent.ACTION_HOVER_ENTER}:$mousePrefix:0:0:0.0:0.0",
                "generic:${MotionEvent.ACTION_HOVER_MOVE}:$mousePrefix:0:0:0.0:0.0",
                "generic:${MotionEvent.ACTION_SCROLL}:$mousePrefix:0:0:2.0:-3.0",
                "generic:${MotionEvent.ACTION_HOVER_EXIT}:$mousePrefix:0:0:0.0:0.0",
            )) { "hosted input dispatch contract: $observed" }
            check(root.lastTouchSource == InputDevice.SOURCE_MOUSE)
            check(root.lastTouchPointerId == 0)
            check(root.lastClickToolType == MotionEvent.TOOL_TYPE_MOUSE)
            val lastTouchPoint = Point()
            root.getLastTouchPoint(lastTouchPoint)
            check(lastTouchPoint == Point(60, 70))
        } finally {
            HostedInputDispatcher.reset()
            root.destroy()
        }
    }

    private fun testHostedPhysicalInputLifecycle() {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            200,
            200,
            120,
            0,
        )
        val touchEvents = mutableListOf<String>()
        val keyEvents = mutableListOf<String>()
        val captureEvents = mutableListOf<String>()
        val view = object : View(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                val pointers = (0 until event.pointerCount).joinToString(",") { index ->
                    "${event.getPointerId(index)}@${event.getX(index)}:${event.getTouchMajor(index)}"
                }
                touchEvents += "${event.actionMasked}:${event.actionIndex}:$pointers"
                return true
            }

            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                keyEvents += listOf(
                    event.action,
                    event.keyCode,
                    event.scanCode,
                    event.repeatCount,
                    event.metaState,
                    event.unicodeChar,
                    event.flags,
                ).joinToString(":")
                return true
            }

            override fun onPointerCaptureChange(hasCapture: Boolean) {
                captureEvents += "capture:$hasCapture"
            }

            override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
                captureEvents += listOf(
                    "motion",
                    event.actionMasked,
                    event.source,
                    event.buttonState,
                    event.actionButton,
                    event.getAxisValue(MotionEvent.AXIS_RELATIVE_X),
                    event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y),
                ).joinToString(":")
                return true
            }
        }
        val root = ViewRootImpl(context)
        root.setView(view, 200, 200)
        view.layout(0, 0, 200, 200)
        try {
            HostedInputDispatcher.dispatchTouchContact(root, 0, 7, 10f, 20f, 8f, 4f, 0.25f)
            HostedInputDispatcher.dispatchTouchContact(root, 0, 3, 30f, 40f, 6f, 3f, -0.5f)
            HostedInputDispatcher.dispatchTouchContact(root, 2, 7, 11f, 21f, 9f, 4f, 0.25f)
            HostedInputDispatcher.dispatchTouchContact(root, 1, 7, 11f, 21f, 9f, 4f, 0.25f)
            HostedInputDispatcher.cancelTouch(root)
            check(touchEvents == listOf(
                "${MotionEvent.ACTION_DOWN}:0:0@10.0:8.0",
                "${MotionEvent.ACTION_POINTER_DOWN}:1:0@10.0:8.0,1@30.0:6.0",
                "${MotionEvent.ACTION_MOVE}:0:0@11.0:9.0,1@30.0:6.0",
                "${MotionEvent.ACTION_POINTER_UP}:0:0@11.0:9.0,1@30.0:6.0",
                "${MotionEvent.ACTION_CANCEL}:0:1@30.0:6.0",
            )) { "physical touch lifecycle: $touchEvents" }

            HostedInputDispatcher.dispatchPhysicalKey(root, KeyEvent.ACTION_DOWN, 42, 0)
            HostedInputDispatcher.dispatchPhysicalKey(root, KeyEvent.ACTION_DOWN, 30, 'A'.code)
            HostedInputDispatcher.dispatchPhysicalKey(root, KeyEvent.ACTION_DOWN, 30, 'A'.code)
            HostedInputDispatcher.dispatchPhysicalKey(root, KeyEvent.ACTION_UP, 30, 0)
            HostedInputDispatcher.dispatchPhysicalKey(root, KeyEvent.ACTION_UP, 42, 0)
            check(keyEvents.size == 5)
            check(keyEvents[0].startsWith("0:${KeyEvent.KEYCODE_SHIFT_LEFT}:42:0:"))
            check(keyEvents[1] == "0:${KeyEvent.KEYCODE_A}:30:0:" +
                "${KeyEvent.normalizeMetaState(KeyEvent.META_SHIFT_LEFT_ON)}:${'A'.code}:0")
            check(keyEvents[2] == "0:${KeyEvent.KEYCODE_A}:30:1:" +
                "${KeyEvent.normalizeMetaState(KeyEvent.META_SHIFT_LEFT_ON)}:${'A'.code}:0")
            check(keyEvents[3].startsWith("1:${KeyEvent.KEYCODE_A}:30:0:"))
            check(keyEvents[4] == "1:${KeyEvent.KEYCODE_SHIFT_LEFT}:42:0:0:0:0")

            keyEvents.clear()
            HostedInputDispatcher.dispatchPhysicalKey(root, KeyEvent.ACTION_DOWN, 42, 0)
            HostedInputDispatcher.dispatchPhysicalKey(root, KeyEvent.ACTION_DOWN, 30, 'A'.code)
            HostedInputDispatcher.resetKeyboard()
            check(keyEvents.takeLast(2) == listOf(
                "1:${KeyEvent.KEYCODE_A}:30:0:" +
                    "${KeyEvent.normalizeMetaState(KeyEvent.META_SHIFT_LEFT_ON)}:0:${KeyEvent.FLAG_CANCELED}",
                "1:${KeyEvent.KEYCODE_SHIFT_LEFT}:42:0:0:0:${KeyEvent.FLAG_CANCELED}",
            )) { "keyboard focus-loss cancellation: $keyEvents" }

            view.requestPointerCapture()
            check(HostedInputDispatcher.wantsPointerCapture())
            check(!view.hasPointerCapture())
            HostedInputDispatcher.onPointerCaptureChanged(true)
            check(view.hasPointerCapture())
            check(HostedInputDispatcher.dispatchCapturedPointer(2.5f, -4f, 0))
            check(HostedInputDispatcher.dispatchCapturedMouse(
                MotionEvent.ACTION_BUTTON_PRESS,
                MotionEvent.BUTTON_PRIMARY,
                MotionEvent.BUTTON_PRIMARY,
            ))
            view.releasePointerCapture()
            check(!HostedInputDispatcher.wantsPointerCapture())
            HostedInputDispatcher.onPointerCaptureChanged(false)
            check(!view.hasPointerCapture())
            check(captureEvents == listOf(
                "capture:true",
                "motion:${MotionEvent.ACTION_MOVE}:${InputDevice.SOURCE_MOUSE_RELATIVE}:0:0:2.5:-4.0",
                "motion:${MotionEvent.ACTION_BUTTON_PRESS}:${InputDevice.SOURCE_MOUSE_RELATIVE}:" +
                    "${MotionEvent.BUTTON_PRIMARY}:${MotionEvent.BUTTON_PRIMARY}:0.0:0.0",
                "capture:false",
            )) { "pointer capture lifecycle: $captureEvents" }
        } finally {
            HostedInputDispatcher.reset()
            root.destroy()
        }
    }

    private fun testHostedInputWindowRouting() {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            200,
            200,
            120,
            0,
        )
        val observed = mutableListOf<String>()
        val activity = RecordingInputView(context, "activity", observed).apply { isClickable = true }
        val activityRoot = ViewRootImpl(context)
        activityRoot.setView(activity, 200, 200)
        activity.measure(
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
        )
        activity.layout(0, 0, 200, 200)
        val manager = WindowManagerImpl(context)
        try {
            val lower = addInputWindow(
                manager, context, "lower", observed,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                0, 0, 100, 100,
            )
            addInputWindow(
                manager, context, "upper", observed,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                100, 0, 200, 100,
            )
            addInputWindow(
                manager, context, "untouchable", observed,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                0, 0, 100, 100,
            )

            HostedInputDispatcher.dispatchMouse(
                activityRoot, MotionEvent.ACTION_DOWN,
                MotionEvent.BUTTON_PRIMARY, 0, 50f, 50f,
            )
            HostedInputDispatcher.dispatchMouse(
                activityRoot, MotionEvent.ACTION_UP, 0, 0, 50f, 50f,
            )
            check(observed == listOf("upper:4", "lower:0", "lower:1", "lower:g:9")) {
                "non-modal outside routing must preserve one gesture root: $observed"
            }

            observed.clear()
            HostedInputDispatcher.dispatchHover(
                activityRoot, MotionEvent.ACTION_HOVER_ENTER, 50f, 50f,
            )
            HostedInputDispatcher.dispatchHover(
                activityRoot, MotionEvent.ACTION_HOVER_MOVE, 150f, 50f,
            )
            HostedInputDispatcher.dispatchHover(
                activityRoot, MotionEvent.ACTION_HOVER_EXIT, 150f, 50f,
            )
            check(observed == listOf("lower:g:9", "lower:g:10", "upper:g:9", "upper:g:10")) {
                "hover target changes must exit the old root before entering the new root: $observed"
            }

            dev.artzig.HostedWindowStack.clear()
            observed.clear()
            addInputWindow(
                manager, context, "modal", observed,
                0,
                100, 0, 200, 100,
            )
            HostedInputDispatcher.dispatchMouse(
                activityRoot, MotionEvent.ACTION_DOWN,
                MotionEvent.BUTTON_PRIMARY, 0, 50f, 50f,
            )
            HostedInputDispatcher.dispatchMouse(
                activityRoot, MotionEvent.ACTION_UP, 0, 0, 50f, 50f,
            )
            check(observed == listOf("modal:0", "modal:1", "modal:g:9")) {
                "modal window must own an outside gesture: $observed"
            }
            check(lower.root.view == null) { "cleared overlay root remained attached" }
        } finally {
            HostedInputDispatcher.reset()
            dev.artzig.HostedWindowStack.clear()
            activityRoot.destroy()
        }
    }

    private fun addInputWindow(
        manager: WindowManagerImpl,
        context: Context,
        name: String,
        observed: MutableList<String>,
        flags: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): dev.artzig.HostedWindowStack.Entry {
        val view = RecordingInputView(context, name, observed).apply { isClickable = true }
        val params = WindowManager.LayoutParams().apply {
            width = right - left
            height = bottom - top
            this.flags = flags
            gravity = Gravity.TOP or Gravity.LEFT
        }
        manager.addView(view, params)
        val entry = dev.artzig.HostedWindowStack.entries().last()
        entry.root.setSize(right - left, bottom - top)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(right - left, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(bottom - top, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, right - left, bottom - top)
        entry.frame.set(left, top, right, bottom)
        return entry
    }

    private class RecordingInputView(
        context: Context,
        private val name: String,
        private val observed: MutableList<String>,
    ) : View(context) {
        override fun onTouchEvent(event: MotionEvent): Boolean {
            observed += "$name:${event.actionMasked}"
            return true
        }

        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            observed += "$name:g:${event.actionMasked}"
            return true
        }
    }

    private fun awaitChoreographerFrame() = StockFrameTestDriver.awaitFrame(::drainMainQueue)

    private fun runHostedFrame() {
        drainMainQueue()
        awaitChoreographerFrame()
        drainMainQueue()
    }

    private fun drainMainQueue() {
        drainMainQueueCounting()
    }

    private fun drainMainQueueCounting(): Int {
        val queue = checkNotNull(Looper.getMainLooper()).queue
        var count = 0
        while (true) {
            val message = queue.`pollDueMessage$artzig`() ?: return count
            try {
                message.target.dispatchMessage(message)
                count++
            } finally {
                android.os.MessageQueue.`recycleDispatched$artzig`(message)
            }
        }
    }

}
