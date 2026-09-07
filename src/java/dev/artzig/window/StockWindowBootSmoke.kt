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
        println("Stock WMS registered application session: $packageName")
        var resumed: android.app.Activity? = null
        dev.artzig.HostedActivityProcess.configureActivityHost(object : dev.artzig.HostedActivityProcess.HostAdapter {
            override fun showActivity(activity: android.app.Activity) { resumed = activity }
            override fun clearActivityWindows() { resumed = null }
            override fun resizeActivityRoot(activity: android.app.Activity, width: Int, height: Int) { }
        })
        application.startActivity(checkNotNull(application.packageManager.getLaunchIntentForPackage(packageName)))
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        while (resumed == null && android.os.SystemClock.uptimeMillis() < deadline) {
            val message = android.os.Looper.myQueue().`pollDueMessage$artzig`()
            if (message == null) android.os.SystemClock.sleep(1)
            else try { message.target.dispatchMessage(message) }
            finally { android.os.MessageQueue.`recycleDispatched$artzig`(message) }
        }
        checkNotNull(resumed) { "Stock ATMS did not resume the fixture activity" }
        check(resumed!!.taskId > 0)
        android.app.ActivityManager.getMyMemoryState(memory)
        check(memory.processState == android.app.ActivityManager.PROCESS_STATE_TOP)
        println("Stock ATMS resumed application task: ${resumed!!.taskId}")
        verifyClientWindow(session, resumed!!)
        verifyColdActivity(application)
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
            if (snapshot?.getStringArrayList("events")?.contains("resume") == true) break
            android.os.SystemClock.sleep(5)
        }
        val state = checkNotNull(snapshot)
        check(state.getInt("pid") == running.pid && state.getInt("uid") == running.uid)
        check(state.getString("process") == processName)
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

    private fun drainDueMessages() {
        while (true) {
            val message = android.os.Looper.myQueue().`pollDueMessage$artzig`() ?: return
            try { message.target.dispatchMessage(message) }
            finally { android.os.MessageQueue.`recycleDispatched$artzig`(message) }
        }
    }

    /** Exercise the actual session contract before switching the ViewRootImpl client.
     * The test window has no view hierarchy; BLAST owns its submitted frame. */
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
