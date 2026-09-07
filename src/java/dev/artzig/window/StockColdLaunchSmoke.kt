package dev.artzig.window

import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import dev.artzig.BinderSession
import dev.artzig.HostContexts
import dev.artzig.HostedServiceClient

/** Trusted test driver: install before any package peer exists, then let ATMS
 * create the application's first process. The driver never attaches as the APK. */
object StockColdLaunchSmoke {
    @JvmStatic fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        BinderSession.setIdentity("android", true)
        check(WindowBackend.stock && BinderSession.assignedUid() == Process.SYSTEM_UID)
        val context = HostContexts.systemContext(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")),
            320, 240, 120, 0)
        val manager = context.getSystemService(ActivityManager::class.java)
        val pkg = "dev.artzig.fixtures.windows"
        check(manager.runningAppProcesses.orEmpty().none { pkg in it.pkgList.orEmpty() })
        val packages = android.app.ActivityThread.getPackageManager()
        check(packages.getApplicationInfo(pkg, 0L, 0) == null)
        check(checkNotNull(HostedServiceClient.installerOrNull()).installPackage(args.single(), null) == pkg)
        val info = checkNotNull(packages.getApplicationInfo(pkg, 0L, 0))
        check(info.uid in Process.FIRST_APPLICATION_UID..Process.LAST_APPLICATION_UID)
        check(BinderSession.reservePackageUid(pkg) == info.uid)
        check(manager.runningAppProcesses.orEmpty().none { it.uid == info.uid })
        val result = ActivityTaskManager.getService().startActivity(null, "android", null,
            Intent().setClassName(pkg, "$pkg.ColdProcessActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            null, null, null, -1, 0, null, null)
        check(result == ActivityManager.START_SUCCESS) { "Cold start rejected: $result" }
        val deadline = SystemClock.uptimeMillis() + 15_000
        var process: ActivityManager.RunningAppProcessInfo? = null
        while (SystemClock.uptimeMillis() < deadline) {
            process = manager.runningAppProcesses.orEmpty().singleOrNull { it.processName == "$pkg:cold" }
            if (process != null) break
            SystemClock.sleep(5)
        }
        val running = checkNotNull(process) { "ATMS did not create the first application process" }
        check(running.uid == info.uid && running.pid != Process.myPid())
        var events: ArrayList<String>? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val state = checkNotNull(context.contentResolver.call(android.net.Uri.parse("content://$pkg.cold"),
                "snapshot", null, null))
            check(state.getInt("pid") == running.pid && state.getInt("uid") == info.uid)
            check(state.getString("process") == "$pkg:cold")
            events = state.getStringArrayList("events")
            if (events?.contains("resume") == true) break
            SystemClock.sleep(5)
        }
        check(events == arrayListOf("provider", "create", "start", "resume")) { "Cold callbacks: $events" }
        println("Stock first-process launch: installed uid=${info.uid}, attached pid=${running.pid}, resumed activity")
    }
}
