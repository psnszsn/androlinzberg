package dev.artzig.system

/** Deterministic process-boundary tests: callbacks deliberately outlive their
 * start generation, just as an already posted timeout or Binder death can. */
object HostedProcessStartsSmoke {
    private class Timer(val action: () -> Unit) : AutoCloseable {
        var canceled = false
        override fun close() { canceled = true }
    }
    private class Child(val request: HostedProcessStarts.Request, val exit: () -> Unit) : HostedProcessStarts.Child {
        var terminated = false
        override fun terminate() { terminated = true }
    }

    @JvmStatic fun main(args: Array<String>) {
        val timers = ArrayList<Timer>()
        val children = ArrayList<Child>()
        val completions = ArrayList<Pair<HostedProcessStarts.Request, HostedProcessStarts.Failure?>>()
        val starts = HostedProcessStarts(
            { delay, action -> check(delay == 10_000L); Timer(action).also(timers::add) },
            { request, failure -> completions.add(request to failure) },
        )
        val launcher = HostedProcessStarts.Launcher { request, exit -> Child(request, exit).also(children::add) }
        val a = HostedProcessStarts.Key("example", "example:a")
        val b = HostedProcessStarts.Key("example", "example:b")
        check(starts.attach(a, 0))
        check(!starts.attach(a, 1))
        check(starts.start(a, "/a.apk", launcher))
        val first = children.single()
        check(first.request.key == a && first.request.apkPath == "/a.apk")
        check(starts.start(a, "/a.apk", launcher) && children.size == 1)
        check(!starts.attach(a, 0) && !starts.attach(a, first.request.sequence + 1))
        check(starts.attach(a, first.request.sequence))
        check(!starts.attach(a, first.request.sequence))
        // A hung Application.onCreate still has a startup deadline after bind.
        timers.last().action()
        check(first.terminated && completions.single().second == HostedProcessStarts.Failure.ATTACH_TIMEOUT)
        check(!starts.attach(a, first.request.sequence))
        check(starts.start(a, "/a.apk", launcher))
        val replacement = children.last()
        check(replacement.request.sequence > first.request.sequence)
        first.exit()
        timers.first().action()
        starts.died(a, first.request.sequence)
        check(!replacement.terminated && completions.size == 1)
        check(starts.attach(a, replacement.request.sequence))
        starts.ready(a, replacement.request.sequence)
        check(timers.last().canceled)
        timers.last().action()
        check(!replacement.terminated)
        check(starts.start(b, "/a.apk", launcher))
        val sibling = children.last()
        sibling.exit()
        check(completions.last().second == HostedProcessStarts.Failure.EXIT_BEFORE_READY)
        check(!replacement.terminated)
        replacement.exit()
        check(completions.last() == replacement.request to null)
        check(starts.start(a, "/a.apk", launcher))
        val live = children.last()
        starts.died(a, live.request.sequence)
        check(live.terminated && timers.last().canceled)
        val count = completions.size
        live.exit()
        check(completions.size == count)
        check(!starts.start(a, "/a.apk", HostedProcessStarts.Launcher { _, _ -> null }))
        check(completions.last().second == HostedProcessStarts.Failure.SPAWN)
        try {
            starts.start(a, "/a.apk", HostedProcessStarts.Launcher { _, _ -> throw java.io.IOException("exec") })
            error("exec exception was swallowed")
        } catch (_: java.io.IOException) { }
        check(completions.last().second == HostedProcessStarts.Failure.SPAWN)
        var immediate: Child? = null
        check(!starts.start(a, "/a.apk", HostedProcessStarts.Launcher { request, exit ->
            exit()
            Child(request, exit).also { immediate = it }
        }))
        check(checkNotNull(immediate).terminated)
        check(starts.start(a, "/a.apk", launcher)) // Every failure permits a fresh attempt.
        println("HostedProcessStarts: generation, attach/readiness deadlines, exit, retry and sibling isolation passed")
    }
}
