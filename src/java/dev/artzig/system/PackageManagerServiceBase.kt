@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/**
 * All-throwing base for the generated `android.content.pm.IPackageManager`
 * stub: every binder method fails loudly until
 * [HostedPackageManagerService] overrides it, which is the gap-discovery
 * mechanism for unmodified AOSP clients (the hosted Parcel carries the
 * exception back to the caller as a remote failure).
 *
 * Mechanically generated from the aidl-generated IPackageManager.java
 * (see the regeneration recipe in the project plan); do not hand-edit
 * signatures.
 */
abstract class PackageManagerServiceBase : android.content.pm.IPackageManager.Stub() {
    override fun checkPackageStartable(packageName: String?, userId: Int) {
        throw UnsupportedOperationException("IPackageManager.checkPackageStartable")
    }

    override fun isPackageAvailable(packageName: String?, userId: Int): Boolean =
        throw UnsupportedOperationException("IPackageManager.isPackageAvailable")

    override fun getPackageInfo(packageName: String?, flags: Long, userId: Int): android.content.pm.PackageInfo? =
        throw UnsupportedOperationException("IPackageManager.getPackageInfo")

    override fun getPackageInfoVersioned(versionedPackage: android.content.pm.VersionedPackage?, flags: Long, userId: Int): android.content.pm.PackageInfo? =
        throw UnsupportedOperationException("IPackageManager.getPackageInfoVersioned")

    override fun getPackageUid(packageName: String?, flags: Long, userId: Int): Int =
        throw UnsupportedOperationException("IPackageManager.getPackageUid")

    override fun getPackageGids(packageName: String?, flags: Long, userId: Int): IntArray? =
        throw UnsupportedOperationException("IPackageManager.getPackageGids")

    override fun currentToCanonicalPackageNames(names: Array<String>?): Array<String>? =
        throw UnsupportedOperationException("IPackageManager.currentToCanonicalPackageNames")

    override fun canonicalToCurrentPackageNames(names: Array<String>?): Array<String>? =
        throw UnsupportedOperationException("IPackageManager.canonicalToCurrentPackageNames")

    override fun getApplicationInfo(packageName: String?, flags: Long, userId: Int): android.content.pm.ApplicationInfo? =
        throw UnsupportedOperationException("IPackageManager.getApplicationInfo")

    override fun getTargetSdkVersion(packageName: String?): Int =
        throw UnsupportedOperationException("IPackageManager.getTargetSdkVersion")

    override fun getActivityInfo(className: android.content.ComponentName?, flags: Long, userId: Int): android.content.pm.ActivityInfo? =
        throw UnsupportedOperationException("IPackageManager.getActivityInfo")

    override fun activitySupportsIntentAsUser(className: android.content.ComponentName?, intent: android.content.Intent?, resolvedType: String?, userId: Int): Boolean =
        throw UnsupportedOperationException("IPackageManager.activitySupportsIntentAsUser")

    override fun getReceiverInfo(className: android.content.ComponentName?, flags: Long, userId: Int): android.content.pm.ActivityInfo? =
        throw UnsupportedOperationException("IPackageManager.getReceiverInfo")

    override fun getServiceInfo(className: android.content.ComponentName?, flags: Long, userId: Int): android.content.pm.ServiceInfo? =
        throw UnsupportedOperationException("IPackageManager.getServiceInfo")

    override fun getProviderInfo(className: android.content.ComponentName?, flags: Long, userId: Int): android.content.pm.ProviderInfo? =
        throw UnsupportedOperationException("IPackageManager.getProviderInfo")

    override fun isProtectedBroadcast(actionName: String?): Boolean =
        throw UnsupportedOperationException("IPackageManager.isProtectedBroadcast")

    override fun checkSignatures(pkg1: String?, pkg2: String?, userId: Int): Int =
        throw UnsupportedOperationException("IPackageManager.checkSignatures")

    override fun checkUidSignatures(uid1: Int, uid2: Int): Int =
        throw UnsupportedOperationException("IPackageManager.checkUidSignatures")

    override fun getAllPackages(): MutableList<String>? =
        throw UnsupportedOperationException("IPackageManager.getAllPackages")

    override fun getPackagesForUid(uid: Int): Array<String>? =
        throw UnsupportedOperationException("IPackageManager.getPackagesForUid")

    override fun getNameForUid(uid: Int): String? =
        throw UnsupportedOperationException("IPackageManager.getNameForUid")

    override fun getNamesForUids(uids: IntArray?): Array<String>? =
        throw UnsupportedOperationException("IPackageManager.getNamesForUids")

    override fun getUidForSharedUser(sharedUserName: String?): Int =
        throw UnsupportedOperationException("IPackageManager.getUidForSharedUser")

    override fun getFlagsForUid(uid: Int): Int =
        throw UnsupportedOperationException("IPackageManager.getFlagsForUid")

    override fun getPrivateFlagsForUid(uid: Int): Int =
        throw UnsupportedOperationException("IPackageManager.getPrivateFlagsForUid")

    override fun isUidPrivileged(uid: Int): Boolean =
        throw UnsupportedOperationException("IPackageManager.isUidPrivileged")

    override fun resolveIntent(intent: android.content.Intent?, resolvedType: String?, flags: Long, userId: Int): android.content.pm.ResolveInfo? =
        throw UnsupportedOperationException("IPackageManager.resolveIntent")

    override fun findPersistentPreferredActivity(intent: android.content.Intent?, userId: Int): android.content.pm.ResolveInfo? =
        throw UnsupportedOperationException("IPackageManager.findPersistentPreferredActivity")

    override fun canForwardTo(intent: android.content.Intent?, resolvedType: String?, sourceUserId: Int, targetUserId: Int): Boolean =
        throw UnsupportedOperationException("IPackageManager.canForwardTo")

    override fun queryIntentActivities(intent: android.content.Intent?, resolvedType: String?, flags: Long, userId: Int): android.content.pm.ParceledListSlice<*>? =
        throw UnsupportedOperationException("IPackageManager.queryIntentActivities")

    override fun queryIntentActivityOptions(caller: android.content.ComponentName?, specifics: Array<android.content.Intent>?, specificTypes: Array<String>?, intent: android.content.Intent?, resolvedType: String?, flags: Long, userId: Int): android.content.pm.ParceledListSlice<*>? =
        throw UnsupportedOperationException("IPackageManager.queryIntentActivityOptions")

    override fun queryIntentReceivers(intent: android.content.Intent?, resolvedType: String?, flags: Long, userId: Int): android.content.pm.ParceledListSlice<*>? =
        throw UnsupportedOperationException("IPackageManager.queryIntentReceivers")

    override fun resolveService(intent: android.content.Intent?, resolvedType: String?, flags: Long, userId: Int): android.content.pm.ResolveInfo? =
        throw UnsupportedOperationException("IPackageManager.resolveService")

    override fun queryIntentServices(intent: android.content.Intent?, resolvedType: String?, flags: Long, userId: Int): android.content.pm.ParceledListSlice<*>? =
        throw UnsupportedOperationException("IPackageManager.queryIntentServices")

    override fun queryIntentContentProviders(intent: android.content.Intent?, resolvedType: String?, flags: Long, userId: Int): android.content.pm.ParceledListSlice<*>? =
        throw UnsupportedOperationException("IPackageManager.queryIntentContentProviders")

    override fun getInstalledPackages(flags: Long, userId: Int): android.content.pm.ParceledListSlice<*>? =
        throw UnsupportedOperationException("IPackageManager.getInstalledPackages")

    override fun getAppMetadataFd(packageName: String?, userId: Int): android.os.ParcelFileDescriptor? =
        throw UnsupportedOperationException("IPackageManager.getAppMetadataFd")

    override fun getPackagesHoldingPermissions(permissions: Array<String>?, flags: Long, userId: Int): android.content.pm.ParceledListSlice<*>? =
        throw UnsupportedOperationException("IPackageManager.getPackagesHoldingPermissions")

    override fun getInstalledApplications(flags: Long, userId: Int): android.content.pm.ParceledListSlice<*>? =
        throw UnsupportedOperationException("IPackageManager.getInstalledApplications")

    override fun getPersistentApplications(flags: Int): android.content.pm.ParceledListSlice<*>? =
        throw UnsupportedOperationException("IPackageManager.getPersistentApplications")

    override fun resolveContentProvider(name: String?, flags: Long, userId: Int): android.content.pm.ProviderInfo? =
        throw UnsupportedOperationException("IPackageManager.resolveContentProvider")

    override fun querySyncProviders(outNames: MutableList<String>?, outInfo: MutableList<android.content.pm.ProviderInfo>?) {
        throw UnsupportedOperationException("IPackageManager.querySyncProviders")
    }

    override fun queryContentProviders(processName: String?, uid: Int, flags: Long, metaDataKey: String?): android.content.pm.ParceledListSlice<*>? =
        throw UnsupportedOperationException("IPackageManager.queryContentProviders")

    override fun getInstrumentationInfoAsUser(className: android.content.ComponentName?, flags: Int, userId: Int): android.content.pm.InstrumentationInfo? =
        throw UnsupportedOperationException("IPackageManager.getInstrumentationInfoAsUser")

    override fun queryInstrumentationAsUser(targetPackage: String?, flags: Int, userId: Int): android.content.pm.ParceledListSlice<*>? =
        throw UnsupportedOperationException("IPackageManager.queryInstrumentationAsUser")

    override fun finishPackageInstall(token: Int, didLaunch: Boolean) {
        throw UnsupportedOperationException("IPackageManager.finishPackageInstall")
    }

    override fun setInstallerPackageName(targetPackage: String?, installerPackageName: String?) {
        throw UnsupportedOperationException("IPackageManager.setInstallerPackageName")
    }

    override fun relinquishUpdateOwnership(targetPackage: String?) {
        throw UnsupportedOperationException("IPackageManager.relinquishUpdateOwnership")
    }

    override fun setApplicationCategoryHint(packageName: String?, categoryHint: Int, callerPackageName: String?) {
        throw UnsupportedOperationException("IPackageManager.setApplicationCategoryHint")
    }

    override fun deletePackageAsUser(packageName: String?, versionCode: Int, observer: android.content.pm.IPackageDeleteObserver?, userId: Int, flags: Int) {
        throw UnsupportedOperationException("IPackageManager.deletePackageAsUser")
    }

    override fun deletePackageVersioned(versionedPackage: android.content.pm.VersionedPackage?, observer: android.content.pm.IPackageDeleteObserver2?, userId: Int, flags: Int) {
        throw UnsupportedOperationException("IPackageManager.deletePackageVersioned")
    }

    override fun deleteExistingPackageAsUser(versionedPackage: android.content.pm.VersionedPackage?, observer: android.content.pm.IPackageDeleteObserver2?, userId: Int) {
        throw UnsupportedOperationException("IPackageManager.deleteExistingPackageAsUser")
    }

    override fun getInstallerPackageName(packageName: String?): String? =
        throw UnsupportedOperationException("IPackageManager.getInstallerPackageName")

    override fun getInstallSourceInfo(packageName: String?, userId: Int): android.content.pm.InstallSourceInfo? =
        throw UnsupportedOperationException("IPackageManager.getInstallSourceInfo")

    override fun resetApplicationPreferences(userId: Int) {
        throw UnsupportedOperationException("IPackageManager.resetApplicationPreferences")
    }

    override fun getLastChosenActivity(intent: android.content.Intent?, resolvedType: String?, flags: Int): android.content.pm.ResolveInfo? =
        throw UnsupportedOperationException("IPackageManager.getLastChosenActivity")

    override fun setLastChosenActivity(intent: android.content.Intent?, resolvedType: String?, flags: Int, filter: android.content.IntentFilter?, match: Int, activity: android.content.ComponentName?) {
        throw UnsupportedOperationException("IPackageManager.setLastChosenActivity")
    }

    override fun addPreferredActivity(filter: android.content.IntentFilter?, match: Int, set: Array<android.content.ComponentName>?, activity: android.content.ComponentName?, userId: Int, removeExisting: Boolean) {
        throw UnsupportedOperationException("IPackageManager.addPreferredActivity")
    }

    override fun replacePreferredActivity(filter: android.content.IntentFilter?, match: Int, set: Array<android.content.ComponentName>?, activity: android.content.ComponentName?, userId: Int) {
        throw UnsupportedOperationException("IPackageManager.replacePreferredActivity")
    }

    override fun clearPackagePreferredActivities(packageName: String?) {
        throw UnsupportedOperationException("IPackageManager.clearPackagePreferredActivities")
    }

    override fun getPreferredActivities(outFilters: MutableList<android.content.IntentFilter>?, outActivities: MutableList<android.content.ComponentName>?, packageName: String?): Int =
        throw UnsupportedOperationException("IPackageManager.getPreferredActivities")

    override fun addPersistentPreferredActivity(filter: android.content.IntentFilter?, activity: android.content.ComponentName?, userId: Int) {
        throw UnsupportedOperationException("IPackageManager.addPersistentPreferredActivity")
    }

    override fun clearPackagePersistentPreferredActivities(packageName: String?, userId: Int) {
        throw UnsupportedOperationException("IPackageManager.clearPackagePersistentPreferredActivities")
    }

    override fun clearPersistentPreferredActivity(filter: android.content.IntentFilter?, userId: Int) {
        throw UnsupportedOperationException("IPackageManager.clearPersistentPreferredActivity")
    }

    override fun addCrossProfileIntentFilter(intentFilter: android.content.IntentFilter?, ownerPackage: String?, sourceUserId: Int, targetUserId: Int, flags: Int) {
        throw UnsupportedOperationException("IPackageManager.addCrossProfileIntentFilter")
    }

    override fun removeCrossProfileIntentFilter(intentFilter: android.content.IntentFilter?, ownerPackage: String?, sourceUserId: Int, targetUserId: Int, flags: Int): Boolean =
        throw UnsupportedOperationException("IPackageManager.removeCrossProfileIntentFilter")

    override fun clearCrossProfileIntentFilters(sourceUserId: Int, ownerPackage: String?) {
        throw UnsupportedOperationException("IPackageManager.clearCrossProfileIntentFilters")
    }

    override fun setDistractingPackageRestrictionsAsUser(packageNames: Array<String>?, restrictionFlags: Int, userId: Int): Array<String>? =
        throw UnsupportedOperationException("IPackageManager.setDistractingPackageRestrictionsAsUser")

    override fun setPackagesSuspendedAsUser(packageNames: Array<String>?, suspended: Boolean, appExtras: android.os.PersistableBundle?, launcherExtras: android.os.PersistableBundle?, dialogInfo: android.content.pm.SuspendDialogInfo?, flags: Int, suspendingPackage: String?, suspendingUserId: Int, targetUserId: Int): Array<String>? =
        throw UnsupportedOperationException("IPackageManager.setPackagesSuspendedAsUser")

    override fun getUnsuspendablePackagesForUser(packageNames: Array<String>?, userId: Int): Array<String>? =
        throw UnsupportedOperationException("IPackageManager.getUnsuspendablePackagesForUser")

    override fun isPackageSuspendedForUser(packageName: String?, userId: Int): Boolean =
        throw UnsupportedOperationException("IPackageManager.isPackageSuspendedForUser")

    override fun isPackageQuarantinedForUser(packageName: String?, userId: Int): Boolean =
        throw UnsupportedOperationException("IPackageManager.isPackageQuarantinedForUser")

    override fun isPackageStoppedForUser(packageName: String?, userId: Int): Boolean =
        throw UnsupportedOperationException("IPackageManager.isPackageStoppedForUser")

    override fun getSuspendedPackageAppExtras(packageName: String?, userId: Int): android.os.Bundle? =
        throw UnsupportedOperationException("IPackageManager.getSuspendedPackageAppExtras")

    override fun getSuspendingPackage(packageName: String?, userId: Int): String? =
        throw UnsupportedOperationException("IPackageManager.getSuspendingPackage")

    override fun getPreferredActivityBackup(userId: Int): ByteArray? =
        throw UnsupportedOperationException("IPackageManager.getPreferredActivityBackup")

    override fun restorePreferredActivities(backup: ByteArray?, userId: Int) {
        throw UnsupportedOperationException("IPackageManager.restorePreferredActivities")
    }

    override fun getDefaultAppsBackup(userId: Int): ByteArray? =
        throw UnsupportedOperationException("IPackageManager.getDefaultAppsBackup")

    override fun restoreDefaultApps(backup: ByteArray?, userId: Int) {
        throw UnsupportedOperationException("IPackageManager.restoreDefaultApps")
    }

    override fun getDomainVerificationBackup(userId: Int): ByteArray? =
        throw UnsupportedOperationException("IPackageManager.getDomainVerificationBackup")

    override fun restoreDomainVerification(backup: ByteArray?, userId: Int) {
        throw UnsupportedOperationException("IPackageManager.restoreDomainVerification")
    }

    override fun getHomeActivities(outHomeCandidates: MutableList<android.content.pm.ResolveInfo>?): android.content.ComponentName? =
        throw UnsupportedOperationException("IPackageManager.getHomeActivities")

    override fun setHomeActivity(className: android.content.ComponentName?, userId: Int) {
        throw UnsupportedOperationException("IPackageManager.setHomeActivity")
    }

    override fun overrideLabelAndIcon(componentName: android.content.ComponentName?, nonLocalizedLabel: String?, icon: Int, userId: Int) {
        throw UnsupportedOperationException("IPackageManager.overrideLabelAndIcon")
    }

    override fun restoreLabelAndIcon(componentName: android.content.ComponentName?, userId: Int) {
        throw UnsupportedOperationException("IPackageManager.restoreLabelAndIcon")
    }

    override fun setComponentEnabledSetting(componentName: android.content.ComponentName?, newState: Int, flags: Int, userId: Int, callingPackage: String?) {
        throw UnsupportedOperationException("IPackageManager.setComponentEnabledSetting")
    }

    override fun setComponentEnabledSettings(settings: MutableList<android.content.pm.PackageManager.ComponentEnabledSetting>?, userId: Int, callingPackage: String?) {
        throw UnsupportedOperationException("IPackageManager.setComponentEnabledSettings")
    }

    override fun getComponentEnabledSetting(componentName: android.content.ComponentName?, userId: Int): Int =
        throw UnsupportedOperationException("IPackageManager.getComponentEnabledSetting")

    override fun setApplicationEnabledSetting(packageName: String?, newState: Int, flags: Int, userId: Int, callingPackage: String?) {
        throw UnsupportedOperationException("IPackageManager.setApplicationEnabledSetting")
    }

    override fun getApplicationEnabledSetting(packageName: String?, userId: Int): Int =
        throw UnsupportedOperationException("IPackageManager.getApplicationEnabledSetting")

    override fun logAppProcessStartIfNeeded(packageName: String?, processName: String?, uid: Int, seinfo: String?, apkFile: String?, pid: Int) {
        throw UnsupportedOperationException("IPackageManager.logAppProcessStartIfNeeded")
    }

    override fun flushPackageRestrictionsAsUser(userId: Int) {
        throw UnsupportedOperationException("IPackageManager.flushPackageRestrictionsAsUser")
    }

    override fun setPackageStoppedState(packageName: String?, stopped: Boolean, userId: Int) {
        throw UnsupportedOperationException("IPackageManager.setPackageStoppedState")
    }

    override fun freeStorageAndNotify(volumeUuid: String?, freeStorageSize: Long, storageFlags: Int, observer: android.content.pm.IPackageDataObserver?) {
        throw UnsupportedOperationException("IPackageManager.freeStorageAndNotify")
    }

    override fun freeStorage(volumeUuid: String?, freeStorageSize: Long, storageFlags: Int, pi: android.content.IntentSender?) {
        throw UnsupportedOperationException("IPackageManager.freeStorage")
    }

    override fun deleteApplicationCacheFiles(packageName: String?, observer: android.content.pm.IPackageDataObserver?) {
        throw UnsupportedOperationException("IPackageManager.deleteApplicationCacheFiles")
    }

    override fun deleteApplicationCacheFilesAsUser(packageName: String?, userId: Int, observer: android.content.pm.IPackageDataObserver?) {
        throw UnsupportedOperationException("IPackageManager.deleteApplicationCacheFilesAsUser")
    }

    override fun clearApplicationUserData(packageName: String?, observer: android.content.pm.IPackageDataObserver?, userId: Int) {
        throw UnsupportedOperationException("IPackageManager.clearApplicationUserData")
    }

    override fun clearApplicationProfileData(packageName: String?) {
        throw UnsupportedOperationException("IPackageManager.clearApplicationProfileData")
    }

    override fun getPackageSizeInfo(packageName: String?, userHandle: Int, observer: android.content.pm.IPackageStatsObserver?) {
        throw UnsupportedOperationException("IPackageManager.getPackageSizeInfo")
    }

    override fun getSystemSharedLibraryNames(): Array<String>? =
        throw UnsupportedOperationException("IPackageManager.getSystemSharedLibraryNames")

    override fun getSystemSharedLibraryNamesAndPaths(): MutableMap<String, String>? =
        throw UnsupportedOperationException("IPackageManager.getSystemSharedLibraryNamesAndPaths")

    override fun getSystemAvailableFeatures(): android.content.pm.ParceledListSlice<*>? =
        throw UnsupportedOperationException("IPackageManager.getSystemAvailableFeatures")

    override fun hasSystemFeature(name: String?, version: Int): Boolean =
        throw UnsupportedOperationException("IPackageManager.hasSystemFeature")

    override fun getInitialNonStoppedSystemPackages(): MutableList<String>? =
        throw UnsupportedOperationException("IPackageManager.getInitialNonStoppedSystemPackages")

    override fun enterSafeMode() {
        throw UnsupportedOperationException("IPackageManager.enterSafeMode")
    }

    override fun isSafeMode(): Boolean =
        throw UnsupportedOperationException("IPackageManager.isSafeMode")

    override fun hasSystemUidErrors(): Boolean =
        throw UnsupportedOperationException("IPackageManager.hasSystemUidErrors")

    override fun notifyPackageUse(packageName: String?, reason: Int) {
        throw UnsupportedOperationException("IPackageManager.notifyPackageUse")
    }

    override fun notifyDexLoad(loadingPackageName: String?, classLoaderContextMap: MutableMap<String, String>?, loaderIsa: String?) {
        throw UnsupportedOperationException("IPackageManager.notifyDexLoad")
    }

    override fun registerDexModule(packageName: String?, dexModulePath: String?, isSharedModule: Boolean, callback: android.content.pm.IDexModuleRegisterCallback?) {
        throw UnsupportedOperationException("IPackageManager.registerDexModule")
    }

    override fun performDexOptMode(packageName: String?, checkProfiles: Boolean, targetCompilerFilter: String?, force: Boolean, bootComplete: Boolean, splitName: String?): Boolean =
        throw UnsupportedOperationException("IPackageManager.performDexOptMode")

    override fun performDexOptSecondary(packageName: String?, targetCompilerFilter: String?, force: Boolean): Boolean =
        throw UnsupportedOperationException("IPackageManager.performDexOptSecondary")

    override fun getMoveStatus(moveId: Int): Int =
        throw UnsupportedOperationException("IPackageManager.getMoveStatus")

    override fun registerMoveCallback(callback: android.content.pm.IPackageMoveObserver?) {
        throw UnsupportedOperationException("IPackageManager.registerMoveCallback")
    }

    override fun unregisterMoveCallback(callback: android.content.pm.IPackageMoveObserver?) {
        throw UnsupportedOperationException("IPackageManager.unregisterMoveCallback")
    }

    override fun movePackage(packageName: String?, volumeUuid: String?): Int =
        throw UnsupportedOperationException("IPackageManager.movePackage")

    override fun movePrimaryStorage(volumeUuid: String?): Int =
        throw UnsupportedOperationException("IPackageManager.movePrimaryStorage")

    override fun setInstallLocation(loc: Int): Boolean =
        throw UnsupportedOperationException("IPackageManager.setInstallLocation")

    override fun getInstallLocation(): Int =
        throw UnsupportedOperationException("IPackageManager.getInstallLocation")

    override fun installExistingPackageAsUser(packageName: String?, userId: Int, installFlags: Int, installReason: Int, whiteListedPermissions: MutableList<String>?): Int =
        throw UnsupportedOperationException("IPackageManager.installExistingPackageAsUser")

    override fun verifyPendingInstall(id: Int, verificationCode: Int) {
        throw UnsupportedOperationException("IPackageManager.verifyPendingInstall")
    }

    override fun extendVerificationTimeout(id: Int, verificationCodeAtTimeout: Int, millisecondsToDelay: Long) {
        throw UnsupportedOperationException("IPackageManager.extendVerificationTimeout")
    }

    override fun verifyIntentFilter(id: Int, verificationCode: Int, failedDomains: MutableList<String>?) {
        throw UnsupportedOperationException("IPackageManager.verifyIntentFilter")
    }

    override fun getIntentVerificationStatus(packageName: String?, userId: Int): Int =
        throw UnsupportedOperationException("IPackageManager.getIntentVerificationStatus")

    override fun updateIntentVerificationStatus(packageName: String?, status: Int, userId: Int): Boolean =
        throw UnsupportedOperationException("IPackageManager.updateIntentVerificationStatus")

    override fun getIntentFilterVerifications(packageName: String?): android.content.pm.ParceledListSlice<*>? =
        throw UnsupportedOperationException("IPackageManager.getIntentFilterVerifications")

    override fun getAllIntentFilters(packageName: String?): android.content.pm.ParceledListSlice<*>? =
        throw UnsupportedOperationException("IPackageManager.getAllIntentFilters")

    override fun getVerifierDeviceIdentity(): android.content.pm.VerifierDeviceIdentity? =
        throw UnsupportedOperationException("IPackageManager.getVerifierDeviceIdentity")

    override fun isFirstBoot(): Boolean =
        throw UnsupportedOperationException("IPackageManager.isFirstBoot")

    override fun isDeviceUpgrading(): Boolean =
        throw UnsupportedOperationException("IPackageManager.isDeviceUpgrading")

    override fun isStorageLow(): Boolean =
        throw UnsupportedOperationException("IPackageManager.isStorageLow")

    override fun setApplicationHiddenSettingAsUser(packageName: String?, hidden: Boolean, userId: Int): Boolean =
        throw UnsupportedOperationException("IPackageManager.setApplicationHiddenSettingAsUser")

    override fun getApplicationHiddenSettingAsUser(packageName: String?, userId: Int): Boolean =
        throw UnsupportedOperationException("IPackageManager.getApplicationHiddenSettingAsUser")

    override fun setSystemAppHiddenUntilInstalled(packageName: String?, hidden: Boolean) {
        throw UnsupportedOperationException("IPackageManager.setSystemAppHiddenUntilInstalled")
    }

    override fun setSystemAppInstallState(packageName: String?, installed: Boolean, userId: Int): Boolean =
        throw UnsupportedOperationException("IPackageManager.setSystemAppInstallState")

    override fun getPackageInstaller(): android.content.pm.IPackageInstaller? =
        throw UnsupportedOperationException("IPackageManager.getPackageInstaller")

    override fun setBlockUninstallForUser(packageName: String?, blockUninstall: Boolean, userId: Int): Boolean =
        throw UnsupportedOperationException("IPackageManager.setBlockUninstallForUser")

    override fun getBlockUninstallForUser(packageName: String?, userId: Int): Boolean =
        throw UnsupportedOperationException("IPackageManager.getBlockUninstallForUser")

    override fun getKeySetByAlias(packageName: String?, alias: String?): android.content.pm.KeySet? =
        throw UnsupportedOperationException("IPackageManager.getKeySetByAlias")

    override fun getSigningKeySet(packageName: String?): android.content.pm.KeySet? =
        throw UnsupportedOperationException("IPackageManager.getSigningKeySet")

    override fun isPackageSignedByKeySet(packageName: String?, ks: android.content.pm.KeySet?): Boolean =
        throw UnsupportedOperationException("IPackageManager.isPackageSignedByKeySet")

    override fun isPackageSignedByKeySetExactly(packageName: String?, ks: android.content.pm.KeySet?): Boolean =
        throw UnsupportedOperationException("IPackageManager.isPackageSignedByKeySetExactly")

    override fun getPermissionControllerPackageName(): String? =
        throw UnsupportedOperationException("IPackageManager.getPermissionControllerPackageName")

    override fun getSdkSandboxPackageName(): String? =
        throw UnsupportedOperationException("IPackageManager.getSdkSandboxPackageName")

    override fun getInstantApps(userId: Int): android.content.pm.ParceledListSlice<*>? =
        throw UnsupportedOperationException("IPackageManager.getInstantApps")

    override fun getInstantAppCookie(packageName: String?, userId: Int): ByteArray? =
        throw UnsupportedOperationException("IPackageManager.getInstantAppCookie")

    override fun setInstantAppCookie(packageName: String?, cookie: ByteArray?, userId: Int): Boolean =
        throw UnsupportedOperationException("IPackageManager.setInstantAppCookie")

    override fun getInstantAppIcon(packageName: String?, userId: Int): android.graphics.Bitmap? =
        throw UnsupportedOperationException("IPackageManager.getInstantAppIcon")

    override fun isInstantApp(packageName: String?, userId: Int): Boolean =
        throw UnsupportedOperationException("IPackageManager.isInstantApp")

    override fun setRequiredForSystemUser(packageName: String?, systemUserApp: Boolean): Boolean =
        throw UnsupportedOperationException("IPackageManager.setRequiredForSystemUser")

    override fun setUpdateAvailable(packageName: String?, updateAvaialble: Boolean) {
        throw UnsupportedOperationException("IPackageManager.setUpdateAvailable")
    }

    override fun getServicesSystemSharedLibraryPackageName(): String? =
        throw UnsupportedOperationException("IPackageManager.getServicesSystemSharedLibraryPackageName")

    override fun getSharedSystemSharedLibraryPackageName(): String? =
        throw UnsupportedOperationException("IPackageManager.getSharedSystemSharedLibraryPackageName")

    override fun getChangedPackages(sequenceNumber: Int, userId: Int): android.content.pm.ChangedPackages? =
        throw UnsupportedOperationException("IPackageManager.getChangedPackages")

    override fun isPackageDeviceAdminOnAnyUser(packageName: String?): Boolean =
        throw UnsupportedOperationException("IPackageManager.isPackageDeviceAdminOnAnyUser")

    override fun getInstallReason(packageName: String?, userId: Int): Int =
        throw UnsupportedOperationException("IPackageManager.getInstallReason")

    override fun getSharedLibraries(packageName: String?, flags: Long, userId: Int): android.content.pm.ParceledListSlice<*>? =
        throw UnsupportedOperationException("IPackageManager.getSharedLibraries")

    override fun getDeclaredSharedLibraries(packageName: String?, flags: Long, userId: Int): android.content.pm.ParceledListSlice<*>? =
        throw UnsupportedOperationException("IPackageManager.getDeclaredSharedLibraries")

    override fun canRequestPackageInstalls(packageName: String?, userId: Int): Boolean =
        throw UnsupportedOperationException("IPackageManager.canRequestPackageInstalls")

    override fun deletePreloadsFileCache() {
        throw UnsupportedOperationException("IPackageManager.deletePreloadsFileCache")
    }

    override fun getInstantAppResolverComponent(): android.content.ComponentName? =
        throw UnsupportedOperationException("IPackageManager.getInstantAppResolverComponent")

    override fun getInstantAppResolverSettingsComponent(): android.content.ComponentName? =
        throw UnsupportedOperationException("IPackageManager.getInstantAppResolverSettingsComponent")

    override fun getInstantAppInstallerComponent(): android.content.ComponentName? =
        throw UnsupportedOperationException("IPackageManager.getInstantAppInstallerComponent")

    override fun getInstantAppAndroidId(packageName: String?, userId: Int): String? =
        throw UnsupportedOperationException("IPackageManager.getInstantAppAndroidId")

    override fun getArtManager(): android.content.pm.dex.IArtManager? =
        throw UnsupportedOperationException("IPackageManager.getArtManager")

    override fun setHarmfulAppWarning(packageName: String?, warning: CharSequence?, userId: Int) {
        throw UnsupportedOperationException("IPackageManager.setHarmfulAppWarning")
    }

    override fun getHarmfulAppWarning(packageName: String?, userId: Int): CharSequence? =
        throw UnsupportedOperationException("IPackageManager.getHarmfulAppWarning")

    override fun hasSigningCertificate(packageName: String?, signingCertificate: ByteArray?, flags: Int): Boolean =
        throw UnsupportedOperationException("IPackageManager.hasSigningCertificate")

    override fun hasUidSigningCertificate(uid: Int, signingCertificate: ByteArray?, flags: Int): Boolean =
        throw UnsupportedOperationException("IPackageManager.hasUidSigningCertificate")

    override fun getDefaultTextClassifierPackageName(): String? =
        throw UnsupportedOperationException("IPackageManager.getDefaultTextClassifierPackageName")

    override fun getSystemTextClassifierPackageName(): String? =
        throw UnsupportedOperationException("IPackageManager.getSystemTextClassifierPackageName")

    override fun getAttentionServicePackageName(): String? =
        throw UnsupportedOperationException("IPackageManager.getAttentionServicePackageName")

    override fun getRotationResolverPackageName(): String? =
        throw UnsupportedOperationException("IPackageManager.getRotationResolverPackageName")

    override fun getWellbeingPackageName(): String? =
        throw UnsupportedOperationException("IPackageManager.getWellbeingPackageName")

    override fun getAppPredictionServicePackageName(): String? =
        throw UnsupportedOperationException("IPackageManager.getAppPredictionServicePackageName")

    override fun getSystemCaptionsServicePackageName(): String? =
        throw UnsupportedOperationException("IPackageManager.getSystemCaptionsServicePackageName")

    override fun getSetupWizardPackageName(): String? =
        throw UnsupportedOperationException("IPackageManager.getSetupWizardPackageName")

    override fun getIncidentReportApproverPackageName(): String? =
        throw UnsupportedOperationException("IPackageManager.getIncidentReportApproverPackageName")

    override fun isPackageStateProtected(packageName: String?, userId: Int): Boolean =
        throw UnsupportedOperationException("IPackageManager.isPackageStateProtected")

    override fun sendDeviceCustomizationReadyBroadcast() {
        throw UnsupportedOperationException("IPackageManager.sendDeviceCustomizationReadyBroadcast")
    }

    override fun getInstalledModules(flags: Int): MutableList<android.content.pm.ModuleInfo>? =
        throw UnsupportedOperationException("IPackageManager.getInstalledModules")

    override fun getModuleInfo(packageName: String?, flags: Int): android.content.pm.ModuleInfo? =
        throw UnsupportedOperationException("IPackageManager.getModuleInfo")

    override fun getRuntimePermissionsVersion(userId: Int): Int =
        throw UnsupportedOperationException("IPackageManager.getRuntimePermissionsVersion")

    override fun setRuntimePermissionsVersion(version: Int, userId: Int) {
        throw UnsupportedOperationException("IPackageManager.setRuntimePermissionsVersion")
    }

    override fun notifyPackagesReplacedReceived(packages: Array<String>?) {
        throw UnsupportedOperationException("IPackageManager.notifyPackagesReplacedReceived")
    }

    override fun requestPackageChecksums(packageName: String?, includeSplits: Boolean, optional: Int, required: Int, trustedInstallers: MutableList<*>?, onChecksumsReadyListener: android.content.pm.IOnChecksumsReadyListener?, userId: Int) {
        throw UnsupportedOperationException("IPackageManager.requestPackageChecksums")
    }

    override fun getLaunchIntentSenderForPackage(packageName: String?, callingPackage: String?, featureId: String?, userId: Int): android.content.IntentSender? =
        throw UnsupportedOperationException("IPackageManager.getLaunchIntentSenderForPackage")

    override fun getAppOpPermissionPackages(permissionName: String?, userId: Int): Array<String>? =
        throw UnsupportedOperationException("IPackageManager.getAppOpPermissionPackages")

    override fun getPermissionGroupInfo(name: String?, flags: Int): android.content.pm.PermissionGroupInfo? =
        throw UnsupportedOperationException("IPackageManager.getPermissionGroupInfo")

    override fun addPermission(info: android.content.pm.PermissionInfo?): Boolean =
        throw UnsupportedOperationException("IPackageManager.addPermission")

    override fun addPermissionAsync(info: android.content.pm.PermissionInfo?): Boolean =
        throw UnsupportedOperationException("IPackageManager.addPermissionAsync")

    override fun removePermission(name: String?) {
        throw UnsupportedOperationException("IPackageManager.removePermission")
    }

    override fun checkPermission(permName: String?, pkgName: String?, userId: Int): Int =
        throw UnsupportedOperationException("IPackageManager.checkPermission")

    override fun grantRuntimePermission(packageName: String?, permissionName: String?, userId: Int) {
        throw UnsupportedOperationException("IPackageManager.grantRuntimePermission")
    }

    override fun checkUidPermission(permName: String?, uid: Int): Int =
        throw UnsupportedOperationException("IPackageManager.checkUidPermission")

    override fun setMimeGroup(packageName: String?, group: String?, mimeTypes: MutableList<String>?) {
        throw UnsupportedOperationException("IPackageManager.setMimeGroup")
    }

    override fun getSplashScreenTheme(packageName: String?, userId: Int): String? =
        throw UnsupportedOperationException("IPackageManager.getSplashScreenTheme")

    override fun setSplashScreenTheme(packageName: String?, themeName: String?, userId: Int) {
        throw UnsupportedOperationException("IPackageManager.setSplashScreenTheme")
    }

    override fun getUserMinAspectRatio(packageName: String?, userId: Int): Int =
        throw UnsupportedOperationException("IPackageManager.getUserMinAspectRatio")

    override fun setUserMinAspectRatio(packageName: String?, userId: Int, aspectRatio: Int) {
        throw UnsupportedOperationException("IPackageManager.setUserMinAspectRatio")
    }

    override fun getMimeGroup(packageName: String?, group: String?): MutableList<String>? =
        throw UnsupportedOperationException("IPackageManager.getMimeGroup")

    override fun isAutoRevokeWhitelisted(packageName: String?): Boolean =
        throw UnsupportedOperationException("IPackageManager.isAutoRevokeWhitelisted")

    override fun makeProviderVisible(recipientAppId: Int, visibleAuthority: String?) {
        throw UnsupportedOperationException("IPackageManager.makeProviderVisible")
    }

    override fun makeUidVisible(recipientAppId: Int, visibleUid: Int) {
        throw UnsupportedOperationException("IPackageManager.makeUidVisible")
    }

    override fun getHoldLockToken(): android.os.IBinder? =
        throw UnsupportedOperationException("IPackageManager.getHoldLockToken")

    override fun holdLock(token: android.os.IBinder?, durationMs: Int) {
        throw UnsupportedOperationException("IPackageManager.holdLock")
    }

    override fun getPropertyAsUser(propertyName: String?, packageName: String?, className: String?, userId: Int): android.content.pm.PackageManager.Property? =
        throw UnsupportedOperationException("IPackageManager.getPropertyAsUser")

    override fun queryProperty(propertyName: String?, componentType: Int): android.content.pm.ParceledListSlice<*>? =
        throw UnsupportedOperationException("IPackageManager.queryProperty")

    override fun setKeepUninstalledPackages(packageList: MutableList<String>?) {
        throw UnsupportedOperationException("IPackageManager.setKeepUninstalledPackages")
    }

    override fun canPackageQuery(sourcePackageName: String?, targetPackageNames: Array<String>?, userId: Int): BooleanArray? =
        throw UnsupportedOperationException("IPackageManager.canPackageQuery")

    override fun waitForHandler(timeoutMillis: Long, forBackgroundHandler: Boolean): Boolean =
        throw UnsupportedOperationException("IPackageManager.waitForHandler")

    override fun registerPackageMonitorCallback(callback: android.os.IRemoteCallback?, userId: Int) {
        throw UnsupportedOperationException("IPackageManager.registerPackageMonitorCallback")
    }

    override fun unregisterPackageMonitorCallback(callback: android.os.IRemoteCallback?) {
        throw UnsupportedOperationException("IPackageManager.unregisterPackageMonitorCallback")
    }

    override fun getArchivedPackage(packageName: String?, userId: Int): android.content.pm.ArchivedPackageParcel? =
        throw UnsupportedOperationException("IPackageManager.getArchivedPackage")

    override fun getArchivedAppIcon(packageName: String?, user: android.os.UserHandle?, callingPackageName: String?): android.graphics.Bitmap? =
        throw UnsupportedOperationException("IPackageManager.getArchivedAppIcon")

    override fun isAppArchivable(packageName: String?, user: android.os.UserHandle?): Boolean =
        throw UnsupportedOperationException("IPackageManager.isAppArchivable")

    override fun getAppMetadataSource(packageName: String?, userId: Int): Int =
        throw UnsupportedOperationException("IPackageManager.getAppMetadataSource")

    override fun getDomainVerificationAgent(userId: Int): android.content.ComponentName? =
        throw UnsupportedOperationException("IPackageManager.getDomainVerificationAgent")

    override fun setPageSizeAppCompatFlagsSettingsOverride(packageName: String?, enabled: Boolean) {
        throw UnsupportedOperationException("IPackageManager.setPageSizeAppCompatFlagsSettingsOverride")
    }

    override fun isPageSizeCompatEnabled(packageName: String?): Boolean =
        throw UnsupportedOperationException("IPackageManager.isPageSizeCompatEnabled")

    override fun getPageSizeCompatWarningMessage(packageName: String?): String? =
        throw UnsupportedOperationException("IPackageManager.getPageSizeCompatWarningMessage")

    override fun getAllApexDirectories(): MutableList<String>? =
        throw UnsupportedOperationException("IPackageManager.getAllApexDirectories")

}
