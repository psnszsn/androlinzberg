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
internal class StockTaskOrganizer(private val handler: Handler,
    private val immersive: (IBinder) -> Boolean = com.android.server.wm.HostedTaskPresentation::isImmersive) :
    WaylandTaskShell.Android<IBinder, SurfaceControl> {
    interface SurfaceWindow : WaylandTaskShell.Window {
        val surface: Surface
        /** In-memory adapters can keep their consumer; Wayland rotates producers. */
        fun prepare(size: WaylandTaskShell.Size): Surface = surface
    }
    private val executor = Executor { command -> check(handler.post(command)) }
    private var events: WaylandTaskShell.Events<IBinder, SurfaceControl>? = null
    private val tasks = mutableMapOf<IBinder, ActivityManager.RunningTaskInfo>()
    private val outputs = IdentityHashMap<SurfaceControl, TaskSurfaceOutput>()
    private var registered = false
    private var descriptionsRegistered = false
    // Pinned Task.updateTaskDescription sends TaskStackListener immediately,
    // but queues TaskOrganizer info until layout/animation. An idle desktop
    // still needs title updates. Keep organizer geometry and replace metadata
    // only, so an older description callback cannot roll back task bounds.
    private val descriptions = object : android.app.TaskStackListener() {
        override fun onTaskDescriptionChanged(info: ActivityManager.RunningTaskInfo) {
            check(handler.post {
                val token = info.token?.asBinder()
                val current = tasks[token]
                if (registered && current != null && current.taskId == info.taskId) {
                    current.taskDescription = info.taskDescription
                    events?.changed(snapshot(current))
                }
            })
        }
    }
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
            ActivityTaskManager.getService().registerTaskStackListener(descriptions)
            descriptionsRegistered = true
            val initial = organizer.registerOrganizer()
            registered = true
            // TaskOrganizerController.removeTask migrates a previous owner's
            // leashes in WM's pending animation transaction. Drain that handover
            // before submitting our first independently synchronized transform;
            // otherwise its old position can overwrite our committed bounds.
            // The stock WMS barrier waits for animations/pending surface work
            // and the resulting input-window publication together.
            checkNotNull(android.view.WindowManagerGlobal.getWindowManagerService()).syncInputTransactions(true)
            android.util.Log.i("WaylandTaskShell", "initial tasks=${initial.map { it.taskInfo }}")
            return initial.map { task ->
                val info = task.taskInfo
                tasks[info.token.asBinder()] = info
                WaylandTaskShell.Appeared(snapshot(info), task.leash)
            }
        } catch (failure: Throwable) {
            this.events = null
            try { unregister() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun snapshot(info: ActivityManager.RunningTaskInfo): WaylandTaskShell.Info<IBinder> {
        val bounds = info.configuration.windowConfiguration.bounds
        val appId = info.baseActivity?.packageName ?: info.topActivity?.packageName.orEmpty()
        val title = info.taskDescription?.label?.takeIf { it.isNotBlank() } ?: appId
        return WaylandTaskShell.Info(info.taskId, info.token.asBinder(), appId, title,
            bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1), info.isResizeable,
            info.displayId == Display.DEFAULT_DISPLAY &&
                info.activityType == WindowConfiguration.ACTIVITY_TYPE_STANDARD && appId.isNotEmpty(),
            bounds.left, bounds.top,
            immersive(info.token.asBinder()))
    }

    override fun configure(token: IBinder, leash: SurfaceControl, window: WaylandTaskShell.Window,
        change: TaskGeometry.Proposal.Change, size: WaylandTaskShell.Size, committed: (Boolean) -> Unit) {
        checkThread()
        val info = checkNotNull(tasks[token]) { "Task vanished before bounds synchronization" }
        android.util.Log.i("WaylandTaskShell", "configure task=${info.taskId} bounds=${change.after} output=$size")
        val target = window as SurfaceWindow
        val surface = target.prepare(size)
        val output = outputs[leash] ?: run {
            TaskSurfaceOutput("Task ${change.task.id}/${change.task.generation}", leash,
                surface, TaskSurfaceOutput.allocateLayerStack()).also { outputs[leash] = it }
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
                        try {
                            if (live) {
                                output.configure(it, bounds, size.bufferWidth, size.bufferHeight, surface = surface)
                                // WindowContainer.updateSurfacePositionNonOrganized deliberately
                                // leaves an organized task's leash to its organizer.
                                // Move/crop the task container, preserving all WMS
                                // window and buffer-child input transforms underneath.
                                it.setPosition(leash, (bounds.left - parentX).toFloat(),
                                    (bounds.top - parentY).toFloat())
                                    .setWindowCrop(leash, bounds.width, bounds.height).show(leash)
                                it.addTransactionCommittedListener(executor) { committed(true) }
                            }
                        } finally { it.apply() }
                        if (!live) committed(false)
                    }
                }) {
                    transaction.use { it.apply() }
                }
            }
        })
    }

    override fun suspended(token: IBinder, suspended: Boolean) {
        checkThread()
        val task = tasks[token] ?: return
        // Pinned WindowOrganizerController.applyTaskChanges sets
        // FLAG_FORCE_HIDDEN_FOR_TASK_ORG and applies lifecycle effects.
        // Task.setForceHidden owns reordering and stock pause/stop/resume;
        // repaint suspension is the host policy input, not keyboard focus.
        organizer.applyTransaction(WindowContainerTransaction().setHidden(task.token, suspended))
    }

    override fun remove(token: IBinder) {
        checkThread()
        val task = tasks[token] ?: return
        check(ActivityTaskManager.getService().removeTask(task.taskId)) { "Android rejected task removal" }
    }

    override fun reject(token: IBinder, reason: TaskGeometry.Rejection) {
        checkThread()
        android.util.Log.w("WaylandTaskShell", "Removing unpresentable task ${tasks[token]?.taskId}: $reason")
        remove(token)
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
        finally {
            registered = false; tasks.clear()
            try { if (descriptionsRegistered) ActivityTaskManager.getService().unregisterTaskStackListener(descriptions) }
            finally { descriptionsRegistered = false }
        }
        // WaylandTaskShell releases its owned leashes after unregistering.
    }
}
