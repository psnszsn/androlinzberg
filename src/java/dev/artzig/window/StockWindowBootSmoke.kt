package dev.artzig.window

import android.content.Context
import android.graphics.Point
import android.hardware.display.IDisplayManager
import android.os.ServiceManager
import android.view.Display
import android.view.IWindowManager

/** Cross-process boot check; the launcher must have started the stock service path. */
object StockWindowBootSmoke {
    @JvmStatic fun main(args: Array<String>) {
        check(WindowBackend.stock)
        check(android.os.SystemProperties.get("sys.azk.boot_completed") == "1")
        val windows = checkNotNull(IWindowManager.Stub.asInterface(ServiceManager.getService(Context.WINDOW_SERVICE)))
        val displays = checkNotNull(IDisplayManager.Stub.asInterface(ServiceManager.getService(Context.DISPLAY_SERVICE)))
        val initial = Point()
        windows.getInitialDisplaySize(Display.DEFAULT_DISPLAY, initial)
        check(initial.x > 0 && initial.y > 0)
        check(displays.getDisplayIds(false).contentEquals(intArrayOf(Display.DEFAULT_DISPLAY)))
        val info = checkNotNull(displays.getDisplayInfo(Display.DEFAULT_DISPLAY))
        check(info.logicalWidth > 0 && info.logicalHeight > 0)
        check(displays.getDisplayInfo(900) == null)
        check(!windows.isKeyguardLocked)
        val packages = checkNotNull(android.content.pm.IPackageManager.Stub.asInterface(
            ServiceManager.getService("package")))
        check(packages.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT, 0))
        check(packages.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PC, 0))
        check(!packages.hasSystemFeature(android.content.pm.PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS, 0))
        checkNotNull(ServiceManager.getService(Context.ACTIVITY_TASK_SERVICE))
        checkNotNull(ServiceManager.getService(Context.DEVICE_STATE_SERVICE))
        val unknownProcess = runCatching {
            windows.openSession(object : android.view.IWindowSessionCallback.Stub() {
                override fun onAnimatorScaleChanged(scale: Float) { }
            })
        }.exceptionOrNull()
        check(unknownProcess is IllegalStateException && unknownProcess.message!!.contains("Unknown pid="))
        check(windows.asBinder().pingBinder())
        android.os.Looper.prepareMainLooper()
        dev.artzig.HostContexts.systemContext(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")),
            320, 240, 120, 0)
        val installer = checkNotNull(dev.artzig.HostedServiceClient.installerOrNull())
        val packageName = installer.installPackage(args.single(), null)
        check(packages.getApplicationInfo(packageName, 0L, 0)!!.uid == android.os.Process.myUid())
        check(runCatching { dev.artzig.BinderSession.reservePackageUid(packageName) }
            .exceptionOrNull() is SecurityException)
        dev.artzig.BinderSession.setIdentity(packageName, false) // Same connected identity is safe.
        check(runCatching { dev.artzig.BinderSession.setIdentity("fixture.different", false) }
            .exceptionOrNull() is IllegalStateException)
        check(runCatching { dev.artzig.BinderSession.setIdentity("android", true) }
            .exceptionOrNull() is IllegalStateException)
        check(dev.artzig.BinderSession.assignedUid() == packages.getApplicationInfo(packageName, 0L, 0)!!.uid)
        val application = dev.artzig.HostedServiceClient.attachApplication(packageName)
        if (dev.artzig.testing.ProcessInspection.enabled) {
            val registry = dev.artzig.testing.IInspectionRegistry.Stub.asInterface(
                ServiceManager.getService(dev.artzig.testing.ProcessInspection.SERVICE))
            check(runCatching { registry.listProcesses() }.exceptionOrNull() is SecurityException)
            check(runCatching { registry.observe(android.os.Process.myPid(), 1, 0, 0, 0) }
                .exceptionOrNull() is SecurityException)
        }
        check(application.packageName == packageName)
        check(application.checkSelfPermission(android.Manifest.permission.SYSTEM_ALERT_WINDOW) ==
            android.content.pm.PackageManager.PERMISSION_DENIED)
        check(application.checkSelfPermission(android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)
        check(application.getSystemService(android.app.AppOpsManager::class.java).checkOpNoThrow(
            android.app.AppOpsManager.OP_SYSTEM_ALERT_WINDOW, android.os.Process.myUid(), packageName) ==
            android.app.AppOpsManager.MODE_DEFAULT)
        check(application.resources.configuration.densityDpi == info.logicalDensityDpi)
        val session = checkNotNull(windows.openSession(object : android.view.IWindowSessionCallback.Stub() {
            override fun onAnimatorScaleChanged(scale: Float) { }
        }))
        check(session.asBinder().pingBinder())
        val memory = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(memory)
        check(memory.processState == android.app.ActivityManager.PROCESS_STATE_CACHED_EMPTY)
        val appOps = application.getSystemService(android.app.AppOpsManager::class.java)
        fun audioFocusMode() = appOps.checkOpNoThrow(android.app.AppOpsManager.OP_TAKE_AUDIO_FOCUS,
            android.os.Process.myUid(), packageName)
        check(audioFocusMode() == android.app.AppOpsManager.MODE_IGNORED) {
            "A windowless process retained foreground AppOps access"
        }
        println("Stock WMS registered application session: $packageName")
        var resumed: android.app.Activity? = null
        val frameCommitted = java.util.concurrent.atomic.AtomicBoolean(false)
        val lifecycle = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                resumed = activity
                activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { frameCommitted.set(true) }
            }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) { }
            override fun onActivityStarted(activity: android.app.Activity) { }
            override fun onActivityPaused(activity: android.app.Activity) { }
            override fun onActivityStopped(activity: android.app.Activity) { }
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) { }
            override fun onActivityDestroyed(activity: android.app.Activity) { }
        }
        application.registerActivityLifecycleCallbacks(lifecycle)
        application.startActivity(checkNotNull(application.packageManager.getLaunchIntentForPackage(packageName)))
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        while ((resumed == null || (android.view.HostedRootFrames.stockClients && !frameCommitted.get())) &&
            android.os.SystemClock.uptimeMillis() < deadline) {
            val message = android.os.Looper.myQueue().`pollDueMessage$artzig`()
            if (message == null) android.os.SystemClock.sleep(1)
            else try { message.target.dispatchMessage(message) }
            finally { android.os.MessageQueue.`recycleDispatched$artzig`(message) }
        }
        application.unregisterActivityLifecycleCallbacks(lifecycle)
        checkNotNull(resumed) { "Stock ATMS did not resume the fixture activity" }
        if (android.view.HostedRootFrames.stockClients) check(frameCommitted.get()) { "Fixture window did not commit a frame" }
        check(resumed!!.taskId > 0)
        android.app.ActivityManager.getMyMemoryState(memory)
        check(memory.processState == android.app.ActivityManager.PROCESS_STATE_TOP)
        val appOpsDeadline = android.os.SystemClock.uptimeMillis() + 5_000
        while (audioFocusMode() != android.app.AppOpsManager.MODE_ALLOWED &&
            android.os.SystemClock.uptimeMillis() < appOpsDeadline) android.os.SystemClock.sleep(5)
        check(audioFocusMode() == android.app.AppOpsManager.MODE_ALLOWED) {
            "WM's resumed process state did not reach stock AppOps"
        }
        println("Stock AppOps: windowless access rejected, resumed activity access allowed")
        println("Stock ATMS resumed application task: ${resumed!!.taskId}")
        if (android.view.HostedRootFrames.stockClients) verifyAccessibility(resumed!!)
        verifyClientWindow(session, resumed!!)
        resumed!!.finish()
        val backgroundDeadline = android.os.SystemClock.uptimeMillis() + 10_000
        do {
            val message = android.os.Looper.myQueue().`pollDueMessage$artzig`()
            if (message == null) android.os.SystemClock.sleep(5)
            else try { message.target.dispatchMessage(message) }
            finally { android.os.MessageQueue.`recycleDispatched$artzig`(message) }
            android.app.ActivityManager.getMyMemoryState(memory)
        } while ((memory.processState < android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY ||
                audioFocusMode() != android.app.AppOpsManager.MODE_IGNORED) &&
            android.os.SystemClock.uptimeMillis() < backgroundDeadline)
        check(memory.processState >= android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY)
        check(audioFocusMode() == android.app.AppOpsManager.MODE_IGNORED) {
            "Finished activity retained foreground AppOps access after the stock settling delay"
        }
        println("Stock AppOps: finished activity lost foreground access while its process remained alive")
        verifyColdActivity(application)
        verifyStartupRetry(application)
        println("Stock WMS/ATMS boot: display=${info.displayId} physical=${initial.x}x${initial.y}")
    }

    private fun verifyColdActivity(application: android.app.Application) {
        val packageName = application.packageName
        val processName = "$packageName:cold"
        val manager = application.getSystemService(android.app.ActivityManager::class.java)
        fun process() = manager.runningAppProcesses.orEmpty().singleOrNull { it.processName == processName }
        check(process() == null) { "Cold activity process was already attached" }
        application.startActivity(android.content.Intent().setClassName(packageName,
            "$packageName.ColdProcessActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        while (process() == null && android.os.SystemClock.uptimeMillis() < deadline) {
            drainDueMessages()
            android.os.SystemClock.sleep(5)
        }
        val running = checkNotNull(process()) { "ATMS did not start the cold activity process" }
        check(running.pid != android.os.Process.myPid() && running.uid == android.os.Process.myUid())
        val uri = android.net.Uri.parse("content://$packageName.cold")
        var snapshot: android.os.Bundle? = null
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            drainDueMessages()
            snapshot = application.contentResolver.call(uri, "snapshot", null, null)
            if (snapshot?.getStringArrayList("events")?.contains("resume") == true &&
                (!android.view.HostedRootFrames.stockClients || snapshot.getBoolean("frameCommitted"))) break
            android.os.SystemClock.sleep(5)
        }
        val state = checkNotNull(snapshot)
        check(state.getInt("pid") == running.pid && state.getInt("uid") == running.uid)
        check(state.getString("process") == processName)
        if (android.view.HostedRootFrames.stockClients) check(state.getBoolean("frameCommitted")) {
            "Stock ViewRootImpl never committed an APK frame"
        }
        check(state.getStringArrayList("events") == arrayListOf("provider", "create", "start", "resume")) {
            "Cold process lifecycle: ${state.getStringArrayList("events")}" }
        println("Stock ATMS cold activity: $processName pid=${running.pid}, provider/create/start/resume verified")
        verifySecondaryComponents(application, running.pid)
    }

    private fun verifySecondaryComponents(application: android.app.Application, activityPid: Int) {
        val pkg = application.packageName
        var serviceState: android.os.Bundle? = null
        val receiver = android.os.Messenger(android.os.Handler(android.os.Looper.getMainLooper()) {
            serviceState = it.data
            true
        })
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
                android.os.Messenger(binder).send(android.os.Message.obtain().apply { replyTo = receiver })
            }
            override fun onServiceDisconnected(name: android.content.ComponentName) { }
        }
        check(application.bindService(android.content.Intent().setClassName(pkg, "$pkg.ColdProcessService"),
            connection, Context.BIND_AUTO_CREATE))
        try {
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (serviceState == null && android.os.SystemClock.uptimeMillis() < deadline) {
                drainDueMessages()
                android.os.SystemClock.sleep(5)
            }
            val state = checkNotNull(serviceState) { "Secondary-process service did not respond" }
            check(state.getInt("pid") == activityPid && state.getString("process") == "$pkg:cold")
            check(state.getStringArrayList("events")!!.takeLast(2) == listOf("service-create", "service-bind"))
        } finally { application.unbindService(connection) }
        val manager = application.getSystemService(android.app.ActivityManager::class.java)
        check(manager.runningAppProcesses.orEmpty().none { it.processName == "$pkg:provider" })
        val provider = checkNotNull(application.contentResolver.call(
            android.net.Uri.parse("content://$pkg.provider"), "snapshot", null, null))
        check(provider.getString("process") == "$pkg:provider")
        check(provider.getInt("pid") != activityPid && provider.getInt("pid") != android.os.Process.myPid())
        check(provider.getInt("uid") == android.os.Process.myUid())
        check(provider.getStringArrayList("events") == arrayListOf("provider"))
        println("Secondary component routing: service reused activity process; provider started its own process")
    }

    /** A process dying while bindApplication installs its providers must fail
     * pending bindings, then permit a fresh start without disturbing siblings. */
    private fun verifyStartupRetry(application: android.app.Application) {
        val pkg = application.packageName
        java.io.File(application.filesDir, "startup-failure-once").delete()
        val manager = application.getSystemService(android.app.ActivityManager::class.java)
        val siblings = manager.runningAppProcesses.orEmpty().filter {
            it.processName == "$pkg:cold" || it.processName == "$pkg:provider"
        }.associate { it.processName to it.pid }
        check(siblings.size == 2)
        val intent = android.content.Intent().setClassName(pkg, "$pkg.RetryProcessService")
        var died = false
        val first = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
                error("The failing startup unexpectedly bound its service")
            }
            override fun onServiceDisconnected(name: android.content.ComponentName) { }
            override fun onBindingDied(name: android.content.ComponentName) { died = true }
        }
        check(application.bindService(intent, first, Context.BIND_AUTO_CREATE))
        try {
            val deadline = android.os.SystemClock.uptimeMillis() + 15_000
            while (!died && android.os.SystemClock.uptimeMillis() < deadline) {
                drainDueMessages(); android.os.SystemClock.sleep(5)
            }
            check(died) { "Process startup death left its binding pending" }
        } finally { application.unbindService(first) }
        var state: android.os.Bundle? = null
        val receiver = android.os.Messenger(android.os.Handler(android.os.Looper.getMainLooper()) {
            state = it.data; true
        })
        val retry = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
                android.os.Messenger(binder).send(android.os.Message.obtain().apply { replyTo = receiver })
            }
            override fun onServiceDisconnected(name: android.content.ComponentName) { }
        }
        check(application.bindService(intent, retry, Context.BIND_AUTO_CREATE))
        try {
            val deadline = android.os.SystemClock.uptimeMillis() + 15_000
            while (state == null && android.os.SystemClock.uptimeMillis() < deadline) {
                drainDueMessages(); android.os.SystemClock.sleep(5)
            }
            val ready = checkNotNull(state) { "Fresh process failed to bind after startup failure" }
            check(ready.getString("process") == "$pkg:retry")
            check(ready.getInt("uid") == android.os.Process.myUid())
            check(ready.getStringArrayList("events") == arrayListOf("provider", "service-create", "service-bind"))
            check(manager.runningAppProcesses.orEmpty().filter { it.processName in siblings }
                .associate { it.processName to it.pid } == siblings)
            println("Process startup recovery: dead binding reported, fresh service start succeeded, sibling PIDs preserved")
        } finally { application.unbindService(retry) }
    }

    private fun drainDueMessages() {
        while (true) {
            val message = android.os.Looper.myQueue().`pollDueMessage$artzig`() ?: return
            try { message.target.dispatchMessage(message) }
            finally { android.os.MessageQueue.`recycleDispatched$artzig`(message) }
        }
    }

    /** Exercise the actual session contract before switching the ViewRootImpl client.
     * The test window has no view hierarchy; BLAST owns its submitted frame. */
    private fun verifyAccessibility(activity: android.app.Activity) {
        val bridge = dev.artzig.ArtzigAccessibilityBridge
        fun snapshot() = org.json.JSONObject(bridge.snapshot())
        fun node(tree: org.json.JSONObject, text: String): org.json.JSONObject {
            val nodes = tree.getJSONArray("nodes")
            return (0 until nodes.length()).map { nodes.getJSONObject(it) }
                .singleOrNull { it.optString("text").equals(text, ignoreCase = true) }
                ?: error("Missing or ambiguous accessibility text '$text': $tree")
        }
        fun click(tree: org.json.JSONObject, target: org.json.JSONObject) = bridge.perform(
            tree.getLong("revision"), target.getLong("window_id"), target.getInt("view_id"),
            target.getInt("virtual_id"), android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null)
        fun settle() {
            val deadline = android.os.SystemClock.uptimeMillis() + 5_000
            do {
                drainDueMessages()
                if (bridge.isIdle()) return
                android.os.SystemClock.sleep(1)
            } while (android.os.SystemClock.uptimeMillis() < deadline)
            error("Stock accessibility roots did not settle")
        }
        settle()
        // Core Wayland does not supply a task-spanning PiP placement policy.
        // Preserve Activity's stock feature-gated false return, without creating
        // a hidden pinned task or changing the current activity's lifecycle.
        check(!activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE))
        check(!activity.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build()))
        check(!activity.isInPictureInPictureMode)
        val initial = snapshot()
        check(initial.getJSONArray("windows").length() == 1)
        val initialNodes = initial.getJSONArray("nodes")
        check((0 until initialNodes.length()).any {
            initialNodes.getJSONObject(it).optString("resource_id") == "android:id/edit"
        }) { "Stock accessibility omitted resource identifiers" }
        val increment = node(initial, "Increment counter")
        check(click(initial, increment))
        check(!click(initial, increment)) { "A stale accessibility revision accepted a second action" }
        settle()
        val updated = snapshot()
        node(updated, "Counter: 1")
        check(node(updated, "Increment counter").getLong("window_id") == increment.getLong("window_id"))
        check(click(updated, node(updated, "Show attached dialog")))
        check(!bridge.isIdle()) { "A registered dialog with its first traversal pending reported idle" }
        settle()
        val dialog = snapshot()
        check(dialog.getJSONArray("windows").length() == 2) {
            "Dialog roots=${android.view.HostedRootFrames.managedRoots().map { root ->
                "${root.view}: stopped=${android.view.HostedRootFrames.stopped(root)}, shown=${root.view?.isShown}"
            }}; tree=$dialog"
        }
        val close = node(dialog, "Close dialog")
        check(close.getLong("window_id") != increment.getLong("window_id"))
        val roots = android.view.HostedRootFrames.managedRoots()
        val host = roots.mapNotNull { it.view?.findViewByAccessibilityIdTraversal<android.view.View>(close.getInt("view_id")) }
            .single()
        val info = host.createAccessibilityNodeInfo()
        try {
            val expected = android.graphics.Rect()
            info.getBoundsInScreen(expected)
            val actual = close.getJSONObject("bounds")
            check(expected.left != 0 || expected.top != 0) { "Dialog bounds did not exercise a screen offset" }
            check(actual.getInt("left") == expected.left && actual.getInt("top") == expected.top &&
                actual.getInt("right") == expected.right && actual.getInt("bottom") == expected.bottom) {
                "Stock dialog accessibility bounds were translated twice: $actual versus $expected"
            }
        } finally { info.recycle() }
        check(click(dialog, close))
        settle()
        val dismissed = snapshot()
        check(dismissed.getJSONArray("windows").length() == 1)
        check(!bridge.perform(dismissed.getLong("revision"), close.getLong("window_id"),
            close.getInt("view_id"), close.getInt("virtual_id"),
            android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null))
        check(activity.window.decorView.viewRootImpl in android.view.HostedRootFrames.managedRoots())
        println("Stock accessibility: activity/dialog roots, actions, screen bounds and stale/removed targets verified")
    }

    private fun verifyClientWindow(session: android.view.IWindowSession, activity: android.app.Activity) {
        val window = object : android.view.IWindow.Stub(),
            android.view.IWindow by android.view.IWindow.Default() {
            override fun asBinder(): android.os.IBinder = this
            override fun insetsControlChanged(state: android.view.InsetsState,
                controls: android.view.InsetsSourceControl.Array) { controls.release() }
        }
        val params = android.view.WindowManager.LayoutParams().apply {
            type = android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION
            token = activity.activityToken
            packageName = activity.packageName
            title = "Stock session buffer gate"
            width = 320
            height = 240
            format = android.graphics.PixelFormat.RGBA_8888
        }
        val channel = android.view.InputChannel()
        val controls = android.view.InsetsSourceControl.Array()
        val result = android.view.WindowRelayoutResult()
        var added = false
        var blast: android.graphics.BLASTBufferQueue? = null
        var surface: android.view.Surface? = null
        try {
            // These types pass PhoneWindowManager's initial permission check.
            // The host must reject their missing presentation capability before
            // creating an input window or validating an unrelated app token.
            for (type in intArrayOf(android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD,
                android.view.WindowManager.LayoutParams.TYPE_WALLPAPER,
                android.view.WindowManager.LayoutParams.TYPE_TOAST,
                android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)) {
                val unsupported = android.view.WindowManager.LayoutParams().apply {
                    copyFrom(params)
                    this.type = type
                }
                val rejected = session.addToDisplayAsUser(window, unsupported, android.view.View.VISIBLE,
                    Display.DEFAULT_DISPLAY, activity.userId, android.view.WindowInsets.Type.defaultVisible(),
                    channel, android.view.InsetsState(), controls, android.graphics.Rect(), floatArrayOf(1f))
                check(rejected == android.view.WindowManagerGlobal.ADD_INVALID_TYPE) {
                    "Taskless system window type=$type returned $rejected"
                }
                check(channel.token == null) { "Rejected window retained an input channel" }
            }
            println("Stock WMS taskless system window rejection: no ghost input channels")
            val add = session.addToDisplayAsUser(window, params, android.view.View.VISIBLE,
                Display.DEFAULT_DISPLAY, activity.userId, android.view.WindowInsets.Type.defaultVisible(),
                channel, android.view.InsetsState(), controls, android.graphics.Rect(), floatArrayOf(1f))
            check(add >= android.view.WindowManagerGlobal.ADD_OKAY) { "WMS rejected window: $add" }
            added = true
            checkNotNull(channel.token) { "WMS did not create an input channel" }
            session.relayout(window, params, 320, 240, android.view.View.VISIBLE, 0, 0, -1, result)
            check(!result.frames.frame.isEmpty) { "WMS returned an empty window frame" }
            check(result.surfaceControl.isValid) { "WMS returned no application surface" }
            val queue = android.graphics.BLASTBufferQueue("stock-session-gate", true).also { blast = it }
            queue.update(result.surfaceControl, result.frames.frame.width(), result.frames.frame.height(),
                params.format)
            val producer = queue.createSurfaceWithHandle().also { surface = it }
            val committed = java.util.concurrent.CountDownLatch(1)
            check(queue.syncNextTransaction { transaction ->
                transaction.addTransactionCommittedListener(java.util.concurrent.Executor { it.run() }) {
                    committed.countDown()
                }
                session.finishDrawing(window, transaction, result.syncSeqId)
                transaction.close()
            })
            val canvas = producer.lockHardwareCanvas()
            canvas.drawColor(android.graphics.Color.BLUE)
            producer.unlockCanvasAndPost(canvas)
            check(committed.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "WMS draw did not commit" }
            check(queue.lastAcquiredFrameNum > 0)
            println("Stock WMS application window: ${result.frames.frame}, input channel and BLAST draw committed")
        } finally {
            if (added) session.remove(window.asBinder())
            surface?.release()
            blast?.destroy()
            result.surfaceControl.release()
            result.activeControls.release()
            controls.release()
            channel.dispose()
        }
    }
}
