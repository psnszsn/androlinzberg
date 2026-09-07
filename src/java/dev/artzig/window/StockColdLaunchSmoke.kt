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
        dev.artzig.StockReadbackSmoke.verifyGeometry()
        StockClientServiceSmoke.run()
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
        var frameCommitted = false
        while (SystemClock.uptimeMillis() < deadline) {
            val state = checkNotNull(context.contentResolver.call(android.net.Uri.parse("content://$pkg.cold"),
                "snapshot", null, null))
            check(state.getInt("pid") == running.pid && state.getInt("uid") == info.uid)
            check(state.getString("process") == "$pkg:cold")
            events = state.getStringArrayList("events")
            frameCommitted = state.getBoolean("frameCommitted")
            if (events?.contains("resume") == true &&
                (!android.view.HostedRootFrames.stockClients || frameCommitted)) break
            SystemClock.sleep(5)
        }
        check(events == arrayListOf("provider", "create", "start", "resume")) { "Cold callbacks: $events" }
        if (android.view.HostedRootFrames.stockClients) {
            check(frameCommitted) { "Stock ViewRootImpl never committed an APK frame" }
            verifyComposedTask(pkg)
        }
        println("Stock first-process launch: installed uid=${info.uid}, attached pid=${running.pid}, resumed activity")
    }

    /** Inspect the task leash that a desktop organizer will present. This
     * requires the APK's actual stock root buffer to reach SF composition. */
    private fun verifyComposedTask(packageName: String) {
        val organizer = android.window.TaskOrganizer(null, java.util.concurrent.Executor { it.run() })
        val tasks = organizer.registerOrganizer()
        try {
            val task = tasks.single { it.taskInfo.topActivity?.packageName == packageName }
            val leash = task.leash
            android.view.SurfaceControl.Transaction().use { it.show(leash).apply() }
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (true) {
                val capture = checkNotNull(android.window.ScreenCapture.captureLayers(
                    leash, android.graphics.Rect(), 1f)) { "SF task capture failed" }
                try {
                    val hardware = checkNotNull(capture.asBitmap())
                    try {
                        val bitmap = checkNotNull(hardware.copy(android.graphics.Bitmap.Config.ARGB_8888, false))
                        try {
                            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                            val cyan = android.graphics.Color.red(pixel) < 8 && android.graphics.Color.green(pixel) > 247 &&
                                android.graphics.Color.blue(pixel) > 247
                            val inset = minOf(bitmap.width, bitmap.height) / 16
                            val corners = intArrayOf(bitmap.getPixel(inset, inset),
                                bitmap.getPixel(bitmap.width - inset - 1, inset),
                                bitmap.getPixel(inset, bitmap.height - inset - 1),
                                bitmap.getPixel(bitmap.width - inset - 1, bitmap.height - inset - 1))
                            val oriented = corners.contentEquals(intArrayOf(android.graphics.Color.RED,
                                android.graphics.Color.GREEN, android.graphics.Color.BLUE, android.graphics.Color.YELLOW))
                            if ((cyan && oriented) || SystemClock.uptimeMillis() >= deadline) {
                                System.getenv("AZK_STOCK_TASK_CAPTURE_PATH")?.let { path ->
                                    val file = java.io.File(path)
                                    file.parentFile?.mkdirs()
                                    file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                                    java.io.FileOutputStream("$path.sf.txt").use {
                                        android.os.ServiceManager.getService("SurfaceFlinger").dump(it.fd, emptyArray())
                                    }
                                }
                                check(cyan && oriented) { "Stock task corner colors=${corners.map { it.toUInt().toString(16) }}; center: ${pixel.toUInt().toString(16)}; task=${task.taskInfo.taskId} bounds=${task.taskInfo.configuration.windowConfiguration.bounds}, capture=${bitmap.width}x${bitmap.height}" }
                                println("Stock ViewRootImpl task composition: ${bitmap.width}x${bitmap.height}, APK center and four oriented corners verified")
                                return
                            }
                        } finally { bitmap.recycle() }
                    } finally { hardware.recycle() }
                } finally { capture.hardwareBuffer.close() }
                SystemClock.sleep(16)
            }
        } finally {
            try { organizer.unregisterOrganizer() }
            finally { tasks.forEach { it.leash.release() } }
        }
    }
}
