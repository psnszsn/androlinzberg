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
        val startupGate = if (dev.artzig.testing.ProcessInspection.enabled) {
            java.io.File(context.createPackageContext(pkg, 0).filesDir, "inspection-startup-gate").apply {
                check(parentFile!!.isDirectory || parentFile!!.mkdirs())
                check(createNewFile())
            }
        } else null
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
        if (startupGate != null) try { verifyStartingInspection(running.pid, deadline) }
        finally { check(startupGate.delete()) }
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
            if (System.getenv("AZK_TASK_WINDOWS") == "1") verifyWaylandTask(pkg, running.pid, manager)
            else {
                if (dev.artzig.testing.ProcessInspection.enabled) verifyInspection(running.pid, info.uid, pkg)
                verifyComposedTask(pkg)
                StockTaskShellSmoke.run(pkg)
            }
        }
        println("Stock first-process launch: installed uid=${info.uid}, attached pid=${running.pid}, resumed activity")
    }

    private fun inspectionProcesses(registry: dev.artzig.testing.IInspectionRegistry): List<android.os.Bundle> =
        registry.listProcesses().getParcelableArrayList("processes", android.os.Bundle::class.java).orEmpty()

    private fun verifyStartingInspection(pid: Int, deadline: Long) {
        val registry = dev.artzig.testing.IInspectionRegistry.Stub.asInterface(
            android.os.ServiceManager.getService(dev.artzig.testing.ProcessInspection.SERVICE))
        var process: android.os.Bundle? = null
        while (SystemClock.uptimeMillis() < deadline) {
            process = inspectionProcesses(registry).singleOrNull { it.getInt("pid") == pid }
            if (process != null) break
            SystemClock.sleep(5)
        }
        val pending = checkNotNull(process) { "Starting app did not register inspection" }
        check(!pending.getBoolean("ready"))
        val before = org.json.JSONObject(registry.diagnostics(0, 0))
        val started = SystemClock.uptimeMillis()
        val observation = registry.observe(pid, pending.getLong("generation"), 0, 0, 0)
        check(observation.getBoolean("starting") && !observation.containsKey("snapshot"))
        check(SystemClock.uptimeMillis() - started < 3_000) { "Starting inspection waited behind app main" }
        check(!registry.perform(pid, pending.getLong("generation"), 0, 1, 1, -1, 4, null))
        val model = dev.artzig.testing.SessionObservation(registry)
        model.refresh()
        check(!model.idle())
        val tree = org.json.JSONObject(model.snapshot())
        check(tree.getJSONArray("starting_processes").length() > 0)
        check(tree.getJSONArray("unavailable_processes").length() == 0)
        val after = org.json.JSONObject(registry.diagnostics(before.getLong("log_cursor"), before.getLong("issue_cursor")))
        check(after.getJSONArray("issues").length() == 0) { "App startup was recorded as an inspection failure: $after" }
    }

    private fun verifyInspection(pid: Int, uid: Int, packageName: String) {
        val registry = dev.artzig.testing.IInspectionRegistry.Stub.asInterface(
            android.os.ServiceManager.getService(dev.artzig.testing.ProcessInspection.SERVICE))
        val sessionBefore = org.json.JSONObject(registry.diagnostics(0, 0))
        val process = inspectionProcesses(registry).single { it.getInt("pid") == pid }
        check(process.getInt("uid") == uid && process.getString("package") == packageName && process.getBoolean("ready"))
        val generation = process.getLong("generation")
        check(generation > 0)
        check(runCatching { registry.observe(pid, generation + 1, 0, 0, 0) }.exceptionOrNull() is IllegalStateException)
        var before = registry.observe(pid, generation, 0, 0, 0)
        var tree = org.json.JSONObject(checkNotNull(before.getString("snapshot")))
        fun target(tree: org.json.JSONObject): org.json.JSONObject {
            val nodes = tree.getJSONArray("nodes")
            return (0 until nodes.length()).map { nodes.getJSONObject(it) }
                .single { it.optString("text") == "Cold process activity" }
        }
        var text = target(tree)
        var windowId = text.getLong("window_id")
        val taskId = checkNotNull(before.getBundle("window_tasks")).getInt(windowId.toString(), -1)
        check(taskId > 0 && ActivityTaskManager.getService()
            .getTasks(128, false, true, android.view.Display.DEFAULT_DISPLAY).any {
                it.taskId == taskId && it.baseActivity?.packageName == packageName
            })
        check(registry.validateInputTarget(pid, generation, tree.getLong("revision"), windowId) == taskId)
        check(registry.validateInputTarget(pid, generation, tree.getLong("revision"), Long.MAX_VALUE) == -1)
        check(!before.containsKey("window_tokens")) { "Raw app window tokens escaped the system inspection boundary" }
        check(!text.getBoolean("selected"))
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!registry.perform(pid, generation, tree.getLong("revision"), windowId,
            text.getInt("view_id"), text.getInt("virtual_id"),
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SELECT, null)) {
            check(SystemClock.uptimeMillis() < deadline) { "Inspection tree never stabilized for the remote action" }
            SystemClock.sleep(5)
            before = registry.observe(pid, generation, 0, 0, 0)
            tree = org.json.JSONObject(checkNotNull(before.getString("snapshot")))
            text = target(tree)
            windowId = text.getLong("window_id")
        }
        check(!registry.perform(pid, generation, tree.getLong("revision"), windowId,
            text.getInt("view_id"), text.getInt("virtual_id"),
            android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_SELECTION, null))
        check(registry.validateInputTarget(pid, generation, tree.getLong("revision"), windowId) == -1)
        val runtime = org.json.JSONObject(checkNotNull(before.getString("runtime")))
        val after = registry.observe(pid, generation, tree.getLong("revision"),
            runtime.getLong("log_cursor"), runtime.getLong("issue_cursor"))
        val afterNodes = org.json.JSONObject(checkNotNull(after.getString("snapshot"))).getJSONArray("nodes")
        check((0 until afterNodes.length()).map { afterNodes.getJSONObject(it) }
            .single { it.getString("id") == text.getString("id") }.getBoolean("selected"))
        check(org.json.JSONObject(checkNotNull(after.getString("runtime"))).getJSONArray("issues").length() == 0)
        val sessionAfter = org.json.JSONObject(registry.diagnostics(
            sessionBefore.getLong("log_cursor"), sessionBefore.getLong("issue_cursor")))
        val sessionLogs = sessionAfter.getJSONArray("logs")
        check((0 until sessionLogs.length()).any {
            sessionLogs.getJSONObject(it).getJSONObject("process").getInt("pid") == pid
        }) { "The session journal lost the app's source identity" }
        var lastSequence = sessionBefore.getLong("log_cursor")
        for (index in 0 until sessionLogs.length()) {
            val sequence = sessionLogs.getJSONObject(index).getLong("seq")
            check(sequence > lastSequence)
            lastSequence = sequence
        }
        check(sessionAfter.getJSONArray("issues").length() == 0) { sessionAfter.toString() }
        val sessionObservation = dev.artzig.testing.SessionObservation(registry)
        sessionObservation.refresh()
        val mergedTree = org.json.JSONObject(sessionObservation.snapshot())
        val mergedNodes = mergedTree.getJSONArray("nodes")
        val mergedText = (0 until mergedNodes.length()).map { mergedNodes.getJSONObject(it) }
            .single { it.optString("text") == "Cold process activity" && it.getInt("pid") == pid }
        check(mergedText.getInt("task_id") == taskId)
        check(mergedText.getLong("process_generation") == generation)
        check(mergedText.getString("id").startsWith("n:${mergedText.getLong("window_id")}:") )
        println("Stock process inspection: authenticated identity, WMS task mapping, remote action, stale generations/revisions and issue cursors verified")
    }

    private fun verifyWaylandTask(packageName: String, pid: Int, manager: ActivityManager) {
        val tasks = ActivityTaskManager.getService()
        fun applicationTasks() = tasks.getTasks(10, false, true, android.view.Display.DEFAULT_DISPLAY)
            .filter { it.baseActivity?.packageName == packageName }
        fun await(description: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (!condition()) {
                check(SystemClock.uptimeMillis() < deadline) {
                    "$description; tasks=${applicationTasks().map { "${it.taskId}:${it.configuration.windowConfiguration.bounds}" }}"
                }
                SystemClock.sleep(5)
            }
        }
        val display = checkNotNull(android.hardware.display.DisplayManagerGlobal.getInstance()
            .getDisplayInfo(android.view.Display.DEFAULT_DISPLAY))
        check(display.logicalWidth == TaskGeometry.DESKTOP_SIZE && display.logicalHeight == TaskGeometry.DESKTOP_SIZE)
        val physical = android.view.SurfaceControl.getDynamicDisplayInfo(
            com.android.server.display.DisplayControl.getPhysicalDisplayIds().single())
        check(physical.supportedDisplayModes.single().width == 1280 &&
            physical.supportedDisplayModes.single().height == 720) { "Synthetic metadata resized the physical framebuffer" }
        val task = applicationTasks().single()
        val commands = System.`in`.bufferedReader()
        fun pixels(marker: String, taskId: Int) {
            println("$marker $taskId")
            System.out.flush()
            check(commands.readLine() != null) { "Wayland screenshot runner disconnected" }
        }
        // The Cage runner inspects actual compositor pixels before releasing
        // this gate. The driver owns no organizer or presentation surface.
        pixels("TASK_WINDOW_READY", task.taskId)
        if (dev.artzig.testing.ProcessInspection.enabled) verifyInspection(pid,
            manager.runningAppProcesses.orEmpty().single { it.pid == pid }.uid, packageName)
        val inputDevices = android.hardware.input.IInputManager.Stub.asInterface(
            android.os.ServiceManager.getService(android.content.Context.INPUT_SERVICE))
        await("Compositor character map was not published to stock input clients") {
            val keyboard = inputDevices.getInputDevice(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
            keyboard != null && keyboard.generation > 0 &&
                keyboard.keyCharacterMap.get(android.view.KeyEvent.KEYCODE_A, 0) == 'a'.code &&
                keyboard.keyCharacterMap.get(android.view.KeyEvent.KEYCODE_A, android.view.KeyEvent.META_SHIFT_ON) == 'A'.code
        }
        println("Stock Wayland keyboard: compositor layout published through InputDevice")
        val firstBounds = android.graphics.Rect(applicationTasks().single().configuration.windowConfiguration.bounds)
        check(tasks.startActivity(null, "android", null,
            Intent().setClassName(packageName, "$packageName.ColdProcessActivity")
                .putExtra("color", android.graphics.Color.MAGENTA)
                .putExtra("title", "Magenta task")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            null, null, null, -1, 0, null, null) == ActivityManager.START_SUCCESS)
        await("Second Android task never reached disjoint synthetic bounds") {
            val live = applicationTasks()
            live.size == 2 && live.all { it.displayId == android.view.Display.DEFAULT_DISPLAY } &&
                live.all { it.configuration.windowConfiguration.bounds.width() == firstBounds.width() &&
                    it.configuration.windowConfiguration.bounds.height() == firstBounds.height() } &&
                !android.graphics.Rect.intersects(live[0].configuration.windowConfiguration.bounds,
                    live[1].configuration.windowConfiguration.bounds)
        }
        val live = applicationTasks()
        check(live.single { it.taskId == task.taskId }.configuration.windowConfiguration.bounds == firstBounds)
        for (info in live) {
            val bounds = info.configuration.windowConfiguration.bounds
            check(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= display.logicalWidth &&
                bounds.bottom <= display.logicalHeight)
        }
        val second = live.single { it.taskId != task.taskId }
        pixels("SECOND_TASK_WINDOW_READY", second.taskId)
        // The pre-presentation snapshot may still contain ActivityStarter's
        // provisional placement. Compare against the actual rendered geometry.
        val secondBounds = android.graphics.Rect(applicationTasks().single { it.taskId == second.taskId }
            .configuration.windowConfiguration.bounds)
        val options = android.app.ActivityOptions.makeBasic().apply { setLaunchTaskId(second.taskId) }
        check(tasks.startActivity(null, "android", null,
            Intent().setClassName(packageName, "$packageName.ColdGroupedActivity")
                .putExtra("color", android.graphics.Color.rgb(255, 127, 0))
                .putExtra("title", "Magenta task").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            null, null, null, -1, 0, null, options.toBundle()) == ActivityManager.START_SUCCESS)
        await("Additional activity did not remain inside its selected task") {
            val current = applicationTasks()
            current.size == 2 && current.single { it.taskId == second.taskId }.numActivities == 2
        }
        await("A new activity changed the task's compositor-owned bounds $secondBounds") {
            applicationTasks().single { it.taskId == second.taskId }.configuration.windowConfiguration.bounds ==
                secondBounds
        }
        pixels("GROUPED_ACTIVITY_WINDOW_READY", second.taskId)
        check(tasks.removeTask(second.taskId))
        await("First task did not survive sibling removal") { applicationTasks().map { it.taskId } == listOf(task.taskId) }
        check(manager.runningAppProcesses.orEmpty().any { it.pid == pid }) { "Sibling removal killed a process with a live task" }
        check(applicationTasks().single().configuration.windowConfiguration.bounds == firstBounds)
        pixels("SURVIVING_TASK_WINDOW_READY", task.taskId)
        check(tasks.removeTask(task.taskId))
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline &&
            tasks.getTasks(10, false, true, android.view.Display.DEFAULT_DISPLAY).any { it.taskId == task.taskId }) {
            SystemClock.sleep(5)
        }
        check(tasks.getTasks(10, false, true, android.view.Display.DEFAULT_DISPLAY).none { it.taskId == task.taskId })
        while (SystemClock.uptimeMillis() < deadline && manager.runningAppProcesses.orEmpty().any { it.pid == pid }) {
            SystemClock.sleep(5)
        }
        check(manager.runningAppProcesses.orEmpty().none { it.pid == pid }) { "Removed task's empty process remained alive" }
        if (dev.artzig.testing.ProcessInspection.enabled) {
            val registry = dev.artzig.testing.IInspectionRegistry.Stub.asInterface(
                android.os.ServiceManager.getService(dev.artzig.testing.ProcessInspection.SERVICE))
            while (inspectionProcesses(registry).any { it.getInt("pid") == pid } &&
                SystemClock.uptimeMillis() < deadline) SystemClock.sleep(5)
            check(inspectionProcesses(registry).none { it.getInt("pid") == pid })
        }
        val exit = manager.getHistoricalProcessExitReasons(packageName, pid, 1).single()
        check(exit.reason == android.app.ApplicationExitInfo.REASON_USER_REQUESTED &&
            exit.subReason == android.app.ApplicationExitInfo.SUBREASON_REMOVE_TASK) { "Task removal exit reason: $exit" }
        println("Stock Wayland task: two tasks, grouped activities, one synthetic display, independent pixels and removal passed")
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
