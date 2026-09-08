package dev.artzig.window

import android.content.ClipData
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.android.server.wm.HostedNativeDrag
import dev.artzig.system.HostedClipboardMonitor
import dev.artzig.system.HostedDesktopTransfers

/** Owns native-offer staging and the Android drag attempt. The WMS adapter owns
 * recipients and grants; the native data-device adapter owns its protocol offer.
 * Both may finish asynchronously, so work is matched to an attempt identity. */
internal class WaylandNativeDrag(private val handler: Handler, private val wm: HostedNativeDrag,
    private val host: Host) {
    interface Host {
        fun point(task: TaskGeometry.Task, x: Float, y: Float): Pair<Float, Float>?
        fun accept(token: Long, accepted: Boolean)
        fun finish(token: Long, consumed: Boolean)
    }
    private class Attempt(val token: Long, val task: TaskGeometry.Task, var x: Float, var y: Float) {
        var payload = false
        var started = false
        var dropped = false
        var attemptedDrop = false
        var lease: String? = null
    }
    private val transfers = HostedDesktopTransfers.get(wm.context, handler)
    private var active: Attempt? = null

    fun entered(token: Long, task: TaskGeometry.Task, x: Float, y: Float) {
        onThread()
        require(token > 0)
        active?.let { cancel(it) }
        if (host.point(task, x, y) == null) { host.finish(token, false); return }
        active = Attempt(token, task, x, y)
    }
    fun motion(token: Long, x: Float, y: Float) {
        onThread()
        val attempt = active?.takeIf { it.token == token && !it.dropped } ?: return
        attempt.x = x; attempt.y = y
        val point = host.point(attempt.task, x, y) ?: run { cancel(attempt); return }
        if (attempt.started) wm.motion(token, attempt.task.id, point.first, point.second)
    }
    fun payload(token: Long, text: ByteArray?, html: ByteArray?, uris: ByteArray?) {
        onThread()
        val attempt = active?.takeIf { it.token == token && !it.payload } ?: return
        attempt.payload = true
        try {
            if (uris != null) {
                val list = HostedClipboardMonitor.decode(uris)
                transfers.stage(null, Process.SYSTEM_UID, list) { result ->
                    result.fold({ staged ->
                        if (active !== attempt) transfers.release(staged.token)
                        else {
                            attempt.lease = staged.token
                            start(attempt, staged.clip, staged.uid)
                        }
                    }, { failure -> if (active === attempt) fail(attempt, failure) })
                }
            } else {
                val rich = html?.let { HostedClipboardMonitor.decode(it) }
                val plain = text?.let { HostedClipboardMonitor.decode(it) }
                    ?: android.text.Html.fromHtml(requireNotNull(rich), android.text.Html.FROM_HTML_MODE_COMPACT).toString()
                val clip = if (rich == null) ClipData.newPlainText("Desktop drag", plain)
                    else ClipData.newHtmlText("Desktop drag", plain, rich)
                start(attempt, clip, Process.SYSTEM_UID)
            }
        } catch (failure: RuntimeException) { fail(attempt, failure) }
    }
    fun dropped(token: Long) {
        onThread()
        val attempt = active?.takeIf { it.token == token && !it.dropped } ?: return
        attempt.dropped = true
        if (attempt.started) deliverDrop(attempt)
    }
    fun ended(token: Long) {
        onThread()
        active?.takeIf { it.token == token }?.let(::cancel)
    }
    fun cancelCurrent() { onThread(); active?.let(::cancel) }
    fun removed(task: TaskGeometry.Task) {
        onThread()
        active?.takeIf { it.task == task }?.let(::cancel)
    }

    private fun start(attempt: Attempt, clip: ClipData, uid: Int) {
        if (active !== attempt) return
        val point = host.point(attempt.task, attempt.x, attempt.y) ?: run { cancel(attempt); return }
        try {
            attempt.started = true // Cleanup also covers a partially initialized WMS attempt.
            attempt.started = wm.begin(attempt.token, attempt.task.id, clip, uid,
                point.first, point.second, object : HostedNativeDrag.Native {
                    override fun accepted(token: Long, accepted: Boolean) {
                        if (active === attempt) host.accept(token, accepted)
                    }
                    override fun ended(token: Long, consumed: Boolean) {
                        if (active !== attempt) return
                        retire(attempt)
                        host.finish(token, consumed)
                    }
                })
            if (!attempt.started) cancel(attempt)
            else if (attempt.dropped) deliverDrop(attempt)
        } catch (failure: RuntimeException) { fail(attempt, failure) }
    }
    private fun deliverDrop(attempt: Attempt) {
        val point = host.point(attempt.task, attempt.x, attempt.y) ?: run { cancel(attempt); return }
        // Retain conservatively even if IPC fails after dispatch. App-held
        // DragAndDropPermissions can outlive the gesture. Session storage has a
        // hard shared budget; no granted URI is silently evicted to make room.
        attempt.attemptedDrop = true
        wm.drop(attempt.token, attempt.task.id, point.first, point.second)
    }
    private fun cancel(attempt: Attempt) {
        retire(attempt)
        if (attempt.started) wm.cancel(attempt.token)
        host.finish(attempt.token, false)
    }
    private fun retire(attempt: Attempt) {
        if (active !== attempt) return
        active = null
        if (!attempt.attemptedDrop) attempt.lease?.let { transfers.release(it) }
    }
    private fun fail(attempt: Attempt, failure: Throwable) {
        cancel(attempt)
        android.util.Log.w("WaylandDrag", "Native drag transfer failed", failure)
        dev.artzig.ArtzigTestDiagnostics.reportIssue("runtime_exception", "WaylandDrag", "transfer", failure.toString())
    }
    private fun onThread() { check(Looper.myLooper() === handler.looper) }
}
