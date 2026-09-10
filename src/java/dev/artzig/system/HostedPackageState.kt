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
import com.android.server.pm.setHostedArchiveState
import com.android.server.pm.permission.LegacyPermissionState
import com.android.server.pm.parsing.pkg.AndroidPackageUtils
import com.android.server.pm.pkg.AndroidPackage
import com.android.server.pm.pkg.PackageStateInternal
import com.android.server.pm.pkg.PackageStateUnserialized
import com.android.server.pm.pkg.PackageUserState
import com.android.server.pm.pkg.PackageUserStateInternal
import com.android.server.pm.pkg.SharedLibrary
import java.io.File
import java.util.UUID

/**
 * The per-package state record of the hosted package manager: a parsed
 * [AndroidPackage] plus the session facts AOSP keeps in `PackageSetting`
 * with mutable per-user and transient state held by stock `PackageSetting`.
 * Read by the vendored `ComponentResolver`/`PackageInfoUtils` through the
 * `PackageStateInternal` surface; everything Android persists across boots
 * (install source, keysets, suspend state, ...) answers its fixed default.
 */
class HostedPackageState(
    private val pkg: AndroidPackageInternal,
    private val apkPath: String,
    /** True for framework-res.apk and bundled system APKs. */
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
    @Volatile @JvmField var appId: Int = if (system && pkg.packageName == "android") android.os.Process.SYSTEM_UID else -1

    private val domainSetId = UUID.randomUUID()
    private val setting = PackageSetting(pkg.packageName, null, File(apkPath), 0, 0, domainSetId)

    /** The hosted user uses the stock setting's change-tracked state. */
    @JvmField val userState = setting.getOrCreateUserState(0)
    private var snapshotManifest: com.android.internal.pm.parsing.pkg.PackageImpl? = null

    internal fun setArchiveState(userId: Int, archiveState: com.android.server.pm.pkg.ArchiveState?) {
        setting.setHostedArchiveState(archiveState, userId)
    }

    /** PackageSetting.snapshot supplies the stock user/keyset/transient-state
     * copy rules. PackageImpl's original parcel form captures the manifest and
     * its hosted UID/platform-signing assignment while retaining AndroidPackageHidden
     * behavior used by the original PackageInfoUtils.
     * PackageStateUnserialized intentionally shares usage counters in AOSP. */
    internal fun snapshot(): PackageStateInternal {
        val source = this
        val capturedSetting = setting.snapshot()
        val capturedAppId = appId
        // Parsed manifests do not change after install except for these two
        // assignments. Share their immutable copy across registry snapshots,
        // just as PackageSetting shares AOSP's finalized parsed package.
        val manifest = snapshotManifest?.takeIf {
            it.uid == pkg.uid && it.isSignedWithPlatformKey == pkg.isSignedWithPlatformKey
        } ?: run {
            val parcel = android.os.Parcel.obtain()
            try {
                (pkg as com.android.internal.pm.parsing.pkg.PackageImpl).writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                com.android.internal.pm.parsing.pkg.PackageImpl.CREATOR.createFromParcel(parcel)
                    .also { captured ->
                        // PackageImpl's parcel constructor recomputes these derived
                        // paths. Preserve the session storage assigned at installation
                        // so component resolution and direct package queries agree.
                        for (fieldName in listOf(
                            "mBaseAppDataCredentialProtectedDirForSystemUser",
                            "mBaseAppDataDeviceProtectedDirForSystemUser",
                        )) {
                            com.android.internal.pm.parsing.pkg.PackageImpl::class.java
                                .getDeclaredField(fieldName).apply { isAccessible = true }
                                .let { it.set(captured, it.get(pkg)) }
                        }
                        snapshotManifest = captured
                    }
            } finally {
                parcel.recycle()
            }
        }
        return object : PackageStateInternal by source {
            override fun getAppId(): Int = capturedAppId
            override fun getPkg(): AndroidPackageInternal = manifest
            override fun getAndroidPackage(): AndroidPackage = manifest
            override fun getFlags(): Int = com.android.server.pm.parsing.PackageInfoUtils.appInfoFlags(manifest, source)
            override fun getPrivateFlags(): Int = com.android.server.pm.parsing.PackageInfoUtils.appInfoPrivateFlags(manifest, source)
            override fun getUserStates(): SparseArray<out PackageUserStateInternal> = capturedSetting.userStates
            override fun getUserStateOrDefault(userId: Int): PackageUserStateInternal =
                capturedSetting.getUserStateOrDefault(userId)
            override fun getStateForUser(user: UserHandle): PackageUserState = capturedSetting.getStateForUser(user)
            override fun getTransientState(): PackageStateUnserialized = capturedSetting.transientState
            override fun getInstallSource(): InstallSource = capturedSetting.installSource
            override fun getLegacyPermissionState(): LegacyPermissionState = capturedSetting.legacyPermissionState
            override fun getKeySetData(): PackageKeySetData = capturedSetting.keySetData
            override fun getLastPackageUsageTime(): LongArray = capturedSetting.transientState.lastPackageUsageTimeInMills
        }
    }

    override fun getPkg(): AndroidPackageInternal = pkg
    override fun getAndroidPackage(): AndroidPackage = pkg
    override fun getPackageName(): String = pkg.packageName
    override fun getTransientState(): PackageStateUnserialized = setting.transientState
    override fun getDomainSetId(): UUID = domainSetId
    override fun getSigningDetails(): SigningDetails = pkg.signingDetails
    override fun getSigningInfo(): SigningInfo = SigningInfo(pkg.signingDetails)
    override fun getInstallSource(): InstallSource = setting.installSource
    // ScanPackageUtils derives these flags before constructing PackageSetting.
    // Archive eligibility reads them directly, including the system-app bit.
    override fun getFlags(): Int = com.android.server.pm.parsing.PackageInfoUtils.appInfoFlags(pkg, this)
    override fun getPrivateFlags(): Int = com.android.server.pm.parsing.PackageInfoUtils.appInfoPrivateFlags(pkg, this)
    override fun getUserStates(): SparseArray<out PackageUserStateInternal> = setting.userStates
    override fun getLegacyPermissionState(): LegacyPermissionState = setting.legacyPermissionState
    override fun getRealName(): String? = null
    override fun isLoading(): Boolean = false
    override fun getPathString(): String = apkPath
    override fun getLoadingProgress(): Float = 1f
    override fun getLoadingCompletedTime(): Long = 0
    override fun getKeySetData(): PackageKeySetData = setting.keySetData
    override fun getPrimaryCpuAbiLegacy(): String? = AndroidPackageUtils.getRawPrimaryCpuAbi(pkg)
    override fun getSecondaryCpuAbiLegacy(): String? = AndroidPackageUtils.getRawSecondaryCpuAbi(pkg)
    override fun getAppMetadataFilePath(): String? = null
    override fun getOldPaths(): Set<File>? = null
    override fun getAppMetadataSource(): Int = 0

    override fun getAppId(): Int = appId
    override fun getHiddenApiEnforcementPolicy(): Int =
        ApplicationInfo.HIDDEN_API_ENFORCEMENT_DISABLED
    override fun getPrimaryCpuAbi(): String? = AndroidPackageUtils.getRawPrimaryCpuAbi(pkg)
    override fun getSecondaryCpuAbi(): String? = AndroidPackageUtils.getRawSecondaryCpuAbi(pkg)
    override fun getSeInfo(): String = "default"
    override fun getStateForUser(user: UserHandle): PackageUserState =
        setting.getStateForUser(user)
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
    override fun getLastPackageUsageTime(): LongArray = setting.transientState.lastPackageUsageTimeInMills
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
