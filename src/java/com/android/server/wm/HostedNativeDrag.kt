package com.android.server.wm

import android.content.ClipData
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.DragEvent
import android.view.IWindow
import android.view.View
import com.android.server.LocalServices
import dev.artzig.window.HostInputManagerBridge

/** Native-compositor entry to pinned DragDropController/DragState. A native
 * source has no Android window or DOWN event to transfer. WMS retains its real
 * drag state, broadcasts, URI grants, recipient results and timeout; the stock
 * InputDispatcher SF hit test resolves window-local locations. Native locations
 * use IWindow like START/DROP/END so a stationary enter cannot race START on a
 * separate input FD. ViewRootImpl.WindowInputEventReceiver.onDragEvent defines
 * the location payload; its dispatchDragEvent is also the IWindow destination.
 * No compositor IPC or provider work occurs while holding the WM lock. */
class HostedNativeDrag(private val windows: WindowManagerService, private val handler: Handler,
    private val input: HostInputManagerBridge) {
    val context: android.content.Context get() = windows.mContext

    interface Native {
        fun accepted(token: Long, accepted: Boolean)
        fun ended(token: Long, consumed: Boolean)
    }
    private class Active(val token: Long, val state: DragState, val native: Native) {
        var target: IBinder? = null
        var client: IBinder? = null
        var dropped = false
        var localX = 0f
        var localY = 0f
    }
    private var active: Active? = null

    init {
        check(LocalServices.getService(HostedNativeDrag::class.java) == null)
        LocalServices.addService(HostedNativeDrag::class.java, this)
        windows.mDragDropController.registerCallback(object : WindowManagerInternal.IDragDropCallback {
            override fun dragRecipientEntered(window: IWindow) = recipient(window.asBinder(), true)
            override fun dragRecipientExited(window: IWindow) = recipient(window.asBinder(), false)
        })
    }

    fun begin(token: Long, taskId: Int, data: ClipData, sourceUid: Int, x: Float, y: Float,
        native: Native): Boolean {
        onThread()
        require(token > 0 && sourceUid >= 0 && x.isFinite() && y.isFinite())
        if (active != null) return false
        synchronized(windows.mGlobalLock) {
            val controller = windows.mDragDropController
            if (controller.hostedDragStateLocked() != null) return false
            if (HostedTaskInputTargets.taskToken(windows, taskId) == null) return false
            val display = windows.mRoot.getDisplayContent(Display.DEFAULT_DISPLAY) ?: return false
            val state = DragState(windows, controller, Binder(), null,
                View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ, Binder()).apply {
                mHostedNativePresentation = true
                mData = ClipData(data)
                mUid = sourceUid
                mPid = -1 // The native source is not an Android application process.
                mCallingTaskIdToHide = -1
                mCurrentDisplayContent = display
                mStartDragDisplayContent = display
            }
            check(controller.adoptHostedDragLocked(state))
            active = Active(token, state, native)
            state.broadcastDragStartedLocked(x, y)
        }
        motion(token, taskId, x, y)
        return true
    }

    fun motion(token: Long, taskId: Int, x: Float, y: Float) {
        onThread()
        val drag = active?.takeIf { it.token == token && !it.dropped } ?: return
        val task = HostedTaskInputTargets.taskToken(windows, taskId) ?: run { cancel(token); return }
        // SF's input transforms, regions, visibility, z-order and task Binder
        // identity determine the recipient. Synthetic Android coordinates are
        // never passed to the native compositor as desktop positions.
        val target = input.hostedDragTarget(task, Display.DEFAULT_DISPLAY, x, y)
        synchronized(windows.mGlobalLock) {
            if (windows.mDragDropController.hostedDragStateLocked() !== drag.state) return
            val old = drag.target?.let { windows.mInputToWindowMap[it] }
            val window = target?.token?.let { windows.mInputToWindowMap[it] }
                ?.takeIf { drag.state.isWindowNotified(it) }
            if (old != null && old !== window) dispatchLocation(old, DragEvent.ACTION_DRAG_EXITED, drag.localX, drag.localY)
            drag.state.mCurrentDisplayX = x
            drag.state.mCurrentDisplayY = y
            drag.target = if (window != null) target?.token else null
            drag.client = window?.mClient?.asBinder()
            if (window != null && target != null) {
                drag.localX = target.x; drag.localY = target.y
                dispatchLocation(window, DragEvent.ACTION_DRAG_LOCATION, target.x, target.y)
            }
        }
        if (drag.target == null) drag.native.accepted(token, false)
    }

    private fun dispatchLocation(window: WindowState, action: Int, x: Float, y: Float) {
        val event = DragEvent.obtain(action, x, y, 0f, 0f, Display.DEFAULT_DISPLAY, 0,
            null, null, null, null, null, false)
        try { window.mClient.dispatchDragEvent(event) }
        catch (_: android.os.RemoteException) { /* Stock WMS ends a dead recipient at DROP/timeout. */ }
        finally { if (window.mSession.mPid != android.os.Process.myPid()) event.recycle() }
    }

    /** Returns whether a stock recipient was sent DROP. A true result is not
     * consumption: the actual app reportDropResult decides native completion.
     * URI staging must retain any lease once DROP was sent, because the app can
     * hold its stock DragAndDropPermissions beyond the end of this gesture. */
    fun drop(token: Long, taskId: Int, x: Float, y: Float): Boolean {
        onThread()
        val drag = active?.takeIf { it.token == token && !it.dropped } ?: return false
        val task = HostedTaskInputTargets.taskToken(windows, taskId) ?: run { cancel(token); return false }
        val target = input.hostedDragTarget(task, Display.DEFAULT_DISPLAY, x, y)
        synchronized(windows.mGlobalLock) {
            if (windows.mDragDropController.hostedDragStateLocked() !== drag.state) return false
            drag.dropped = true
            drag.state.mCurrentDisplayX = x
            drag.state.mCurrentDisplayY = y
            return drag.state.reportDropWindowLock(target?.token, target?.x ?: 0f, target?.y ?: 0f)
        }
    }

    fun cancel(token: Long) {
        onThread()
        val drag = active?.takeIf { it.token == token } ?: return
        synchronized(windows.mGlobalLock) {
            if (windows.mDragDropController.hostedDragStateLocked() === drag.state)
                drag.state.cancelDragLocked(true)
        }
        active = null
        drag.native.ended(token, false)
    }

    private fun recipient(client: IBinder, accepted: Boolean) {
        check(handler.post {
            val drag = active ?: return@post
            if (!drag.dropped && drag.client == client) drag.native.accepted(drag.token, accepted)
        })
    }
    private fun closedOnThread(state: DragState, consumed: Boolean) {
        check(handler.post {
            val drag = active?.takeIf { it.state === state } ?: return@post
            active = null
            drag.native.ended(drag.token, consumed)
        })
    }
    private fun onThread() { check(Looper.myLooper() === handler.looper) }

    companion object {
        /** Called by stock closeLocked before clearing fields; only posts work. */
        @JvmStatic fun closed(value: Any, consumed: Boolean) {
            // DragState is package-private Java and cannot appear in a public Kotlin signature.
            val state = value as DragState
            LocalServices.getService(HostedNativeDrag::class.java)?.closedOnThread(state, consumed)
        }
    }
}
