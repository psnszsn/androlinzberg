package dev.artzig.system

import android.content.pm.PackageManager
import android.os.Binder
import android.os.Process

/**
 * Caller permission checks for hosted services, over the hosted package manager's
 * install-time grants (no app ops). `Context.enforceCallingOrSelfPermission` for a
 * service that has no Context of the caller's process.
 */
open class HostedPermissions(private val packages: android.content.pm.IPackageManager) {
    fun has(permission: String, uid: Int = Binder.getCallingUid()): Boolean =
        uid == Process.SYSTEM_UID || uid == Process.ROOT_UID ||
            packages.checkUidPermission(permission, uid) == PackageManager.PERMISSION_GRANTED

    fun isSystemCaller(): Boolean = Binder.getCallingUid().let { it == Process.SYSTEM_UID || it == Process.ROOT_UID }

    fun isCallerPackage(packageName: String?, uid: Int = Binder.getCallingUid()): Boolean =
        packageName != null && (uid == Process.SYSTEM_UID || packages.getPackagesForUid(uid)?.contains(packageName) == true)

    /** AOSP verifyCaller / enforceCallingPackage: the package must belong to the calling uid. */
    fun enforceCallerPackage(packageName: String?, what: String) {
        if (!isCallerPackage(packageName)) throw SecurityException("$what: calling package name mismatch: uid ${Binder.getCallingUid()} is not $packageName")
    }

    fun enforce(permission: String, message: String) {
        if (!has(permission)) throw SecurityException("$message requires $permission")
    }

    fun enforceSystem(message: String) {
        if (!isSystemCaller()) throw SecurityException("$message is only available to the system")
    }
}
