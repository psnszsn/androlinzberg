package com.android.server.wm

import android.os.Binder

/** In-process contract checks for the pinned WMS surface-sync controller. */
object StockSurfaceSyncSmoke {
    /** Called on a Binder thread, preserving the remote UID for the owner check. */
    fun verifyRemoteOwner(): Boolean {
        val controller = SurfaceSyncGroupController()
        val token = android.os.Binder()
        val result = android.window.AddToSurfaceSyncGroupResult()
        check(controller.addToSyncGroup(token, false, null, result))
        val owner = android.os.Binder.getCallingUid()
        val identity = android.os.Binder.clearCallingIdentity()
        val rejected = try {
            check(android.os.Binder.getCallingUid() != owner)
            runCatching { controller.markSyncGroupReady(token) }.exceptionOrNull() is IllegalArgumentException
        } finally { android.os.Binder.restoreCallingIdentity(identity) }
        controller.markSyncGroupReady(token)
        result.mTransactionReadyCallback.onTransactionReady(null)
        return rejected
    }

    fun run() {
        val events = ArrayList<String>()
        val immediate = java.util.concurrent.Executor { it.run() }
        val parent = android.window.SurfaceSyncGroup("module-parent") {
            check(it != null); events.add("parent-transaction")
        }.apply { toggleTimeout(false) }
        val child = android.window.SurfaceSyncGroup("module-child") {
            check(it == null); events.add("child-transaction")
        }.apply { toggleTimeout(false) }
        parent.addSyncCompleteCallback(immediate) { events.add("parent-complete") }
        child.addSyncCompleteCallback(immediate) { events.add("child-complete") }
        child.setAddedToSyncListener { events.add("child-added") }
        check(parent.add(child) { events.add("before-add") })
        check(events == listOf("before-add", "child-added"))
        parent.markSyncReady()
        check(events == listOf("before-add", "child-added"))
        var lateAddRan = false
        check(!parent.add(child) { lateAddRan = true } && !lateAddRan)
        check(parent.createTransactionReadyCallback(false) == null)
        child.markSyncReady()
        check(events == listOf("before-add", "child-added", "child-transaction", "child-complete",
            "parent-transaction", "parent-complete")) { events.toString() }
        parent.addSyncCompleteCallback(immediate) { events.add("late-complete") }
        parent.markSyncReady()
        check(events.last() == "late-complete" && events.size == 7)

        val service = SurfaceSyncGroupController()
        val token = Binder()
        val first = android.window.AddToSurfaceSyncGroupResult()
        val second = android.window.AddToSurfaceSyncGroupResult()
        var complete = 0
        val listener = object : android.window.ISurfaceSyncGroupCompletedListener.Stub() {
            override fun onSurfaceSyncGroupComplete() { complete++ }
        }
        check(service.addToSyncGroup(token, false, listener, first))
        check(service.addToSyncGroup(token, false, null, second))
        check(first.mParentSyncGroup.asBinder() == second.mParentSyncGroup.asBinder())
        service.markSyncGroupReady(token)
        check(complete == 0)
        first.mTransactionReadyCallback.onTransactionReady(null)
        check(complete == 0)
        second.mTransactionReadyCallback.onTransactionReady(null)
        check(complete == 1)
        check(runCatching { service.markSyncGroupReady(token) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { service.markSyncGroupReady(Binder()) }.exceptionOrNull() is IllegalArgumentException)
    }

}
