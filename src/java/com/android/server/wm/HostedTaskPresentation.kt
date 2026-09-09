package com.android.server.wm

import android.app.WindowConfiguration
import android.os.IBinder
import dev.artzig.window.HostedWindowPresentation
import java.util.function.Consumer

/**
 * Follows Task.fillTaskInfo's top activity/main window selection at the end of
 * WindowSurfacePlacer's layout. TaskInfo exposes requested bar visibility but
 * omits View's legacy LAYOUT_* intent. With no SystemUI on the host, preserve
 * that intent when temporarily revealed controls leave immersive layout intact.
 * Only stock organizer callbacks carry changes to the desktop presentation seam.
 */
object HostedTaskPresentation {
    private data class State(val activity: IBinder, val immersive: Boolean)
    private val states = HashMap<IBinder, State>()

    @JvmStatic
    fun isImmersive(token: IBinder): Boolean {
        val task = WindowContainer.fromBinder(token) as? Task ?: return false
        return synchronized(task.mWmService.mGlobalLock) {
            if (!task.isAttached || !task.isOrganized) return@synchronized false
            // Session.updateRequestedVisibleTypes can report task info without
            // scheduling layout when the host has no bar surfaces to relayout.
            // Read that committed WM state when handling its organizer event.
            update(task, token)
            synchronized(states) { states[token]?.immersive ?: false }
        }
    }

    private fun update(task: Task, token: IBinder): Boolean {
        // A relaunch can temporarily have no main window. Keep its last
        // presentation until the replacement publishes actual window state.
        val activity = task.getTopNonFinishingActivity() ?: return false
        val window = activity.findMainWindow(false) ?: return false
        val previous = synchronized(states) { states[token] }
        val ui = window.mAttrs.systemUiVisibility or window.mAttrs.subtreeSystemUiVisibility
        val immersive = HostedWindowPresentation.requestsFullscreen(ui, window.mAttrs.flags,
            window.requestedVisibleTypes,
            previous?.activity === activity.token && previous.immersive)
        synchronized(states) { states[token] = State(activity.token, immersive) }
        val changed = immersive != (previous?.immersive ?: false)
        if (changed) android.util.Log.i("WaylandTaskShell",
            "task=${task.mTaskId} immersive=$immersive ui=0x${Integer.toHexString(ui)} visible=0x${Integer.toHexString(window.requestedVisibleTypes)}")
        return changed
    }

    /** Called with WMS's global lock held. Never hold the cache lock across WM calls. */
    @JvmStatic
    fun onLayout(windows: WindowManagerService) {
        val live = HashSet<IBinder>()
        val changed = ArrayList<Task>()
        windows.mRoot.forAllLeafTasks(Consumer { task ->
            if (!task.isOrganized || task.activityType != WindowConfiguration.ACTIVITY_TYPE_STANDARD) {
                return@Consumer
            }
            val token = task.mRemoteToken.toWindowContainerToken().asBinder()
            live.add(token)
            if (update(task, token)) changed.add(task)
        }, true)
        synchronized(states) { states.keys.retainAll(live) }
        // Force reporting for legacy flag changes absent from TaskInfo's equality
        // comparison. Organizer ordering and vanished-task checks remain stock.
        changed.forEach { it.dispatchTaskInfoChangedIfNeeded(true) }
    }
}
