package dev.artzig.window

import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import android.view.SurfaceControl
import android.view.View
import com.android.server.wm.HostedNativeDrag
import dev.artzig.system.HostedDesktopTransfers

/** One original stock drag owns provider preparation, native source and mirrored
 * shadow. All callbacks run on the shell handler; no work runs under WM's lock. */
internal class WaylandOutgoingDrag(private val handler: Handler, private val wm: HostedNativeDrag,
    private val remote: IWaylandTaskPresenter, private val host: Host) : HostedNativeDrag.SourceListener {
    data class Origin(val task: TaskGeometry.Task, val size: WaylandTaskShell.Size,
        val bounds: TaskGeometry.Bounds, val kind: Int, val inputId: Int, val claim: Long)
    interface Host {
        fun origin(source: HostedNativeDrag.Source): Origin?
        fun current(origin: Origin): Boolean
    }
    private inner class Request(val source: HostedNativeDrag.Source, val origin: Origin) {
        var linked = false
        var token = 0L
        var surface: Surface? = null
        var output: TaskSurfaceOutput? = null
        var lease: String? = null
        var exposed = false
        var terminal = false
        var wmClosed = false
        var androidDrop = false
        val death = IBinder.DeathRecipient { handler.post { if (active === this) fail(this, null) } }
    }
    private val transfers = HostedDesktopTransfers.get(wm.context, handler)
    private var active: Request? = null
    init { wm.connectSource(this) }

    override fun started(source: HostedNativeDrag.Source) {
        onThread()
        // Unsupported input kinds and absent compositor capabilities retain the
        // stock local gesture. No serial from another device can be substituted.
        val origin = try { host.origin(source) }
        catch (failure: android.os.RemoteException) {
            wm.finishSource(source, false)
            report("claim", failure)
            return
        } ?: run { wm.local(source); return }
        check(active == null)
        val request = Request(source, origin)
        active = request
        try {
            source.client.linkToDeath(request.death, 0)
            request.linked = true
            val global = source.flags and View.DRAG_FLAG_GLOBAL != 0 &&
                source.flags and View.DRAG_FLAG_GLOBAL_SAME_APPLICATION == 0
            val data = source.data
            val uris = global && data != null && (0 until data.itemCount).any { data.getItemAt(it).uri != null }
            if (uris && source.flags and View.DRAG_FLAG_GLOBAL_URI_READ != 0) {
                transfers.stage(data, source.uid, null) { result ->
                    result.fold({ staged ->
                        if (active !== request || !wm.valid(source) || !host.current(origin)) {
                            transfers.release(staged.token, discard = true)
                            if (active === request) finishBeforeExposure(request)
                        } else {
                            request.lease = staged.token
                            start(request, null, null, staged.native)
                        }
                    }, { failure -> if (active === request) fail(request, failure) })
                }
            } else {
                val item = if (global && !uris && data?.itemCount == 1) data.getItemAt(0) else null
                start(request, item?.text?.toString()?.toByteArray(Charsets.UTF_8),
                    item?.htmlText?.toByteArray(Charsets.UTF_8), null)
            }
        } catch (failure: RuntimeException) { fail(request, failure) }
        catch (failure: android.os.RemoteException) { fail(request, failure) }
    }
    fun source(token: Long): HostedNativeDrag.Source? =
        active?.takeIf { it.token == token && !it.wmClosed && !it.terminal }?.source
    fun dropping(token: Long) { active?.takeIf { it.token == token }?.androidDrop = true }
    fun sourceEvent(token: Long, kind: Int, value: Boolean) {
        onThread()
        require(kind in 0..3)
        val request = active?.takeIf { it.token == token } ?: return
        if (kind != 3 || request.terminal) return
        request.terminal = true
        if (!request.wmClosed) wm.finishSource(request.source, value)
        else retire(request)
    }
    override fun closed(source: HostedNativeDrag.Source, consumed: Boolean) {
        onThread()
        val request = active?.takeIf { it.source === source } ?: return
        request.wmClosed = true
        if (request.token == 0L || request.terminal) retire(request)
        else if (!request.androidDrop) cancelNative(request)
        // Own Android DROP has already finished its offer. Let the compositor
        // publish dnd_finished before destroying the source or retiring its icon.
    }
    fun removed(task: TaskGeometry.Task) {
        active?.takeIf { it.origin.task == task }?.let { fail(it, null) }
    }
    fun cancelCurrent() { active?.let { fail(it, null) } }

    private fun start(request: Request, text: ByteArray?, html: ByteArray?, uris: ByteArray?) {
        if (active !== request) return
        if (!wm.valid(request.source) || !host.current(request.origin)) { finishBeforeExposure(request); return }
        val source = request.source
        val origin = request.origin
        try {
            fun project(value: Float, logical: Int, android: Int) = value.toDouble() * logical / android
            val width = kotlin.math.ceil(project(source.width.toFloat(), origin.size.width, origin.bounds.width)).toInt()
            val height = kotlin.math.ceil(project(source.height.toFloat(), origin.size.height, origin.bounds.height)).toInt()
            val x = kotlin.math.floor(project(source.hotspotX, origin.size.width, origin.bounds.width)).toInt()
            val y = kotlin.math.floor(project(source.hotspotY, origin.size.height, origin.bounds.height)).toInt()
            val pixelsX = kotlin.math.ceil(width.toDouble() * origin.size.bufferWidth / origin.size.width).toInt()
            val pixelsY = kotlin.math.ceil(height.toDouble() * origin.size.bufferHeight / origin.size.height).toInt()
            require(width in 1..16384 && height in 1..16384 && x in 0 until width && y in 0 until height &&
                pixelsX in 1..16384 && pixelsY in 1..16384)
            val result = LongArray(2)
            request.exposed = true // Binder failure can occur after paths reached the compositor.
            val surface = remote.startNativeDrag(origin.task.id, origin.task.generation, origin.size.configurationGeneration,
                origin.kind, origin.inputId, origin.claim, width, height, x, y, pixelsX, pixelsY, text, html, uris, result)
            request.token = result[0]
            request.surface = surface
            if (result[1] == 2L) {
                check(surface == null)
                // Explicit rejection before takeover permits the old local drag.
                wm.local(source)
                request.terminal = true
                retire(request)
                return
            }
            if (result[1] != 1L || surface == null || !surface.isValid || !host.current(origin) ||
                !wm.valid(source) || !wm.handoff(source)) { fail(request, null); return }
            request.output = TaskSurfaceOutput("Drag ${request.token}", source.shadow, surface, TaskSurfaceOutput.allocateLayerStack())
            SurfaceControl.Transaction().use { transaction ->
                request.output!!.configure(transaction, TaskGeometry.Bounds(0, 0, source.width, source.height), pixelsX, pixelsY)
                transaction.apply()
            }
        } catch (failure: RuntimeException) { fail(request, failure) }
        catch (failure: android.os.RemoteException) { fail(request, failure) }
    }
    private fun finishBeforeExposure(request: Request) {
        wm.local(request.source)
        request.terminal = true
        // The gesture may have naturally ended while a provider was preparing.
        // Never initiate native takeover with a stale task/grab generation.
        retire(request)
    }
    private fun fail(request: Request, failure: Throwable?) {
        if (active !== request) return
        android.util.Log.d("WaylandDrag", "Cancelling source task=${request.origin.task.id} token=${request.token} terminal=${request.terminal} wmClosed=${request.wmClosed}")
        if (request.token != 0L && !request.terminal) cancelNative(request)
        wm.finishSource(request.source, false)
        if (request.token == 0L) request.terminal = true
        if (request.wmClosed && request.terminal) retire(request)
        if (failure != null) report("source", failure)
    }
    private fun cancelNative(request: Request) {
        try { remote.cancelNativeDragSource(request.token) }
        catch (failure: android.os.RemoteException) {
            // A dead presenter cannot send the terminal callback. Complete WM
            // independently, then release the local shadow/output references.
            request.terminal = true
            report("cancel", failure)
            if (request.wmClosed) retire(request)
        }
    }
    private fun retire(request: Request) {
        if (active !== request) return
        active = null
        // Each owner must retire even if a different service died during
        // cleanup. Unbind SF before abandoning the presenter's consumer queue.
        cleanup("unlink") { if (request.linked) request.source.client.unlinkToDeath(request.death, 0) }
        cleanup("output") { request.output?.close() }
        cleanup("surface") { request.surface?.release() }
        cleanup("icon") { if (request.token != 0L) remote.retireNativeDragIcon(request.token) }
        cleanup("lease") { if (!request.exposed) request.lease?.let { transfers.release(it, discard = true) } }
    }
    private inline fun cleanup(member: String, action: () -> Unit) {
        try { action() }
        catch (failure: android.os.RemoteException) { report(member, failure) }
        catch (failure: RuntimeException) { report(member, failure) }
    }
    private fun report(member: String, failure: Throwable) {
        android.util.Log.w("WaylandDrag", "Outgoing drag $member failed", failure)
        if (failure !is android.os.DeadObjectException)
            dev.artzig.ArtzigTestDiagnostics.reportIssue("runtime_exception", "WaylandDrag", member, failure.toString())
    }
    private fun onThread() { check(Looper.myLooper() === handler.looper) }
}
