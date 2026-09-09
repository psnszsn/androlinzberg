package com.android.server.pm

import android.app.ActivityThread
import android.app.role.RoleManager
import com.android.server.LocalServices
import dev.artzig.system.HostedPackageManagerService

/** Pinned PMS.getKnownPackageNamesInternal delegates to KnownPackages. The host
 * supplies its package/role registry instead of PMS's persistent settings and
 * device provisioning services; absent system package roles have no candidate. */
object HostedKnownPackages {
    @JvmStatic fun resolve(packages: HostedPackageManagerService, knownPackage: Int,
        userId: Int): Array<String> {
        val context = ActivityThread.currentActivityThread().systemContext as android.content.Context
        val defaults = DefaultAppProvider(
            { context.getSystemService(RoleManager::class.java) },
            { LocalServices.getService(UserManagerInternal::class.java) })
        return KnownPackages(defaults, null, null, null, emptyArray(), null, null,
            packages.permissionControllerPackageName, null, null, null, null,
            null, null, null, null, null).getKnownPackageNames(packages, knownPackage, userId)
    }
}
