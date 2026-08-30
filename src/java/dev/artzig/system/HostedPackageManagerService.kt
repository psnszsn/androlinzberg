package dev.artzig.system

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.res.ApkAssets
import android.content.res.AssetManager
import android.os.Binder
import android.os.Bundle
import android.util.Log

/**
 * The session-wide package manager: one record per installed package, fed by
 * each process registering its parsed APK (or by parsing here for respawns).
 *
 * Source note: follows `com.android.server.pm.ComputerEngine`
 * (`queryIntentServicesInternal`, `resolveIntentInternal`, `getPackageInfo`)
 * and `PermissionManagerService.checkPermission` for query and permission
 * semantics; `PackageManagerService` itself is inseparable from the full
 * system_server graph (spike result), so the record model is hosted. Intent
 * matching uses the parser's per-service action sets; full `IntentFilter`
 * matching arrives with cross-process broadcasts.
 */
class HostedPackageManagerService : IHostedPackageManager.Stub() {
    private class Record(val pkg: HostedInstalledPackage, val uid: Int)

    private val lock = Any()
    private val packages = LinkedHashMap<String, Record>()

    override fun registerPackage(pkg: HostedInstalledPackage) {
        val callerUid = Binder.getCallingUid()
        synchronized(lock) {
            val existing = packages[pkg.packageInfo.packageName]
            // A package record belongs to the uid that first registered it: a
            // different app uid may not replace its content (that would let a
            // peer plant grantedPermissions honoured for the victim's uid).
            // The system uid may always update, and a system-side parse
            // (installPackage before spawn) yields to the app process that
            // later attaches with its broker-assigned uid.
            if (existing != null && callerUid != android.os.Process.SYSTEM_UID &&
                existing.uid != android.os.Process.SYSTEM_UID && existing.uid != callerUid
            ) {
                throw SecurityException(
                    "uid " + callerUid + " may not re-register " + pkg.packageInfo.packageName +
                        " owned by uid " + existing.uid,
                )
            }
            // Interim identity lock (until per-connection credential auth): an
            // app uid owns exactly one package, so a peer cannot register a
            // second, unused package name and self-grant its permissions. The
            // system uid legitimately parses many (android + installPackage).
            if (existing == null && callerUid != android.os.Process.SYSTEM_UID) {
                val already = packages.values.firstOrNull { it.uid == callerUid }
                if (already != null) {
                    throw SecurityException(
                        "uid " + callerUid + " already owns " + already.pkg.packageInfo.packageName +
                            " and may not also register " + pkg.packageInfo.packageName,
                    )
                }
            }
            val uid = when {
                existing == null -> callerUid
                existing.uid == android.os.Process.SYSTEM_UID &&
                    callerUid != android.os.Process.SYSTEM_UID -> callerUid
                else -> existing.uid
            }
            packages[pkg.packageInfo.packageName] = Record(pkg, uid)
        }
        Log.i(TAG, "registered " + pkg.packageInfo.packageName + " uid=" + callerUid)
    }

    override fun installPackage(apkPath: String): HostedInstalledPackage {
        synchronized(lock) {
            packages.values.firstOrNull { it.pkg.apkPath == apkPath }?.let { return it.pkg }
        }
        val assets = AssetManager.Builder().addApkAssets(ApkAssets.loadFromPath(apkPath)).build()
        val hosted = try {
            android.content.pm.HostedPackageParser.parse(assets)
        } finally {
            assets.close()
        }
        val installed = PackageManager.toInstalledPackage(hosted, apkPath, null, emptyMap())
        registerPackage(installed)
        return installed
    }

    override fun getPackage(packageName: String?): HostedInstalledPackage? =
        synchronized(lock) { packages[packageName]?.pkg }

    override fun queryIntentServices(intent: Intent?, flags: Int): List<ResolveInfo> {
        val result = ArrayList<ResolveInfo>()
        if (intent == null) return result
        val component = intent.component
        synchronized(lock) {
            for (record in packages.values) {
                val info = record.pkg.packageInfo
                val services = info.services ?: continue
                for (service in services) {
                    if (!service.enabled && (flags and PackageManager.MATCH_DISABLED_COMPONENTS) == 0) continue
                    if (component != null) {
                        if (component.packageName != service.packageName || component.className != service.name) continue
                    } else {
                        if (intent.`package` != null && intent.`package` != service.packageName) continue
                        val action = intent.action ?: continue
                        val actions = record.pkg.serviceActions.getStringArrayList(service.name) ?: continue
                        if (!actions.contains(action)) continue
                    }
                    val resolve = ResolveInfo()
                    resolve.serviceInfo = ServiceInfo(service)
                    result.add(resolve)
                }
            }
        }
        return result
    }

    override fun queryIntentActivities(intent: Intent?, flags: Int): List<ResolveInfo> {
        val result = ArrayList<ResolveInfo>()
        val component = intent?.component ?: return result
        synchronized(lock) {
            for (record in packages.values) {
                val activities = record.pkg.packageInfo.activities ?: continue
                for (activity in activities) {
                    if (component.packageName != activity.packageName || component.className != activity.name) continue
                    if (!activity.enabled && (flags and PackageManager.MATCH_DISABLED_COMPONENTS) == 0) continue
                    val resolve = ResolveInfo()
                    resolve.activityInfo = android.content.pm.ActivityInfo(activity)
                    result.add(resolve)
                }
            }
        }
        return result
    }

    override fun checkPermission(permission: String?, packageName: String?): Int {
        if (permission == null) return PackageManager.PERMISSION_DENIED
        synchronized(lock) {
            val record = packages[packageName] ?: return PackageManager.PERMISSION_DENIED
            return if (record.pkg.grantedPermissions.contains(permission)) {
                PackageManager.PERMISSION_GRANTED
            } else {
                PackageManager.PERMISSION_DENIED
            }
        }
    }

    override fun checkUidPermission(permission: String?, uid: Int): Int {
        if (uid == android.os.Process.ROOT_UID || uid == android.os.Process.SYSTEM_UID) {
            return PackageManager.PERMISSION_GRANTED
        }
        synchronized(lock) {
            val granted = packages.values.any { it.uid == uid && it.pkg.grantedPermissions.contains(permission) }
            return if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
    }

    override fun getPackagesForUid(uid: Int): Array<String> = synchronized(lock) {
        packages.values.filter { it.uid == uid }.map { it.pkg.packageInfo.packageName }.toTypedArray()
    }

    override fun getPackageUid(packageName: String?): Int = synchronized(lock) {
        packages[packageName]?.uid ?: -1
    }

    override fun getApkPath(packageName: String?): String? = synchronized(lock) {
        packages[packageName]?.pkg?.apkPath
    }

    override fun getInstalledPackageNames(): Array<String> = synchronized(lock) {
        packages.keys.toTypedArray()
    }

    private companion object {
        const val TAG = "HostedPackageManager"
    }
}
