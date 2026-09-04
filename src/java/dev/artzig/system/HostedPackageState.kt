@file:Suppress("OVERRIDE_DEPRECATION")

package dev.artzig.system

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.SigningDetails
import android.content.pm.SigningInfo
import android.os.UserHandle
import android.util.SparseArray
import com.android.internal.pm.parsing.pkg.AndroidPackageInternal
import com.android.server.pm.InstallSource
import com.android.server.pm.PackageKeySetData
import com.android.server.pm.PackageSetting
import com.android.server.pm.HostedPackageStateDefaults
import com.android.server.pm.permission.LegacyPermissionState
import com.android.server.pm.pkg.AndroidPackage
import com.android.server.pm.pkg.PackageStateInternal
import com.android.server.pm.pkg.PackageStateUnserialized
import com.android.server.pm.pkg.PackageUserState
import com.android.server.pm.pkg.PackageUserStateImpl
import com.android.server.pm.pkg.PackageUserStateInternal
import com.android.server.pm.pkg.SharedLibrary
import java.io.File
import java.util.UUID

/**
 * The per-package state record of the hosted package manager: a parsed
 * [AndroidPackage] plus the session facts AOSP keeps in `PackageSetting`
 * (the real class is inseparable from PackageManagerService — spike result).
 * Read by the vendored `ComponentResolver`/`PackageInfoUtils` through the
 * `PackageStateInternal` surface; everything Android persists across boots
 * (install source, keysets, suspend state, ...) answers its fixed default.
 */
class HostedPackageState(
    private val pkg: AndroidPackageInternal,
    private val apkPath: String,
    /** True only for framework-res.apk installed as package "android". */
    private val system: Boolean,
    /** Manifest-requested permission names. */
    @JvmField val requestedPermissions: Set<String>,
    /** Install-time grants computed by the service's permission registry. */
    @JvmField val grantedPermissions: MutableSet<String>,
) : PackageStateInternal {
    /**
     * The broker-assigned Android uid, bound when the first process of this
     * package attaches; -1 (never granted an appId) before that. The single
     * hosted user is user 0, so uid == appId.
     */
    @Volatile @JvmField var appId: Int = if (system) android.os.Process.SYSTEM_UID else -1

    /** Mutable single-user state (component enabled settings live here). */
    @JvmField val userState = PackageUserStateImpl()
    private val userStates = SparseArray<PackageUserStateImpl>(1).apply { put(0, userState) }

    private val transientState = PackageStateUnserialized(PackageSetting())
    private val domainSetId = UUID.randomUUID()
    private val legacyPermissionState = LegacyPermissionState()
    private val keySetData = HostedPackageStateDefaults.emptyKeySetData()

    override fun getPkg(): AndroidPackageInternal = pkg
    override fun getAndroidPackage(): AndroidPackage = pkg
    override fun getPackageName(): String = pkg.packageName
    override fun getTransientState(): PackageStateUnserialized = transientState
    override fun getDomainSetId(): UUID = domainSetId
    override fun getSigningDetails(): SigningDetails = pkg.signingDetails
    override fun getSigningInfo(): SigningInfo = SigningInfo(pkg.signingDetails)
    override fun getInstallSource(): InstallSource = HostedPackageStateDefaults.emptyInstallSource()
    override fun getFlags(): Int = 0
    override fun getPrivateFlags(): Int = 0
    override fun getUserStates(): SparseArray<out PackageUserStateInternal> = userStates
    override fun getLegacyPermissionState(): LegacyPermissionState = legacyPermissionState
    override fun getRealName(): String? = null
    override fun isLoading(): Boolean = false
    override fun getPathString(): String = apkPath
    override fun getLoadingProgress(): Float = 1f
    override fun getLoadingCompletedTime(): Long = 0
    override fun getKeySetData(): PackageKeySetData = keySetData
    override fun getPrimaryCpuAbiLegacy(): String? = null
    override fun getSecondaryCpuAbiLegacy(): String? = null
    override fun getAppMetadataFilePath(): String? = null
    override fun getOldPaths(): Set<File>? = null
    override fun getAppMetadataSource(): Int = 0

    override fun getAppId(): Int = appId
    override fun getHiddenApiEnforcementPolicy(): Int =
        ApplicationInfo.HIDDEN_API_ENFORCEMENT_DISABLED
    override fun getPrimaryCpuAbi(): String? = null
    override fun getSecondaryCpuAbi(): String? = null
    override fun getSeInfo(): String = "default"
    override fun getStateForUser(user: UserHandle): PackageUserState =
        userStates.get(user.identifier) ?: PackageUserStateInternal.DEFAULT
    override fun getSharedLibraryDependencies(): List<SharedLibrary> = emptyList()
    override fun isApex(): Boolean = false
    override fun isPrivileged(): Boolean = system
    override fun isSystem(): Boolean = system
    override fun isUpdatedSystemApp(): Boolean = false
    override fun getCategoryOverride(): Int = ApplicationInfo.CATEGORY_UNDEFINED
    override fun isPageSizeAppCompatEnabled(): Boolean = false
    override fun getPageSizeCompatWarningMessage(context: Context?): String? = null
    override fun getCpuAbiOverride(): String? = null
    override fun getLastModifiedTime(): Long = 0
    override fun getLastPackageUsageTime(): LongArray = LongArray(8)
    override fun getLastUpdateTime(): Long = 0
    override fun getVersionCode(): Long = pkg.longVersionCode
    override fun getMimeGroups(): Map<String, Set<String>> = emptyMap()
    override fun getPath(): File = File(apkPath)
    override fun hasSharedUser(): Boolean = false
    override fun isPendingRestore(): Boolean = false
    override fun isDebuggable(): Boolean = pkg.isDebuggable
    override fun getSharedUserAppId(): Int = -1
    override fun getUsesLibraryFiles(): List<String> = emptyList()
    override fun getUsesSdkLibraries(): Array<String> = emptyArray()
    override fun getUsesSdkLibrariesVersionsMajor(): LongArray = LongArray(0)
    override fun getUsesSdkLibrariesOptional(): BooleanArray = BooleanArray(0)
    override fun getUsesStaticLibraries(): Array<String> = emptyArray()
    override fun getUsesStaticLibrariesVersions(): LongArray = LongArray(0)
    override fun getVolumeUuid(): String? = null
    override fun isDefaultToDeviceProtectedStorage(): Boolean = false
    override fun isExternalStorage(): Boolean = false
    override fun isForceQueryableOverride(): Boolean = false
    override fun isHiddenUntilInstalled(): Boolean = false
    override fun isInstallPermissionsFixed(): Boolean = true
    override fun isOdm(): Boolean = false
    override fun isOem(): Boolean = false
    override fun isProduct(): Boolean = false
    override fun isRequiredForSystemUser(): Boolean = false
    override fun isSystemExt(): Boolean = false
    override fun isUpdateAvailable(): Boolean = false
    override fun isApkInUpdatedApex(): Boolean = false
    override fun isVendor(): Boolean = false
    override fun getApexModuleName(): String? = null
    override fun isPersistent(): Boolean = false
    override fun getTargetSdkVersion(): Int = pkg.targetSdkVersion
    override fun getRestrictUpdateHash(): ByteArray? = null
    override fun isScannedAsStoppedSystemApp(): Boolean = false
    override fun isLeavingSharedUser(): Boolean = false
}
