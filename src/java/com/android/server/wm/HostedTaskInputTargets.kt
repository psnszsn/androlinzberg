package com.android.server.wm

import android.os.IBinder

/** Follows WMS.onInputFocusChanged's input-token lookup under mGlobalLock,
 * including EmbeddedWindow's host WindowState. InputReader cannot resolve a
 * Wayland surface, so the desktop seat needs the enclosing Android task ID.
 * The caller must release this lock before communicating with the presenter.
 */
object HostedTaskInputTargets {
    fun windowTask(windows: WindowManagerService, token: IBinder, uid: Int, pid: Int): Int? =
        synchronized(windows.mGlobalLock) {
            val target = windows.getInputTargetFromWindowTokenLocked(token)
                ?: return@synchronized null
            if (target.uid != uid || target.pid != pid) return@synchronized null
            target.windowState?.task?.mTaskId
        }

    fun taskId(windows: WindowManagerService, token: IBinder): Int? =
        synchronized(windows.mGlobalLock) {
            windows.getInputTargetFromToken(token)?.windowState?.task?.mTaskId
        }
}
