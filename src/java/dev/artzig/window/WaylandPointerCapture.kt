package dev.artzig.window

import android.os.IBinder

/** Pinned InputReader's PointerCaptureRequest handshake over a compositor seat.
 * InputDispatcher authorizes the token and sequence. The host resolves its task
 * and acknowledges only actual capture; stale callbacks cannot capture a new root.
 * Calls are serialized with task/input callbacks, outside WMS and dispatcher locks.
 */
internal class WaylandPointerCapture(private val host: Host) {
    interface Host {
        fun task(token: IBinder): TaskGeometry.Task?
        fun request(task: TaskGeometry.Task, enabled: Boolean, sequence: Int): Boolean
        fun mode(task: TaskGeometry.Task, enabled: Boolean)
        fun acknowledge(token: IBinder?, sequence: Int)
        fun unsupported(task: TaskGeometry.Task)
    }
    private data class Request(val task: TaskGeometry.Task, val token: IBinder?, val sequence: Int)
    private var current: Request? = null

    fun request(token: IBinder?, sequence: Int) {
        val old = current
        val task = if (token != null) host.task(token) else old?.task
        if (old != null && old.task != task) {
            host.mode(old.task, false)
            host.request(old.task, false, old.sequence)
        }
        if (task == null) {
            current = null
            host.acknowledge(null, sequence)
            return
        }
        current = Request(task, token, sequence)
        if (!host.request(task, token != null, sequence)) {
            host.mode(task, false)
            current = null
            host.acknowledge(null, sequence)
            if (token != null) host.unsupported(task)
        }
    }

    fun changed(task: TaskGeometry.Task, enabled: Boolean, sequence: Int) {
        val request = current ?: return
        if (request.task != task || request.sequence != sequence) return
        if (enabled && request.token == null) return
        host.mode(task, enabled)
        host.acknowledge(if (enabled) request.token else null, sequence)
        if (!enabled) {
            // A persistent Wayland constraint must not reactivate after a real
            // compositor unlock has revoked Android's capture request.
            host.request(task, false, sequence)
            current = null
        }
    }

    fun closed(task: TaskGeometry.Task) {
        val request = current ?: return
        if (request.task != task) return
        current = null
        host.mode(task, false)
        host.acknowledge(null, request.sequence)
    }
}
