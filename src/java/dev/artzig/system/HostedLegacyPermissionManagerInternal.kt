package dev.artzig.system

import android.Manifest
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.UserHandle
import com.android.server.pm.permission.LegacyPermissionManagerInternal

/**
 * DefaultPermissionGrantPolicy's default open-Wifi app grant over the hosted
 * package store. Its system-server permission backend is not hosted; scorer
 * selection and service binding remain in the unmodified NetworkScoreService.
 */
class HostedLegacyPermissionManagerInternal(private val packages: HostedPackageManagerService) :
    LegacyPermissionManagerInternal by unsupported<LegacyPermissionManagerInternal>() {
    @Volatile private var openWifiApps: LegacyPermissionManagerInternal.PackagesProvider? = null

    override fun setUseOpenWifiAppPackagesProvider(provider: LegacyPermissionManagerInternal.PackagesProvider?) {
        openWifiApps = provider
    }

    override fun grantDefaultPermissionsToDefaultUseOpenWifiApp(packageName: String, userId: Int) {
        if (userId != UserHandle.USER_SYSTEM) return
        val identity = Binder.clearCallingIdentity()
        try {
            val info = packages.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS.toLong(), userId) ?: return
            if ((info.applicationInfo?.targetSdkVersion ?: 0) <= Build.VERSION_CODES.LOLLIPOP_MR1) return
            val requested = info.requestedPermissions?.toSet().orEmpty()
            for (permission in arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                if (permission in requested) packages.grantRuntimePermission(packageName, permission, userId)
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }
}
