package dev.artzig.system

import android.database.IContentObserver
import android.net.Uri
import android.os.IBinder
import android.os.RemoteException
import android.util.Log

/**
 * Session-wide ContentObserver registry, published as `"content"`.
 *
 * Source note: `com.android.server.content.ContentService.ObserverNode`
 * flattened to a list — the AOSP service is inseparable from SyncManager, and
 * observer counts here are small. Matching follows the ObserverNode contract:
 * an observer of X fires for a change to Y when X == Y, when X is a proper
 * ancestor of Y and it registered with notifyForDescendants, and always when
 * Y is an ancestor of X (a parent change implies the subtree changed).
 */
class HostedContentService : IHostedContentService.Stub() {
    private class Registration(
        val segments: List<String>,
        val notifyForDescendants: Boolean,
        val observer: IContentObserver,
    ) : IBinder.DeathRecipient {
        lateinit var owner: HostedContentService
        override fun binderDied() = owner.remove(observer.asBinder())
    }

    private val lock = Any()
    private val registrations = ArrayList<Registration>()

    private fun segmentsOf(uri: Uri): List<String> {
        val segments = ArrayList<String>()
        uri.authority?.let { segments.add(it) }
        segments.addAll(uri.pathSegments)
        return segments
    }

    override fun registerContentObserver(uri: Uri, notifyForDescendants: Boolean, observer: IContentObserver) {
        val registration = Registration(segmentsOf(uri), notifyForDescendants, observer)
        registration.owner = this
        synchronized(lock) { registrations.add(registration) }
        try {
            observer.asBinder().linkToDeath(registration, 0)
        } catch (e: RemoteException) {
            remove(observer.asBinder())
        }
    }

    override fun unregisterContentObserver(observer: IContentObserver) {
        remove(observer.asBinder())
    }

    private fun remove(binder: IBinder) {
        val removed: List<Registration>
        synchronized(lock) {
            removed = registrations.filter { it.observer.asBinder() == binder }
            registrations.removeAll(removed)
        }
        for (registration in removed) {
            try {
                binder.unlinkToDeath(registration, 0)
            } catch (ignored: java.util.NoSuchElementException) {
            }
        }
    }

    override fun notifyChange(uri: Uri, skipObserver: IContentObserver?, flags: Int) {
        val changed = segmentsOf(uri)
        val targets: List<Registration>
        synchronized(lock) {
            targets = registrations.filter { registration ->
                if (skipObserver != null &&
                    registration.observer.asBinder() == skipObserver.asBinder()
                ) {
                    return@filter false
                }
                matches(registration, changed)
            }
        }
        for (registration in targets) {
            try {
                registration.observer.onChange(false, uri, 0)
            } catch (e: RemoteException) {
                remove(registration.observer.asBinder())
            }
        }
        if (targets.isNotEmpty()) Log.d(TAG, "notified " + targets.size + " observers of " + uri)
    }

    private fun matches(registration: Registration, changed: List<String>): Boolean {
        val observed = registration.segments
        val shared = minOf(observed.size, changed.size)
        for (i in 0 until shared) {
            if (observed[i] != changed[i]) return false
        }
        return when {
            observed.size == changed.size -> true
            // Change beneath the observed uri.
            observed.size < changed.size -> registration.notifyForDescendants
            // Change at an ancestor of the observed uri.
            else -> true
        }
    }

    private companion object {
        const val TAG = "HostedContentService"
    }
}
