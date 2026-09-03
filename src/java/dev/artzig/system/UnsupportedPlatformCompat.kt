@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every IPlatformCompat method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedPlatformCompat : com.android.internal.compat.IPlatformCompat.Stub() {
    override fun reportChange(changeId: Long, appInfo: android.content.pm.ApplicationInfo?) { throw UnsupportedOperationException("IPlatformCompat.reportChange is not hosted") }
    override fun reportChangeByPackageName(changeId: Long, packageName: String?, userId: Int) { throw UnsupportedOperationException("IPlatformCompat.reportChangeByPackageName is not hosted") }
    override fun reportChangeByUid(changeId: Long, uid: Int) { throw UnsupportedOperationException("IPlatformCompat.reportChangeByUid is not hosted") }
    override fun isChangeEnabled(changeId: Long, appInfo: android.content.pm.ApplicationInfo?): Boolean = throw UnsupportedOperationException("IPlatformCompat.isChangeEnabled is not hosted")
    override fun isChangeEnabledByPackageName(changeId: Long, packageName: String?, userId: Int): Boolean = throw UnsupportedOperationException("IPlatformCompat.isChangeEnabledByPackageName is not hosted")
    override fun isChangeEnabledByUid(changeId: Long, uid: Int): Boolean = throw UnsupportedOperationException("IPlatformCompat.isChangeEnabledByUid is not hosted")
    override fun setOverrides(overrides: com.android.internal.compat.CompatibilityChangeConfig?, packageName: String?) { throw UnsupportedOperationException("IPlatformCompat.setOverrides is not hosted") }
    override fun putAllOverridesOnReleaseBuilds(overridesByPackage: com.android.internal.compat.CompatibilityOverridesByPackageConfig?) { throw UnsupportedOperationException("IPlatformCompat.putAllOverridesOnReleaseBuilds is not hosted") }
    override fun putOverridesOnReleaseBuilds(overrides: com.android.internal.compat.CompatibilityOverrideConfig?, packageName: String?) { throw UnsupportedOperationException("IPlatformCompat.putOverridesOnReleaseBuilds is not hosted") }
    override fun setOverridesForTest(overrides: com.android.internal.compat.CompatibilityChangeConfig?, packageName: String?) { throw UnsupportedOperationException("IPlatformCompat.setOverridesForTest is not hosted") }
    override fun clearOverride(changeId: Long, packageName: String?): Boolean = throw UnsupportedOperationException("IPlatformCompat.clearOverride is not hosted")
    override fun clearOverrideForTest(changeId: Long, packageName: String?): Boolean = throw UnsupportedOperationException("IPlatformCompat.clearOverrideForTest is not hosted")
    override fun removeAllOverridesOnReleaseBuilds(overridesToRemoveByPackage: com.android.internal.compat.CompatibilityOverridesToRemoveByPackageConfig?) { throw UnsupportedOperationException("IPlatformCompat.removeAllOverridesOnReleaseBuilds is not hosted") }
    override fun removeOverridesOnReleaseBuilds(overridesToRemove: com.android.internal.compat.CompatibilityOverridesToRemoveConfig?, packageName: String?) { throw UnsupportedOperationException("IPlatformCompat.removeOverridesOnReleaseBuilds is not hosted") }
    override fun enableTargetSdkChanges(packageName: String?, targetSdkVersion: Int): Int = throw UnsupportedOperationException("IPlatformCompat.enableTargetSdkChanges is not hosted")
    override fun disableTargetSdkChanges(packageName: String?, targetSdkVersion: Int): Int = throw UnsupportedOperationException("IPlatformCompat.disableTargetSdkChanges is not hosted")
    override fun clearOverrides(packageName: String?) { throw UnsupportedOperationException("IPlatformCompat.clearOverrides is not hosted") }
    override fun clearOverridesForTest(packageName: String?) { throw UnsupportedOperationException("IPlatformCompat.clearOverridesForTest is not hosted") }
    override fun getAppConfig(appInfo: android.content.pm.ApplicationInfo?): com.android.internal.compat.CompatibilityChangeConfig? = throw UnsupportedOperationException("IPlatformCompat.getAppConfig is not hosted")
    override fun listAllChanges(): Array<com.android.internal.compat.CompatibilityChangeInfo?>? = throw UnsupportedOperationException("IPlatformCompat.listAllChanges is not hosted")
    override fun listUIChanges(): Array<com.android.internal.compat.CompatibilityChangeInfo?>? = throw UnsupportedOperationException("IPlatformCompat.listUIChanges is not hosted")
    override fun getOverrideValidator(): com.android.internal.compat.IOverrideValidator? = throw UnsupportedOperationException("IPlatformCompat.getOverrideValidator is not hosted")
}
