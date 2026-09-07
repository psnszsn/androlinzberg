package dev.artzig.window

import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.WindowConfiguration
import android.graphics.Rect
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.SurfaceControl
import android.window.TaskOrganizer
import android.window.WindowContainerTransaction
import android.window.WindowContainerTransactionCallback
import java.util.IdentityHashMap
import java.util.concurrent.Executor

/**
 * Pinned TaskOrganizer.registerOrganizer and WindowOrganizer.applySyncTransaction
 * supply Android task authority. A private SF mirror joins each bounds sync;
 * its committed listener releases the old synthetic reservation. No display is
 * added to DisplayManager and no buffer child receives a synthetic transform.
 */
internal class StockTaskOrganizer(private val handler: Handler) :
    WaylandTaskShell.Android<IBinder, SurfaceControl> {
    interface SurfaceWindow : WaylandTaskShell.Window { val surface: Surface }
    private val executor = Executor { command -> check(handler.post(command)) }
    private var events: WaylandTaskShell.Events<IBinder, SurfaceControl>? = null
    private val tasks = mutableMapOf<IBinder, ActivityManager.RunningTaskInfo>()
    private val outputs = IdentityHashMap<SurfaceControl, TaskSurfaceOutput>()
    private var layerStack = 1
    private var registered = false
    private val organizer = object : TaskOrganizer(null, executor) {
        override fun onTaskAppeared(info: ActivityManager.RunningTaskInfo, leash: SurfaceControl) {
            val listener = events
            if (listener == null) { leash.release(); return }
            tasks[info.token.asBinder()] = info
            listener.appeared(WaylandTaskShell.Appeared(snapshot(info), leash))
        }
        override fun onTaskInfoChanged(info: ActivityManager.RunningTaskInfo) {
            val token = info.token.asBinder()
            if (tasks.containsKey(token)) tasks[token] = info
            events?.changed(snapshot(info))
        }
        override fun onTaskVanished(info: ActivityManager.RunningTaskInfo) {
            val token = info.token.asBinder()
            tasks.remove(token)
            events?.vanished(info.taskId, token)
        }
    }

    private fun checkThread() { check(Looper.myLooper() === handler.looper) }

    override fun register(events: WaylandTaskShell.Events<IBinder, SurfaceControl>):
        List<WaylandTaskShell.Appeared<IBinder, SurfaceControl>> {
        checkThread()
        check(!registered)
        this.events = events
        try {
            val initial = organizer.registerOrganizer()
            registered = true
            android.util.Log.i("WaylandTaskShell", "initial tasks=${initial.map { it.taskInfo }}")
            return initial.map { task ->
                val info = task.taskInfo
                tasks[info.token.asBinder()] = info
                WaylandTaskShell.Appeared(snapshot(info), task.leash)
            }
        } catch (failure: Throwable) { this.events = null; throw failure }
    }

    private fun snapshot(info: ActivityManager.RunningTaskInfo): WaylandTaskShell.Info<IBinder> {
        val bounds = info.configuration.windowConfiguration.bounds
        val appId = info.baseActivity?.packageName ?: info.topActivity?.packageName.orEmpty()
        val title = info.taskDescription?.label?.takeIf { it.isNotBlank() } ?: appId
        return WaylandTaskShell.Info(info.taskId, info.token.asBinder(), appId, title,
            bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1), info.isResizeable,
            info.displayId == Display.DEFAULT_DISPLAY &&
                info.activityType == WindowConfiguration.ACTIVITY_TYPE_STANDARD && appId.isNotEmpty())
    }

    override fun configure(token: IBinder, leash: SurfaceControl, window: WaylandTaskShell.Window,
        change: TaskGeometry.Proposal.Change, size: WaylandTaskShell.Size, committed: (Boolean) -> Unit) {
        checkThread()
        val info = checkNotNull(tasks[token]) { "Task vanished before bounds synchronization" }
        android.util.Log.i("WaylandTaskShell", "configure task=${info.taskId} bounds=${change.after} output=$size")
        val target = window as SurfaceWindow
        val output = outputs[leash] ?: run {
            check(layerStack < Int.MAX_VALUE) { "Private layer stack IDs exhausted" }
            TaskSurfaceOutput("Task ${change.task.id}/${change.task.generation}", leash,
                target.surface, layerStack++).also { outputs[leash] = it }
        }
        val bounds = change.after
        val oldBounds = info.configuration.windowConfiguration.bounds
        val parentX = oldBounds.left - info.positionInParent.x
        val parentY = oldBounds.top - info.positionInParent.y
        val transaction = WindowContainerTransaction()
            .setWindowingMode(info.token, WindowConfiguration.WINDOWING_MODE_FREEFORM)
            .setBounds(info.token, Rect(bounds.left, bounds.top, bounds.right.toInt(), bounds.bottom.toInt()))
        organizer.applySyncTransaction(transaction, object : WindowContainerTransactionCallback() {
            override fun onTransactionReady(id: Int, transaction: SurfaceControl.Transaction) {
                android.util.Log.i("WaylandTaskShell", "sync ready id=$id task=${info.taskId}")
                // This callback originates on a Binder thread. Always apply the
                // received sync transaction, including when its task disappeared,
                // so WM's draw synchronization cannot remain parked indefinitely.
                if (!handler.post {
                    transaction.use {
                        val live = registered && tasks[token]?.taskId == info.taskId && outputs[leash] === output
                        if (live) {
                            output.configure(it, bounds, size.width, size.height)
                            // WindowContainer.updateSurfacePositionNonOrganized deliberately
                            // leaves an organized task's leash to its organizer.
                            // Move/crop the task container, preserving all WMS
                            // window and buffer-child input transforms underneath.
                            it.setPosition(leash, (bounds.left - parentX).toFloat(),
                                (bounds.top - parentY).toFloat())
                                .setWindowCrop(leash, bounds.width, bounds.height).show(leash)
                            it.addTransactionCommittedListener(executor) { committed(true) }
                        }
                        it.apply()
                        if (!live) committed(false)
                    }
                }) {
                    transaction.use { it.apply() }
                }
            }
        })
    }

    override fun remove(token: IBinder) {
        checkThread()
        val task = tasks[token] ?: return
        check(ActivityTaskManager.getService().removeTask(task.taskId)) { "Android rejected task removal" }
    }

    override fun release(leash: SurfaceControl) {
        checkThread()
        try { outputs.remove(leash)?.close() }
        finally { leash.release() }
    }

    override fun unregister() {
        checkThread()
        events = null
        try { if (registered) organizer.unregisterOrganizer() }
        finally { registered = false; tasks.clear() }
        // WaylandTaskShell releases its owned leashes after unregistering.
    }
}
