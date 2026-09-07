package com.android.server.wm

import android.os.IBinder

/** Follows WMS.onInputFocusChanged's input-token lookup under mGlobalLock,
 * including EmbeddedWindow's host WindowState. InputReader cannot resolve a
 * Wayland surface, so the desktop seat needs the enclosing Android task ID.
 * The caller must release this lock before communicating with the presenter.
 */
object HostedTaskInputTargets {
    fun taskId(windows: WindowManagerService, token: IBinder): Int? =
        synchronized(windows.mGlobalLock) {
            windows.getInputTargetFromToken(token)?.windowState?.task?.mTaskId
        }
}
