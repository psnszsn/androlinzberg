package dev.artzig.system

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
) : PackageDataSnapshot {
    fun getPackage(packageName: String): AndroidPackage? = states[packageName]?.androidPackage
    fun getPackageStateInternal(packageName: String): PackageStateInternal? = states[packageName]

    // This registry has no shadowed system packages or shared-UID installations.
    fun getDisabledSystemPackage(packageName: String): PackageStateInternal? = null
    fun filterOnlySystemPackages(vararg packageNames: String?): Array<String> =
        packageNames.filterNotNull().filter { states[it]?.isSystem == true }.toTypedArray()
}
