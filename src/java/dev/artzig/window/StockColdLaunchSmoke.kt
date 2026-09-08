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
        WaylandCursorSmoke.run(context)
        StockClientServiceSmoke.run()
        val manager = context.getSystemService(ActivityManager::class.java)
        val pkg = "dev.artzig.fixtures.windows"
        check(manager.runningAppProcesses.orEmpty().none { pkg in it.pkgList.orEmpty() })
        val packages = android.app.ActivityThread.getPackageManager()
        check(packages.getApplicationInfo(pkg, 0L, 0) == null)
        if (System.getenv("AZK_TASK_WINDOWS") == "1") {
            val input = android.hardware.input.IInputManager.Stub.asInterface(
                android.os.ServiceManager.getService(Context.INPUT_SERVICE))
            val keyboard = checkNotNull(input.getInputDevice(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD))
            val seatKeyboard = checkNotNull(input.getInputDevice(1))
            check(!seatKeyboard.isVirtual && seatKeyboard.isExternal)
            check(seatKeyboard.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC)
            check(checkNotNull(seatKeyboard.keyCharacterMap.getEvents(charArrayOf('a'))).all { it.deviceId == 1 })
            check(keyboard.generation > 0)
            check(keyboard.keyCharacterMap.get(android.view.KeyEvent.KEYCODE_A, 0) == 'a'.code)
            check(keyboard.keyCharacterMap.get(android.view.KeyEvent.KEYCODE_A,
                android.view.KeyEvent.META_SHIFT_ON) == 'A'.code)
            val supported = BooleanArray(2)
            check(input.hasKeys(-1, android.view.InputDevice.SOURCE_KEYBOARD,
                intArrayOf(android.view.KeyEvent.KEYCODE_A, android.view.KeyEvent.KEYCODE_DEL), supported))
            check(supported.all { it })
            check(input.hasKeys(1, android.view.InputDevice.SOURCE_KEYBOARD,
                intArrayOf(android.view.KeyEvent.KEYCODE_A, android.view.KeyEvent.KEYCODE_DEL), supported))
            check(supported.all { it })
            check(ActivityManager.getService().configuration.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY) {
                "WM did not publish the seat keyboard configuration before APK startup"
            }
            println("Stock Wayland keyboard: layout and keys available before APK installation")
        }
        check(checkNotNull(HostedServiceClient.installerOrNull()).installPackage(args.single(), null) == pkg)
        val info = checkNotNull(packages.getApplicationInfo(pkg, 0L, 0))
        check(info.uid in Process.FIRST_APPLICATION_UID..Process.LAST_APPLICATION_UID)
        check(BinderSession.reservePackageUid(pkg) == info.uid)
        val grammar = android.app.IGrammaticalInflectionManager.Stub.asInterface(
            android.os.ServiceManager.getService(Context.GRAMMATICAL_INFLECTION_SERVICE))
        grammar.setRequestedApplicationGrammaticalGender(pkg, 0,
            android.content.res.Configuration.GRAMMATICAL_GENDER_FEMININE)
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
        verifyGrammaticalConfiguration(context, pkg, grammar)
        verifyNightConfiguration(context, pkg)
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

    private fun verifyGrammaticalConfiguration(context: Context, pkg: String,
        grammar: android.app.IGrammaticalInflectionManager) {
        fun snapshot() = checkNotNull(context.contentResolver.call(android.net.Uri.parse("content://$pkg.cold"),
            "snapshot", null, null))
        fun matches(state: android.os.Bundle, gender: Int) =
            state.getInt("applicationGender") == gender && state.getInt("activityGender") == gender &&
                state.getInt("reportedGender") == gender
        check(matches(snapshot(), android.content.res.Configuration.GRAMMATICAL_GENDER_FEMININE)) {
            "The install-time package override did not seed application, activity and ATMS configurations"
        }
        grammar.setRequestedApplicationGrammaticalGender(pkg, 0,
            android.content.res.Configuration.GRAMMATICAL_GENDER_MASCULINE)
        val deadline = SystemClock.uptimeMillis() + 10_000
        var state: android.os.Bundle
        do {
            state = snapshot()
            val callbacks = state.getStringArrayList("events").orEmpty()
            if (matches(state, android.content.res.Configuration.GRAMMATICAL_GENDER_MASCULINE) &&
                state.getBoolean("restoredState") && callbacks.lastIndexOf("resume") > callbacks.lastIndexOf("create") &&
                (!android.view.HostedRootFrames.stockClients || state.getBoolean("frameCommitted"))) break
            SystemClock.sleep(5)
        } while (SystemClock.uptimeMillis() < deadline)
        check(matches(state, android.content.res.Configuration.GRAMMATICAL_GENDER_MASCULINE)) {
            "The running package override bypassed ATMS or activity configuration: $state"
        }
        check(state.getBoolean("restoredState")) { "Configuration relaunch lost saved state: $state" }
        val callbacks = checkNotNull(state.getStringArrayList("events"))
        val previous = callbacks.indexOf("pause")
        val saved = callbacks.indexOf("save")
        val destroyed = callbacks.indexOf("destroy")
        val created = callbacks.lastIndexOf("create")
        val resumed = callbacks.lastIndexOf("resume")
        check(previous >= 0 && saved > previous && destroyed > saved && created > destroyed && resumed > created) {
            "Grammatical configuration relaunch ordering: $callbacks"
        }
        val tasks = ActivityTaskManager.getService()
        val taskId = tasks.getTasks(128, false, true, android.view.Display.DEFAULT_DISPLAY)
            .single { it.baseActivity?.packageName == pkg }.taskId
        val options = android.app.ActivityOptions.makeBasic().apply { setLaunchTaskId(taskId) }
        check(tasks.startActivity(null, "android", null,
            Intent().setClassName(pkg, "$pkg.ColdHandledConfigurationActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null, null, null, -1, 0, null,
            options.toBundle()) == ActivityManager.START_SUCCESS)
        fun awaitState(description: String, ready: (android.os.Bundle) -> Boolean): android.os.Bundle {
            val end = SystemClock.uptimeMillis() + 10_000
            do {
                val next = snapshot()
                if (ready(next)) return next
                SystemClock.sleep(5)
            } while (SystemClock.uptimeMillis() < end)
            error("$description: ${snapshot()}")
        }
        awaitState("Handled configuration activity did not resume") {
            it.getString("activityClass") == "ColdHandledConfigurationActivity" &&
                matches(it, android.content.res.Configuration.GRAMMATICAL_GENDER_MASCULINE)
        }
        grammar.setRequestedApplicationGrammaticalGender(pkg, 0,
            android.content.res.Configuration.GRAMMATICAL_GENDER_NEUTRAL)
        val handled = awaitState("Handled grammatical configuration was not delivered") {
            matches(it, android.content.res.Configuration.GRAMMATICAL_GENDER_NEUTRAL) &&
                it.getStringArrayList("activityEvents").orEmpty()
                    .contains("ColdHandledConfigurationActivity:configuration")
        }
        check(handled.getStringArrayList("activityEvents").orEmpty()
            .filter { it.startsWith("ColdHandledConfigurationActivity:") } == listOf(
                "ColdHandledConfigurationActivity:create", "ColdHandledConfigurationActivity:start",
                "ColdHandledConfigurationActivity:resume", "ColdHandledConfigurationActivity:configuration")) {
            "A handled configuration recreated or paused its activity: $handled"
        }
        context.contentResolver.call(android.net.Uri.parse("content://$pkg.cold"), "finish", null, null)
        awaitState("Original activity did not restore after closing the handled activity") {
            it.getString("activityClass") == "ColdProcessActivity" &&
                matches(it, android.content.res.Configuration.GRAMMATICAL_GENDER_NEUTRAL) &&
                (!android.view.HostedRootFrames.stockClients || it.getBoolean("frameCommitted"))
        }
        grammar.setRequestedApplicationGrammaticalGender(pkg, 0,
            android.content.res.Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED)
        awaitState("Clearing the package gender did not restore the inherited configuration") {
            matches(it, android.content.res.Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED) &&
                (!android.view.HostedRootFrames.stockClients || it.getBoolean("frameCommitted"))
        }
        println("Stock grammatical configuration: cold override, WPC getter, relaunch/saved state, handled callback and clearing passed")
    }

    private fun verifyNightConfiguration(context: Context, pkg: String) {
        val uri = android.net.Uri.parse("content://$pkg.cold")
        val ui = android.app.IUiModeManager.Stub.asInterface(
            android.os.ServiceManager.getService(Context.UI_MODE_SERVICE))
        fun snapshot() = checkNotNull(context.contentResolver.call(uri, "snapshot", null, null))
        fun appMode(mode: Int) { context.contentResolver.call(uri, "night", mode.toString(), null) }
        fun matches(state: android.os.Bundle, night: Int) =
            listOf("applicationUiMode", "reportedUiMode", "activityUiMode").all {
                state.getInt(it) and android.content.res.Configuration.UI_MODE_NIGHT_MASK == night
            }
        fun awaitState(description: String, ready: (android.os.Bundle) -> Boolean): android.os.Bundle {
            val end = SystemClock.uptimeMillis() + 10_000
            do {
                val next = snapshot()
                if (ready(next)) return next
                SystemClock.sleep(5)
            } while (SystemClock.uptimeMillis() < end)
            error("$description: ${snapshot()}")
        }
        val day = android.content.res.Configuration.UI_MODE_NIGHT_NO
        val night = android.content.res.Configuration.UI_MODE_NIGHT_YES
        val originalMode = ui.nightMode
        ui.setNightMode(android.app.UiModeManager.MODE_NIGHT_NO)
        awaitState("Global day configuration did not reach the application") { matches(it, day) }
        val before = snapshot().getStringArrayList("events").orEmpty().size
        appMode(android.app.UiModeManager.MODE_NIGHT_YES)
        val relaunched = awaitState("Application night mode did not relaunch through ATMS") {
            val callbacks = it.getStringArrayList("events").orEmpty().drop(before)
            matches(it, night) && it.getBoolean("restoredState") &&
                callbacks.lastIndexOf("resume") > callbacks.lastIndexOf("create") &&
                callbacks.contains("create") &&
                (!android.view.HostedRootFrames.stockClients || it.getBoolean("frameCommitted"))
        }
        val callbacks = relaunched.getStringArrayList("events").orEmpty().drop(before)
        check(callbacks.indexOf("pause") >= 0 && callbacks.indexOf("save") > callbacks.indexOf("pause") &&
            callbacks.indexOf("destroy") > callbacks.indexOf("save") &&
            callbacks.indexOf("create") > callbacks.indexOf("destroy")) { "Night relaunch ordering: $callbacks" }
        val tasks = ActivityTaskManager.getService()
        val taskId = tasks.getTasks(128, false, true, android.view.Display.DEFAULT_DISPLAY)
            .single { it.baseActivity?.packageName == pkg }.taskId
        val options = android.app.ActivityOptions.makeBasic().apply { setLaunchTaskId(taskId) }
        check(tasks.startActivity(null, "android", null,
            Intent().setClassName(pkg, "$pkg.ColdHandledConfigurationActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null, null, null, -1, 0, null,
            options.toBundle()) == ActivityManager.START_SUCCESS)
        val handledStart = awaitState("Night-mode handled activity did not resume") {
            it.getString("activityClass") == "ColdHandledConfigurationActivity" && matches(it, night)
        }.getStringArrayList("activityEvents").orEmpty().size
        appMode(android.app.UiModeManager.MODE_NIGHT_NO)
        val handled = awaitState("Handled night configuration was not delivered") {
            matches(it, day) && it.getStringArrayList("activityEvents").orEmpty().drop(handledStart)
                .contains("ColdHandledConfigurationActivity:configuration")
        }
        check(handled.getStringArrayList("activityEvents").orEmpty().drop(handledStart)
            .filter { it.startsWith("ColdHandledConfigurationActivity:") } ==
            listOf("ColdHandledConfigurationActivity:configuration")) { "Handled night mode lifecycle: $handled" }
        ui.setNightMode(android.app.UiModeManager.MODE_NIGHT_YES)
        val systemDeadline = SystemClock.uptimeMillis() + 10_000
        while (ActivityManager.getService().configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK != night &&
            SystemClock.uptimeMillis() < systemDeadline) SystemClock.sleep(5)
        check(ActivityManager.getService().configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK == night)
        check(matches(snapshot(), day)) { "Global night mode replaced the application's day override" }
        appMode(android.app.UiModeManager.MODE_NIGHT_AUTO)
        awaitState("Clearing application mode did not inherit global night mode") { matches(it, night) }
        context.contentResolver.call(uri, "finish", null, null)
        awaitState("Original activity did not restore after night configuration") {
            it.getString("activityClass") == "ColdProcessActivity" && matches(it, night) &&
                (!android.view.HostedRootFrames.stockClients || it.getBoolean("frameCommitted"))
        }
        ui.setNightMode(android.app.UiModeManager.MODE_NIGHT_NO)
        awaitState("Cleared application override did not follow global day mode") {
            matches(it, day) && (!android.view.HostedRootFrames.stockClients || it.getBoolean("frameCommitted"))
        }
        ui.setNightMode(originalMode)
        println("Stock night configuration: application override, WPC getter, relaunch/saved state, handled callback, global inheritance and clearing passed")
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
        if (System.getenv("AZK_TEST_DESKTOP_CONFIGURATIONS") == "1")
            verifyDesktopConfigurations(task.taskId, commands, ::pixels)

        if (dev.artzig.testing.ProcessInspection.enabled) verifyInspection(pid,
            manager.runningAppProcesses.orEmpty().single { it.pid == pid }.uid, packageName)
        val inputDevices = android.hardware.input.IInputManager.Stub.asInterface(
            android.os.ServiceManager.getService(android.content.Context.INPUT_SERVICE))
        // The harness initially owns only a keyboard. Create/remove/recreate a
        // compositor virtual pointer after checking truthful absence.
        fun pointerCapability(marker: String): Boolean {
            println("$marker ${task.taskId}")
            System.out.flush()
            val mask = checkNotNull(commands.readLine()).toInt()
            check(mask and 15 == mask)
            return mask and 1 != 0
        }
        check(inputDevices.getInputDevice(0) == null)
        check(pointerCapability("POINTER_DEVICE_CREATE"))
        await("Wayland pointer device publication") { inputDevices.getInputDevice(0) != null }
        val pointer = checkNotNull(inputDevices.getInputDevice(0))
        check(pointer.isEnabled && pointer.supportsSource(android.view.InputDevice.SOURCE_MOUSE))
        val retained = pointerCapability("POINTER_DEVICE_REMOVE")
        // A compositor may retain its aggregate pointer capability after the
        // last physical/test device disappears. Follow wl_seat, not our helper's
        // device lifetime; only an actual capability loss removes Android input.
        await("Wayland pointer capability after device removal") { (inputDevices.getInputDevice(0) != null) == retained }
        check(inputDevices.getInputDevice(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD) != null)
        check(pointerCapability("POINTER_DEVICE_RECREATE"))
        await("Wayland pointer device reappearance") { inputDevices.getInputDevice(0) != null }
        val currentPointer = checkNotNull(inputDevices.getInputDevice(0))
        check(if (retained) currentPointer.generation >= pointer.generation else currentPointer.generation > pointer.generation)
        println("Stock Wayland pointer: absence and compositor capability lifecycle passed; retained=$retained")
        await("Compositor character map was not published to stock input clients") {
            val keyboard = inputDevices.getInputDevice(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
            keyboard != null && keyboard.generation > 0 &&
                keyboard.keyCharacterMap.get(android.view.KeyEvent.KEYCODE_A, 0) == 'a'.code &&
                keyboard.keyCharacterMap.get(android.view.KeyEvent.KEYCODE_A, android.view.KeyEvent.META_SHIFT_ON) == 'A'.code
        }
        println("Stock Wayland keyboard: compositor layout published through InputDevice")
        val keySupport = BooleanArray(4)
        check(inputDevices.hasKeys(-1, android.view.InputDevice.SOURCE_KEYBOARD,
            intArrayOf(android.view.KeyEvent.KEYCODE_A, android.view.KeyEvent.KEYCODE_DEL,
                android.view.KeyEvent.KEYCODE_UNKNOWN, Int.MAX_VALUE), keySupport))
        check(keySupport.contentEquals(booleanArrayOf(true, true, false, false)))
        check(!inputDevices.hasKeys(0, android.view.InputDevice.SOURCE_MOUSE,
            intArrayOf(android.view.KeyEvent.KEYCODE_A), BooleanArray(1)))
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
        val presenter = IWaylandTaskPresenter.Stub.asInterface(
            android.os.ServiceManager.getService("dev.artzig.window.presenter"))
        await("Native task windows remained after removal") {
            presenter.testWindow(task.taskId).isEmpty() && presenter.testWindow(second.taskId).isEmpty()
        }
        val windowlessRetained = pointerCapability("WINDOWLESS_POINTER_REMOVE")
        await("Seat update was lost without task windows") { (inputDevices.getInputDevice(0) != null) == windowlessRetained }
        check(pointerCapability("WINDOWLESS_POINTER_RECREATE"))
        await("Seat capability did not reappear without task windows") { inputDevices.getInputDevice(0) != null }
        if (!windowlessRetained) check(checkNotNull(inputDevices.getInputDevice(0)).generation > currentPointer.generation)
        for ((marker, expected) in listOf("WINDOWLESS_KEYBOARD_GERMAN" to '"', "WINDOWLESS_KEYBOARD_US" to '@')) {
            val previous = checkNotNull(inputDevices.getInputDevice(-1)).generation
            println(marker)
            System.out.flush()
            check(commands.readLine() == "keyboard updated")
            await("Keyboard layout update was lost without task windows: $marker") {
                val keyboard = checkNotNull(inputDevices.getInputDevice(-1))
                keyboard.generation > previous && keyboard.keyCharacterMap.get(
                    android.view.KeyEvent.KEYCODE_2, android.view.KeyEvent.META_SHIFT_ON) == expected.code
            }
        }
        println("Stock Wayland keyboard: layout changes survive removal of every task window")
        println("Stock Wayland seat: capability updates survive removal of every task window")
        println("Stock Wayland task: two tasks, grouped activities, one synthetic display, independent pixels and removal passed")
    }

    private fun verifyDesktopConfigurations(taskId: Int, commands: java.io.BufferedReader,
        pixels: (String, Int) -> Unit) {
        val presenter = IWaylandTaskPresenter.Stub.asInterface(
            android.os.ServiceManager.getService("dev.artzig.window.presenter"))
        val displayBefore = android.content.res.Configuration(ActivityManager.getService().configuration)
        fun awaitGeometry(description: String, presented: Boolean = true, ready: (LongArray) -> Boolean): LongArray {
            val deadline = SystemClock.uptimeMillis() + 10_000
            do {
                val state = presenter.testWindow(taskId)
                if (state.size == 16 && state[10] == state[11] && state[12] > 0 &&
                    (!presented || state[12] == state[13]) && ready(state)) return state
                SystemClock.sleep(5)
            } while (SystemClock.uptimeMillis() < deadline)
            error("$description: ${presenter.testWindow(taskId).contentToString()}")
        }
        val initial = awaitGeometry("Initial output generation did not present") { true }
        var previous = initial
        for ((requested, marker) in listOf(1 to "FULLSCREEN_TASK_WINDOW_READY",
                0 to "FULLSCREEN_RESTORED_TASK_WINDOW_READY", 2 to "MAXIMIZED_TASK_WINDOW_READY",
                0 to "MAXIMIZE_RESTORED_TASK_WINDOW_READY")) {
            if (requested == 1) check(presenter.testHoldOutput(taskId, initial[0], true))
            check(presenter.testWindowState(taskId, initial[0], requested))
            if (requested == 1) {
                awaitGeometry("New output did not commit while its frames were delayed", presented = false) {
                    it[9] == 1L && it[10] > initial[10] && it[12] > initial[12] && it[13] == initial[13]
                }
                // The previous producer has been retired. Its committed image
                // must remain valid on the compositor while the replacement is held.
                pixels("PENDING_FULLSCREEN_TASK_WINDOW_READY", taskId)
                check(presenter.testHoldOutput(taskId, initial[0], false))
            }
            val next = awaitGeometry("Window state $requested did not produce a new configuration") {
                it[9] == requested.toLong() && it[10] > previous[10] && it[12] > previous[12] &&
                    (requested != 0 || (it[7] == initial[7] && it[8] == initial[8]))
            }
            if (requested == 0) {
                check(next.sliceArray(1..8).contentEquals(initial.sliceArray(1..8))) {
                    "Restore did not return to identical Android and Wayland geometry"
                }
                // All dimensions and synthetic bounds match again. Only the
                // configuration generation can reject this pre-resize target.
                check(presenter.injectTestInput(taskId, initial[0], initial[10],
                    IntArray(8) { initial[it + 1].toInt() }, 0, intArrayOf(10, 10, 0, 0), "") == 0L) {
                    "Input from before an A-B-A resize was accepted"
                }
            }
            pixels(marker, taskId)
            previous = next
        }
        println("SCALE_TASK_WINDOW_REQUESTED")
        System.out.flush()
        check(commands.readLine() == "scaled")
        val scaled = awaitGeometry("Fractional scale did not reach the private SF output") {
            it[10] > previous[10] && it[12] > previous[12] &&
                it[7] == initial[7] && it[8] == initial[8] &&
                it[14] == (it[7] * 3 + 1) / 2 && it[15] == (it[8] * 3 + 1) / 2
        }
        check(ActivityManager.getService().configuration.densityDpi == displayBefore.densityDpi) {
            "A task's presentation scale changed the shared Android density"
        }
        pixels("SCALED_TASK_WINDOW_READY", taskId)
        println("RESTORE_TASK_SCALE_REQUESTED")
        System.out.flush()
        check(commands.readLine() == "scaled")
        val restored = awaitGeometry("Scale restore did not present its own output generation") {
            it[10] > scaled[10] && it[12] > scaled[12] && it[14] == initial[14] && it[15] == initial[15]
        }
        pixels("SCALE_RESTORED_TASK_WINDOW_READY", taskId)
        val resourcesBefore = presenter.testResources()
        check(resourcesBefore.size == 5 && resourcesBefore[0] == 1L && resourcesBefore[1] == 1L)
        val peaks = resourcesBefore.copyOf()
        check(presenter.testHoldOutput(taskId, initial[0], true))
        var latest = restored
        repeat(64) { index ->
            val state = if (index % 2 == 0) 2L else 0L
            check(presenter.testWindowState(taskId, initial[0], state.toInt()))
            val deadline = SystemClock.uptimeMillis() + 5_000
            var next: LongArray
            do {
                next = presenter.testWindow(taskId)
                if (next.size == 16 && next[10] > latest[10] && next[9] == state) break
                SystemClock.sleep(1)
            } while (SystemClock.uptimeMillis() < deadline)
            check(next.size == 16 && next[10] > latest[10] && next[9] == state) {
                "Resize storm configure $index stalled: ${next.contentToString()}"
            }
            // Wait only for the compositor acknowledgement, not the Android
            // sync or replacement frame, so newer configures can overtake both.
            check(next[13] == restored[13]) { "A held replacement frame was presented" }
            latest = next
            val resources = presenter.testResources()
            check(resources.size == 5 && resources[0] == 1L && resources[1] in 1L..2L &&
                resources[2] in 0L..2L && resources[3] <= 32L &&
                resources[4] <= resourcesBefore[4] + 64L) {
                "Resize storm resources exceeded their bounded allowance: ${resources.contentToString()}"
            }
            resources.indices.forEach { peaks[it] = maxOf(peaks[it], resources[it]) }
        }
        val finalConfiguration = latest[10]
        awaitGeometry("Resize storm did not commit its final configuration", presented = false) {
            it[10] == finalConfiguration && it[13] == restored[13] &&
                it.sliceArray(1..8).contentEquals(initial.sliceArray(1..8))
        }
        pixels("STORM_PENDING_TASK_WINDOW_READY", taskId)
        check(presenter.testHoldOutput(taskId, initial[0], false))
        awaitGeometry("Resize storm did not present its final generation") {
            it[10] == finalConfiguration && it[14] == initial[14] && it[15] == initial[15]
        }
        pixels("STORM_TASK_WINDOW_READY", taskId)
        val resourcesAfter = presenter.testResources()
        check(resourcesAfter[0] == 1L && resourcesAfter[1] == 1L && resourcesAfter[2] <= 2L &&
            resourcesAfter[3] <= resourcesBefore[3] + 4L && resourcesAfter[4] <= resourcesBefore[4] + 8L) {
            "Resize storm leaked resources: before=${resourcesBefore.contentToString()} after=${resourcesAfter.contentToString()}"
        }
        println("Stock task resize storm: 64 acknowledged configures with held frames; " +
            "resources before=${resourcesBefore.contentToString()} peak=${peaks.contentToString()} after=${resourcesAfter.contentToString()}")
        println("Stock task configuration generations: delayed replacement, fullscreen, maximize, restore, A-B-A stale input and fractional composition passed")
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
