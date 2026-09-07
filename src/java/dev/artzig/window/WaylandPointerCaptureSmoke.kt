package dev.artzig.window

import android.os.Binder
import android.os.IBinder

/** Tests the InputReader replacement's observable request/acknowledgement contract. */
object WaylandPointerCaptureSmoke {
    fun run() {
        val first = TaskGeometry.Task(1, 10)
        val second = TaskGeometry.Task(2, 20)
        val firstToken = Binder()
        val secondToken = Binder()
        val unknownToken = Binder()
        val requests = mutableListOf<Triple<TaskGeometry.Task, Boolean, Int>>()
        val modes = mutableListOf<Pair<TaskGeometry.Task, Boolean>>()
        val acknowledgements = mutableListOf<Pair<IBinder?, Int>>()
        var supported = true
        var unsupported = 0
        val capture = WaylandPointerCapture(object : WaylandPointerCapture.Host {
            override fun task(token: IBinder) = when (token) {
                firstToken -> first
                secondToken -> second
                else -> null
            }
            override fun request(task: TaskGeometry.Task, enabled: Boolean, sequence: Int): Boolean {
                requests.add(Triple(task, enabled, sequence))
                return supported
            }
            override fun mode(task: TaskGeometry.Task, enabled: Boolean) { modes.add(task to enabled) }
            override fun acknowledge(token: IBinder?, sequence: Int) { acknowledgements.add(token to sequence) }
            override fun unsupported(task: TaskGeometry.Task) { unsupported++ }
        })
        capture.request(firstToken, 1)
        check(acknowledgements.isEmpty() && modes.isEmpty())
        capture.changed(first, true, 0)
        capture.changed(second, true, 1)
        check(acknowledgements.isEmpty())
        capture.changed(first, true, 1)
        check(acknowledgements == listOf(firstToken to 1))
        capture.request(null, 2)
        capture.changed(first, true, 1) // Delayed old enable cannot satisfy release.
        check(acknowledgements.size == 1)
        capture.changed(first, false, 2)
        check(acknowledgements.last() == (null to 2) && modes.last() == (first to false))
        capture.request(firstToken, 3)
        capture.request(secondToken, 4)
        check(Triple(first, false, 3) in requests)
        capture.changed(first, true, 3)
        capture.changed(first, false, 3)
        check(acknowledgements.size == 2)
        capture.changed(second, true, 4)
        check(acknowledgements.last() == (secondToken to 4))
        capture.changed(second, false, 4) // Compositor unlock revokes persistent constraint.
        check(requests.last() == Triple(second, false, 4))
        check(acknowledgements.last() == (null to 4))
        capture.changed(second, true, 4)
        check(acknowledgements.last() == (null to 4))
        capture.request(firstToken, 5)
        capture.closed(TaskGeometry.Task(1, 9))
        capture.changed(first, true, 5)
        check(acknowledgements.last() == (firstToken to 5))
        capture.closed(first)
        check(acknowledgements.last() == (null to 5))
        capture.changed(first, true, 5)
        check(acknowledgements.last() == (null to 5))
        capture.request(unknownToken, 6)
        check(acknowledgements.last() == (null to 6))
        supported = false
        capture.request(secondToken, 7)
        check(acknowledgements.last() == (null to 7) && unsupported == 1)
        val serviceRequests = mutableListOf<Pair<IBinder, Boolean>>()
        val service = dev.artzig.system.HostedInputManagerService(captureRequest = { token, enabled ->
            serviceRequests.add(token to enabled)
        })
        service.requestPointerCapture(firstToken, true)
        service.requestPointerCapture(firstToken, false)
        check(serviceRequests == listOf(firstToken to true, firstToken to false))
        try { service.requestPointerCapture(null, true); error("Null capture token accepted") }
        catch (expected: NullPointerException) {
            check(expected.message == "inputChannelToken must not be null")
        }
        println("Wayland pointer capture: compositor acknowledgement, token/task generations, stale replies, loss and rejection passed")
    }
}
