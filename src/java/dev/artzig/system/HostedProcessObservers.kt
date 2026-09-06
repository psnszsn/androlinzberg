package dev.artzig.system

import android.app.IProcessObserver
import android.os.Handler
import android.os.RemoteCallbackList
import android.os.RemoteException

/**
 * Follows ProcessList's observer registration, pending-change coalescing and
 * death cancellation. Its zygote/AMS process graph cannot run on this host;
 * the session's process and task authorities feed this registry instead.
 */
internal class HostedProcessObservers(private val handler: Handler) {
    private val callbacks = RemoteCallbackList<IProcessObserver>()
    private val lock = Any()
    private data class Change(val pid: Int, val uid: Int,
        var activities: Boolean? = null, var services: Int? = null)
    private val pending = LinkedHashMap<Int, Change>()

    fun register(observer: IProcessObserver) = synchronized(callbacks) {
        callbacks.register(observer)
        Unit
    }

    fun unregister(observer: IProcessObserver) = synchronized(callbacks) {
        callbacks.unregister(observer)
        Unit
    }

    private fun broadcast(action: (IProcessObserver) -> Unit) = synchronized(callbacks) {
        val count = callbacks.beginBroadcast()
        try {
            for (i in count - 1 downTo 0) {
                try { action(callbacks.getBroadcastItem(i)) } catch (_: RemoteException) { }
            }
        } finally { callbacks.finishBroadcast() }
    }

    fun started(pid: Int, uid: Int, packageUid: Int, packageName: String, processName: String) {
        if (!android.app.Flags.enableProcessObserverBroadcastOnProcessStarted()) return
        broadcast { it.onProcessStarted(pid, uid, packageUid, packageName, processName) }
    }

    fun activitiesChanged(pid: Int, uid: Int, foreground: Boolean) = change(pid, uid) {
        it.activities = foreground
    }

    fun servicesChanged(pid: Int, uid: Int, serviceTypes: Int) = change(pid, uid) {
        it.services = serviceTypes
    }

    private fun change(pid: Int, uid: Int, update: (Change) -> Unit) = synchronized(lock) {
        val schedule = pending.isEmpty()
        update(pending.getOrPut(pid) { Change(pid, uid) })
        if (schedule) check(handler.post { dispatchChanges() })
    }

    private fun dispatchChanges() {
        val changes = synchronized(lock) {
            pending.values.toList().also { pending.clear() }
        }
        broadcast { observer ->
            for (change in changes) {
                change.activities?.let {
                    observer.onForegroundActivitiesChanged(change.pid, change.uid, it)
                }
                change.services?.let {
                    observer.onForegroundServicesChanged(change.pid, change.uid, it)
                }
            }
        }
    }

    fun died(pid: Int, uid: Int) = synchronized(lock) {
        if (pid > 0) pending.remove(pid)
        check(handler.post { broadcast { it.onProcessDied(pid, uid) } })
    }
}
