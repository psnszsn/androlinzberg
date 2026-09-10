package dev.artzig.system

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Process
import android.os.UserHandle
import android.util.Slog
import com.android.server.pm.parsing.PackageInfoUtils
import com.android.server.pm.pkg.AndroidPackage
import com.android.server.pm.pkg.PackageStateInternal
import com.android.server.pm.resolution.ComponentResolverApi
import com.android.server.pm.snapshot.PackageDataSnapshot

/** An immutable package/resolver revision for the hosted package authority.
 * ComputerEngine requires PMS's full persistent settings and process graph.
 * The reused resolver instead receives this captured registry, using original
 * PackageSetting snapshots, parsed packages and ComponentResolverSnapshot.
 * No query falls back to mutable registry state or a default service proxy. */
class HostedPackageSnapshot internal constructor(
    private val states: Map<String, PackageStateInternal>,
    val componentResolver: ComponentResolverApi,
    private val userIds: Set<Int> = setOf(UserHandle.USER_SYSTEM),
    private val permissionGrants: Map<Int, Set<String>> = emptyMap(),
    private val shellRestrictedUsers: Set<Int> = emptySet(),
) : PackageDataSnapshot {
    fun getPackage(packageName: String): AndroidPackage? = states[packageName]?.androidPackage
    fun getPackageStateInternal(packageName: String): PackageStateInternal? = states[packageName]

    // ComputerEngine.getPackageStateFiltered: the hosted registry has the same
    // public package-visibility policy as its PackageManagerInternal adapter.
    // Keep archived records: installed-state filtering belongs to the query.
    fun getPackageStateFiltered(packageName: String, callingUid: Int, userId: Int): PackageStateInternal? =
        states[packageName]

    fun getApplicationInfo(packageName: String?, flags: Long, userId: Int): ApplicationInfo? {
        if (userId !in userIds) return null
        val state = states[packageName ?: return null] ?: return null
        val pkg = state.pkg ?: return null
        return PackageInfoUtils.generateApplicationInfo(pkg, withDirectBoot(flags),
            state.getUserStateOrDefault(userId), userId, state)
    }

    fun getPackageUid(packageName: String?, flags: Long, userId: Int): Int =
        getApplicationInfo(packageName, flags, userId)?.uid ?: -1

    fun queryHomeActivities(packageName: String?, userId: Int): List<ResolveInfo> =
        queryHomeActivities(packageName, userId, 0L)

    private fun queryHomeActivities(packageName: String?, userId: Int, flags: Long): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addCategory(Intent.CATEGORY_DEFAULT).setPackage(packageName)
        return if (packageName == null) {
            componentResolver.queryActivities(this, intent, null, withDirectBoot(flags), userId).orEmpty()
        } else {
            val state = states[packageName] ?: return emptyList()
            componentResolver.queryActivities(this, intent, null, withDirectBoot(flags),
                state.pkg!!.activities, userId).orEmpty()
        }
    }

    fun hasUnarchiveReceiver(packageName: String, userId: Int): Boolean {
        val state = states[packageName] ?: return false
        val intent = Intent(Intent.ACTION_UNARCHIVE_PACKAGE).setPackage(packageName)
        return !componentResolver.queryReceivers(this, intent, null, withDirectBoot(0L),
            state.pkg!!.receivers, userId).isNullOrEmpty()
    }

    /** ComputerEngine.getDefaultHomeActivity's fallback with no ROLE_HOME holder. */
    fun getDefaultHomeActivity(userId: Int): ComponentName? {
        var priority = Int.MIN_VALUE
        var component: ComponentName? = null
        for (candidate in queryHomeActivities(null, userId, PackageManager.GET_META_DATA.toLong())) {
            if (candidate.priority > priority) {
                component = candidate.activityInfo.componentName
                priority = candidate.priority
            } else if (candidate.priority == priority) component = null
        }
        return component
    }

    /** ComputerEngine.enforceCrossUserPermission, using this authority revision's
     * grants and user restrictions rather than reentering a mutable PM service. */
    fun enforceCrossUserPermission(callingUid: Int, userId: Int, requireFullPermission: Boolean,
        checkShell: Boolean, message: String?) {
        require(userId >= 0) { "Invalid userId $userId" }
        if (checkShell && callingUid == Process.SHELL_UID && userId in shellRestrictedUsers) {
            throw SecurityException("Shell does not have permission to access user $userId")
        }
        if (UserHandle.getUserId(callingUid) == userId || callingUid == Process.SYSTEM_UID ||
            callingUid == Process.ROOT_UID) return
        val grants = permissionGrants[callingUid].orEmpty()
        if (Manifest.permission.INTERACT_ACROSS_USERS_FULL in grants ||
            (!requireFullPermission && Manifest.permission.INTERACT_ACROSS_USERS in grants)) return
        val error = (message?.let { "$it: " } ?: "") + "UID $callingUid requires " +
            Manifest.permission.INTERACT_ACROSS_USERS_FULL +
            (if (requireFullPermission) "" else " or " + Manifest.permission.INTERACT_ACROSS_USERS) +
            " to access user $userId."
        Slog.w("PackageManager", error)
        throw SecurityException(error)
    }

    private fun withDirectBoot(flags: Long): Long = flags or
        PackageManager.MATCH_DIRECT_BOOT_AWARE.toLong() or PackageManager.MATCH_DIRECT_BOOT_UNAWARE.toLong()

    // This registry has no shadowed system packages or shared-UID installations.
    fun getDisabledSystemPackage(packageName: String): PackageStateInternal? = null
    fun filterOnlySystemPackages(vararg packageNames: String?): Array<String> =
        packageNames.filterNotNull().filter { states[it]?.isSystem == true }.toTypedArray()
}
