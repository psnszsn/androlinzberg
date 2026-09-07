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
        val installer = checkNotNull(android.app.HostedServiceClient.installerOrNull())
        val packageName = installer.installPackage(args.single(), null)
        val application = android.app.HostedServiceClient.attachApplication(packageName)
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
        println("Stock WMS registered application session: $packageName")
        var resumed: android.app.Activity? = null
        android.app.ActivityThread.configureActivityHost(object : android.app.ActivityThread.HostAdapter {
            override fun showActivity(activity: android.app.Activity) { resumed = activity }
            override fun clearActivityWindows() { resumed = null }
            override fun resizeActivityRoot(activity: android.app.Activity, width: Int, height: Int) { }
        })
        application.startActivity(checkNotNull(application.packageManager.getLaunchIntentForPackage(packageName)))
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        while (resumed == null && android.os.SystemClock.uptimeMillis() < deadline) {
            val message = android.os.Looper.myQueue().`pollDueMessage$artzig`()
            if (message == null) android.os.SystemClock.sleep(1)
            else message.target.dispatchMessage(message)
        }
        checkNotNull(resumed) { "Stock ATMS did not resume the fixture activity" }
        check(resumed!!.taskId > 0)
        println("Stock ATMS resumed application task: ${resumed!!.taskId}")
        println("Stock WMS/ATMS boot: display=${info.displayId} physical=${initial.x}x${initial.y}")
    }
}
