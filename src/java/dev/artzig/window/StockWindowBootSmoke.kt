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
        println("Stock WMS/ATMS boot: display=${info.displayId} physical=${initial.x}x${initial.y}")
    }
}
