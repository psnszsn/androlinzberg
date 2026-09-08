package dev.artzig.window

/** Delayed Android commits and compositor callbacks, with independent ownership checks. */
object WaylandTaskShellSmoke {
    private data class Token(val value: Int)
    private class Leash { var releases = 0 }
    private class Desktop : WaylandTaskShell.Presenter {
        val windows = mutableListOf<Window>()
        override fun open(task: TaskGeometry.Task, appId: String, title: String,
            size: WaylandTaskShell.Size, events: WaylandTaskShell.WindowEvents): Window =
            Window(task, size, events).also { windows.add(it) }
    }
    private class Window(val task: TaskGeometry.Task, override val size: WaylandTaskShell.Size,
        val events: WaylandTaskShell.WindowEvents) : WaylandTaskShell.Window {
        var closes = 0
        val commits = mutableListOf<Pair<TaskGeometry.Bounds, WaylandTaskShell.Size>>()
        val rejections = mutableListOf<TaskGeometry.Rejection>()
        var title = ""
        val immersiveRequests = mutableListOf<Boolean>()
        override fun metadata(appId: String, title: String) { this.title = title }
        override fun immersive(enabled: Boolean) { immersiveRequests.add(enabled) }
        override fun committed(bounds: TaskGeometry.Bounds, size: WaylandTaskShell.Size) {
            check(closes == 0); commits.add(bounds to size)
        }
        override fun rejected(reason: TaskGeometry.Rejection) { rejections.add(reason) }
        override fun close() { check(++closes == 1) }
    }
    private class Android : WaylandTaskShell.Android<Token, Leash> {
        lateinit var events: WaylandTaskShell.Events<Token, Leash>
        var initial = emptyList<WaylandTaskShell.Appeared<Token, Leash>>()
        val configurations = mutableListOf<Pending>()
        val removes = mutableListOf<Token>()
        val suspensions = mutableListOf<Pair<Token, Boolean>>()
        val rejections = mutableListOf<Pair<Token, TaskGeometry.Rejection>>()
        var unregisters = 0
        class Pending(val change: TaskGeometry.Proposal.Change, val size: WaylandTaskShell.Size,
            val done: (Boolean) -> Unit)
        override fun register(events: WaylandTaskShell.Events<Token, Leash>): List<WaylandTaskShell.Appeared<Token, Leash>> {
            this.events = events; return initial
        }
        override fun unregister() { unregisters++ }
        override fun release(leash: Leash) { check(++leash.releases == 1) }
        override fun configure(token: Token, leash: Leash, window: WaylandTaskShell.Window,
            change: TaskGeometry.Proposal.Change, size: WaylandTaskShell.Size, committed: (Boolean) -> Unit) {
            configurations.add(Pending(change, size, committed))
        }
        override fun remove(token: Token) { removes.add(token) }
        override fun suspended(token: Token, suspended: Boolean) { suspensions.add(token to suspended) }
        override fun reject(token: Token, reason: TaskGeometry.Rejection) {
            rejections.add(token to reason); remove(token)
        }
    }
    private fun task(id: Int, token: Int = id, application: Boolean = true, resizable: Boolean = true) =
        WaylandTaskShell.Appeared(WaylandTaskShell.Info(id, Token(token), "test.app", "task $id",
            100, 80, resizable, application), Leash())

    @JvmStatic fun main(args: Array<String>) {
        val android = Android()
        val desktop = Desktop()
        val geometry = TaskGeometry(1000, 1000, 2)
        val first = task(1)
        val second = task(2)
        val home = task(3, application = false)
        android.initial = listOf(first, second, home)
        val shell = WaylandTaskShell(android, desktop, geometry)
        shell.start()
        check(desktop.windows.size == 2 && home.leash.releases == 1)
        check(android.configurations.size == 2)
        val one = desktop.windows[0]
        val two = desktop.windows[1]
        one.events.suspended(true)
        one.events.suspended(true)
        one.events.suspended(false)
        check(android.suspensions == listOf(first.info.token to true, first.info.token to false))
        val firstBounds = android.configurations[0].change.after
        val secondBounds = android.configurations[1].change.after
        check(firstBounds.right < secondBounds.left || secondBounds.right < firstBounds.left ||
            firstBounds.bottom < secondBounds.top || secondBounds.bottom < firstBounds.top)
        val duplicate = task(1)
        android.events.appeared(duplicate)
        check(duplicate.leash.releases == 1 && first.leash.releases == 0 && desktop.windows.size == 2)
        android.events.changed(first.info.copy(title = "updated"))
        check(one.title == "updated")
        android.events.changed(first.info.copy(immersive = true))
        android.events.changed(first.info.copy(immersive = true, title = "immersive title"))
        check(one.immersiveRequests == listOf(true) && two.immersiveRequests.isEmpty())
        android.events.changed(first.info.copy(immersive = false))
        check(one.immersiveRequests == listOf(true, false))
        one.events.configure(WaylandTaskShell.Size(200, 120))
        one.events.configure(WaylandTaskShell.Size(240, 180))
        check(android.configurations.size == 2)
        android.configurations[0].done(true)
        check(one.commits.size == 1 && android.configurations.size == 3)
        check(android.configurations[2].size == WaylandTaskShell.Size(240, 180))
        android.configurations[0].done(true) // Repeated/stale acknowledgement cannot commit twice.
        check(one.commits.size == 1)
        android.configurations[1].done(true)
        android.configurations[2].done(true)
        check(geometry.bounds(one.task)?.width == 240)
        val crowded = task(4)
        android.events.appeared(crowded)
        check(android.rejections == listOf(crowded.info.token to TaskGeometry.Rejection.CAPACITY))
        check(desktop.windows.size == 2 && crowded.leash.releases == 1)

        one.events.closeRequested()
        one.events.closeRequested()
        check(android.removes == listOf(crowded.info.token, first.info.token) && one.closes == 0)
        android.events.vanished(1, Token(999))
        check(one.closes == 0)
        android.events.vanished(1, first.info.token)
        check(one.closes == 1 && first.leash.releases == 1 && two.closes == 0)
        one.events.configure(WaylandTaskShell.Size(400, 400))
        one.events.closeRequested()
        one.events.suspended(true)
        check(android.suspensions.size == 2)
        check(android.removes.size == 2)

        val replacement = task(1, token = 11, resizable = false)
        android.events.appeared(replacement)
        val replacementWindow = desktop.windows.last()
        check(replacementWindow.task.generation > one.task.generation)
        replacementWindow.events.configure(WaylandTaskShell.Size(300, 240))
        android.configurations[3].done(true)
        check(android.configurations[4].change.after.width == 100)
        check(android.configurations[4].size.width == 300)
        android.events.vanished(1, first.info.token)
        check(replacementWindow.closes == 0)
        shell.close()
        shell.close()
        check(android.unregisters == 1 && replacementWindow.closes == 1 && two.closes == 1)
        check(replacement.leash.releases == 1 && second.leash.releases == 1)
        android.configurations[4].done(true) // No commit may revive a closed task.
        check(replacementWindow.commits.size == 1)
        val late = task(5)
        android.events.appeared(late)
        check(late.leash.releases == 1 && desktop.windows.size == 3)

        val failingAndroid = Android()
        val failingDesktop = Desktop()
        val failedTask = task(8)
        failingAndroid.initial = listOf(failedTask)
        val failing = WaylandTaskShell(failingAndroid, failingDesktop)
        failing.start()
        failingAndroid.configurations.single().done(false)
        check(failingDesktop.windows.single().closes == 1 && failedTask.leash.releases == 1)
        failing.close()

        val generations = Android()
        val generationDesktop = Desktop()
        val old = task(9)
        generations.initial = listOf(old)
        val generationShell = WaylandTaskShell(generations, generationDesktop)
        generationShell.start()
        val new = task(9, token = 19)
        generations.events.appeared(new) // Replacement before old configure completes.
        check(old.leash.releases == 1 && generationDesktop.windows.first().closes == 1)
        generations.configurations[0].done(true)
        check(generationDesktop.windows.last().commits.isEmpty())
        generations.configurations[1].done(true)
        val active = generationDesktop.windows.last()
        active.events.configure(WaylandTaskShell.Size(180, 160))
        generations.configurations[2].done(false)
        check(active.closes == 0 && active.commits.size == 1)
        active.events.configure(WaylandTaskShell.Size(190, 170))
        generations.configurations[3].done(true)
        check(active.commits.last().first.width == 190)
        generationShell.close()

        val startup = Android()
        val unvisited = task(12)
        val bad = task(11)
        startup.initial = listOf(bad, unvisited)
        val failureShell = WaylandTaskShell(startup, object : WaylandTaskShell.Presenter {
            override fun open(task: TaskGeometry.Task, appId: String, title: String,
                size: WaylandTaskShell.Size, events: WaylandTaskShell.WindowEvents): WaylandTaskShell.Window =
                error("Compositor unavailable")
        })
        check(runCatching { failureShell.start() }.isFailure)
        check(bad.leash.releases == 1 && unvisited.leash.releases == 1 && startup.unregisters == 1)
        val reusedAndroid = Android()
        val reusedDesktop = Desktop()
        val reusedTask = task(20)
        reusedAndroid.initial = listOf(reusedTask)
        val reused = WaylandTaskShell(reusedAndroid, reusedDesktop)
        reused.start()
        reusedAndroid.configurations.single().done(true)
        reusedAndroid.events.changed(reusedTask.info.copy(width = 410, height = 730, left = 800, top = 600))
        check(reusedDesktop.windows.size == 1 && reusedAndroid.configurations.size == 2)
        val repaired = reusedAndroid.configurations.last()
        check(repaired.change.after == TaskGeometry.Bounds(0, 0, 100, 80) &&
            repaired.size == WaylandTaskShell.Size(100, 80))
        repaired.done(true)
        reusedAndroid.events.changed(reusedTask.info)
        check(reusedAndroid.configurations.size == 2)
        val reusedWindow = reusedDesktop.windows.single()
        reusedWindow.events.configure(WaylandTaskShell.Size(110, 90))
        reusedAndroid.configurations.last().done(false)
        reusedAndroid.events.changed(reusedTask.info.copy(left = 500))
        check(reusedAndroid.configurations.last().size == WaylandTaskShell.Size(100, 80)) {
            "Failed configure replaced the accepted presentation size"
        }
        reusedAndroid.configurations.last().done(true)
        reused.close()
        val rejectedAndroid = Android()
        val rejectedDesktop = Desktop()
        val boundedTask = task(22)
        rejectedAndroid.initial = listOf(boundedTask)
        val bounded = WaylandTaskShell(rejectedAndroid, rejectedDesktop, TaskGeometry(200, 200))
        bounded.start()
        rejectedAndroid.configurations.single().done(true)
        rejectedDesktop.windows.single().events.configure(WaylandTaskShell.Size(300, 300))
        check(rejectedAndroid.configurations.size == 1)
        rejectedAndroid.events.changed(boundedTask.info.copy(left = 20))
        check(rejectedAndroid.configurations.size == 2 &&
            rejectedAndroid.configurations.last().size == WaylandTaskShell.Size(100, 80)) {
            "Rejected configure poisoned later Android bounds reconciliation"
        }
        rejectedAndroid.configurations.last().done(true)
        bounded.close()
        val tinyAndroid = Android()
        val tinyDesktop = Desktop()
        val oversized = task(21)
        tinyAndroid.initial = listOf(oversized)
        val tiny = WaylandTaskShell(tinyAndroid, tinyDesktop, TaskGeometry(50, 50))
        tiny.start()
        check(tinyAndroid.rejections == listOf(oversized.info.token to TaskGeometry.Rejection.TOO_LARGE))
        check(tinyDesktop.windows.single().closes == 0 && oversized.leash.releases == 0)
        tinyAndroid.events.vanished(21, oversized.info.token)
        check(tinyDesktop.windows.single().closes == 1 && oversized.leash.releases == 1)
        tiny.close()
        val scaleAndroid = Android()
        val scaleDesktop = Desktop()
        scaleAndroid.initial = listOf(task(31))
        val scaleShell = WaylandTaskShell(scaleAndroid, scaleDesktop, TaskGeometry(300, 300))
        scaleShell.start()
        scaleAndroid.configurations.single().done(true)
        val scaleWindow = scaleDesktop.windows.single()
        val originalBounds = scaleWindow.commits.single().first
        scaleWindow.events.configure(WaylandTaskShell.Size(100, 80, 150, 120, 1, 10))
        scaleWindow.events.configure(WaylandTaskShell.Size(100, 80, 200, 160, 2, 11))
        scaleWindow.events.configure(WaylandTaskShell.Size(100, 80, 100, 80, 3, 12))
        check(scaleAndroid.configurations.size == 2)
        scaleAndroid.configurations[1].done(true)
        check(scaleAndroid.configurations.size == 3)
        val coalesced = scaleAndroid.configurations.last()
        check(coalesced.size.configurationGeneration == 3L && coalesced.size.bufferWidth == 100 &&
            coalesced.change.after == originalBounds)
        coalesced.done(true)
        check(scaleWindow.commits.last().second.configurationGeneration == 3L)
        check(scaleWindow.commits.all { it.first == originalBounds })
        scaleShell.close()
        println("WaylandTaskShell: initial tasks, deduplication, generations, resize coalescing, Android close and cleanup passed")
    }
}
