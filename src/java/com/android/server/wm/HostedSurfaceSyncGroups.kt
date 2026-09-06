package com.android.server.wm

import android.os.IBinder
import android.window.AddToSurfaceSyncGroupResult
import android.window.ISurfaceSyncGroupCompletedListener

/** Package access to the pinned, package-private WMS controller from the hosted service. */
class HostedSurfaceSyncGroups {
    private val controller = SurfaceSyncGroupController()

    fun add(token: IBinder, merge: Boolean, listener: ISurfaceSyncGroupCompletedListener?,
        result: AddToSurfaceSyncGroupResult): Boolean =
        controller.addToSyncGroup(token, merge, listener, result)

    fun markReady(token: IBinder) = controller.markSyncGroupReady(token)
}
