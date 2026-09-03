package dev.artzig.system

import android.content.pm.ParceledListSlice
import android.net.Uri
import android.os.Binder

/**
 * The session's `android.app.IUriGrantsManager` (`"uri_grants"`). Source
 * note: `com.android.server.uri.UriGrantsManagerService` — persisted grants
 * are an in-memory per-package table (AOSP persists them to XML across
 * reboots; a session is one boot), and no grant is denied: hosted providers
 * enforce access through the package manager's permissions, so the
 * checkGrantUriPermission path reports "no grant needed" (-1) like AOSP does
 * for a target that already holds the provider permission.
 */
class HostedUriGrantsService : android.app.IUriGrantsManager.Stub(), android.app.IUriGrantsManager by unsupported<android.app.IUriGrantsManager>() {
    override fun asBinder(): android.os.IBinder = this

    private class Grant(val uri: Uri, var modeFlags: Int, val time: Long)

    private val lock = Any()
    private val persisted = HashMap<String, ArrayList<Grant>>()

    override fun takePersistableUriPermission(uri: Uri?, modeFlags: Int, toPackage: String?, userId: Int) {
        val pkg = toPackage ?: callingPackage()
        synchronized(lock) {
            val grants = persisted.getOrPut(pkg) { ArrayList() }
            val existing = grants.firstOrNull { it.uri == uri }
            if (existing != null) existing.modeFlags = existing.modeFlags or modeFlags
            else grants.add(Grant(checkNotNull(uri), modeFlags, System.currentTimeMillis()))
        }
    }

    override fun releasePersistableUriPermission(uri: Uri?, modeFlags: Int, toPackage: String?, userId: Int) {
        val pkg = toPackage ?: callingPackage()
        synchronized(lock) {
            val grants = persisted[pkg] ?: return
            val it = grants.iterator()
            while (it.hasNext()) {
                val grant = it.next()
                if (grant.uri != uri) continue
                grant.modeFlags = grant.modeFlags and modeFlags.inv()
                if (grant.modeFlags == 0) it.remove()
            }
        }
    }

    override fun getUriPermissions(packageName: String?, incoming: Boolean, persistedOnly: Boolean): ParceledListSlice<*>? {
        val pkg = packageName ?: callingPackage()
        val list = synchronized(lock) {
            (persisted[pkg] ?: emptyList<Grant>()).map { android.content.UriPermission(it.uri, it.modeFlags, it.time) }
        }
        return ParceledListSlice(list)
    }

    /** Grants held by a package as a URI owner: none are tracked (nothing is denied). */
    override fun getGrantedUriPermissions(packageName: String?, userId: Int): ParceledListSlice<*>? =
        ParceledListSlice(ArrayList<android.app.GrantedUriPermission>())

    override fun clearGrantedUriPermissions(packageName: String?, userId: Int) {
        synchronized(lock) { persisted.remove(packageName) }
    }

    override fun checkGrantUriPermission_ignoreNonSystem(sourceUid: Int, targetPkg: String?, uri: Uri?, modeFlags: Int, userId: Int): Int = -1

    override fun grantUriPermissionFromOwner(owner: android.os.IBinder?, fromUid: Int, targetPkg: String?, uri: Uri?, mode: Int, sourceUserId: Int, targetUserId: Int) {}

    private fun callingPackage(): String =
        android.app.ActivityThread.getPackageManager().getPackagesForUid(Binder.getCallingUid())?.firstOrNull() ?: ""
}
