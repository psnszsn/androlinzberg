package dev.artzig.system

import android.content.Context
import android.content.pm.PackageManager
import android.permission.PermissionGroupUsage
import dev.artzig.ArtzigTestDiagnostics

/**
 * Optional collaborators of the pinned AppOpsManager client. HealthConnectManager
 * tests the health prefix before resolving its permission-group owner; an absent
 * module produces an empty permission set. Importing that entire module requires
 * the Health Connect service. PermissionUsageHelper also starts Android audio,
 * telephony and companion-device observers that this host does not provide.
 */
object HostAppOpsOptionalServices {
    @JvmStatic fun isHealthPermission(context: Context?, permission: String): Boolean {
        if (!permission.startsWith("android.permission.health.")) return false
        val pm = checkNotNull(context).applicationContext.packageManager
        try {
            pm.getPermissionGroupInfo("android.permission-group.HEALTH", 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        throw ArtzigTestDiagnostics.unsupported("android.health.connect.HealthConnectManager",
            "isHealthPermission", "A Health Connect permission owner is installed but its module is not hosted")
    }

    @JvmStatic fun getPermissionGroupUsageForPrivacyIndicator(
        context: Context, includeMicrophoneUsage: Boolean,
    ): List<PermissionGroupUsage> {
        context.enforceCallingOrSelfPermission(android.Manifest.permission.GET_APP_OPS_STATS,
            "getPermissionGroupUsageForPrivacyIndicator")
        throw ArtzigTestDiagnostics.unsupported("android.app.AppOpsManager",
            "getPermissionGroupUsageForPrivacyIndicator",
            "Device privacy usage collection is not hosted (includeMicrophoneUsage=$includeMicrophoneUsage)")
    }
}
