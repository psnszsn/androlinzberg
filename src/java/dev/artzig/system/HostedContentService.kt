package dev.artzig.system

import android.database.IContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log

/**
 * The session's `android.content.IContentService` (`"content"`): the
 * ContentObserver registry and the per-package cache the real ContentResolver
 * uses. Source note: `com.android.server.content.ContentService` — its
 * ObserverNode tree flattened to a list (observer counts here are small) and
 * the SyncManager surface left unsupported (no sync adapters, no accounts on
 * this host). Matching follows the ObserverNode contract: an observer of X
 * fires for a change to Y when X == Y, when X is a proper ancestor of Y and it
 * registered with notifyForDescendants, and always when Y is an ancestor of X.
 */
class HostedContentService : android.content.IContentService.Stub(), android.content.IContentService by unsupported<android.content.IContentService>() {
    override fun asBinder(): android.os.IBinder = this

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
    private val caches = HashMap<String, HashMap<Uri, Bundle>>()

    private fun segmentsOf(uri: Uri): List<String> {
        val segments = ArrayList<String>()
        uri.authority?.let { segments.add(it) }
        segments.addAll(uri.pathSegments)
        return segments
    }

    override fun registerContentObserver(
        uri: Uri?,
        notifyForDescendants: Boolean,
        observer: IContentObserver?,
        userHandle: Int,
        targetSdkVersion: Int,
    ) {
        val registration = Registration(segmentsOf(checkNotNull(uri)), notifyForDescendants, checkNotNull(observer))
        registration.owner = this
        synchronized(lock) { registrations.add(registration) }
        try {
            observer.asBinder().linkToDeath(registration, 0)
        } catch (e: RemoteException) {
            remove(observer.asBinder())
        }
    }

    override fun unregisterContentObserver(observer: IContentObserver?) {
        remove(checkNotNull(observer).asBinder())
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

    override fun notifyChange(
        uris: Array<Uri?>?,
        observer: IContentObserver?,
        observerWantsSelfNotifications: Boolean,
        flags: Int,
        userHandle: Int,
        targetSdkVersion: Int,
        callingPackage: String?,
    ) {
        for (uri in checkNotNull(uris)) notifyOne(checkNotNull(uri), observer, observerWantsSelfNotifications, flags)
    }

    private fun notifyOne(uri: Uri, observer: IContentObserver?, selfChange: Boolean, flags: Int) {
        val changed = segmentsOf(uri)
        val targets: List<Registration>
        synchronized(lock) {
            targets = registrations.filter { registration ->
                if (observer != null && !selfChange &&
                    registration.observer.asBinder() == observer.asBinder()
                ) {
                    return@filter false
                }
                matches(registration, changed)
            }
        }
        for (registration in targets) {
            val self = observer != null && registration.observer.asBinder() == observer.asBinder()
            try {
                registration.observer.onChange(self, uri, 0)
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

    /** ContentService.putCache/getCache: a per-package in-memory cache (AOSP clears it on package change). */
    override fun putCache(packageName: String?, key: Uri?, value: Bundle?, userId: Int) {
        synchronized(lock) {
            val cache = caches.getOrPut(checkNotNull(packageName)) { HashMap() }
            if (value == null) cache.remove(checkNotNull(key)) else cache[checkNotNull(key)] = value
        }
    }

    override fun getCache(packageName: String?, key: Uri?, userId: Int): Bundle? =
        synchronized(lock) { caches[checkNotNull(packageName)]?.get(checkNotNull(key)) }

    override fun onDbCorruption(tag: String?, message: String?, stacktrace: String?) {
        Log.e(TAG, "database corruption (" + tag + "): " + message + "\n" + stacktrace)
    }

    override fun getMasterSyncAutomatically(): Boolean = false
    override fun getMasterSyncAutomaticallyAsUser(userId: Int): Boolean = false
    override fun getSyncAdapterTypes(): Array<android.content.SyncAdapterType?>? = arrayOfNulls(0)
    override fun getSyncAdapterTypesAsUser(userId: Int): Array<android.content.SyncAdapterType?>? = arrayOfNulls(0)
    override fun getCurrentSyncs(): MutableList<android.content.SyncInfo?>? = ArrayList()
    override fun getCurrentSyncsAsUser(userId: Int): MutableList<android.content.SyncInfo?>? = ArrayList()

    private companion object {
        const val TAG = "HostedContentService"
    }
}
