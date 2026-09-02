@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every IContentService method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedContentService : android.content.IContentService.Stub() {
    override fun unregisterContentObserver(observer: android.database.IContentObserver?) { throw UnsupportedOperationException("IContentService.unregisterContentObserver is not hosted") }
    override fun registerContentObserver(uri: android.net.Uri?, notifyForDescendants: Boolean, observer: android.database.IContentObserver?, userHandle: Int, targetSdkVersion: Int) { throw UnsupportedOperationException("IContentService.registerContentObserver is not hosted") }
    override fun notifyChange(uris: Array<android.net.Uri?>?, observer: android.database.IContentObserver?, observerWantsSelfNotifications: Boolean, flags: Int, userHandle: Int, targetSdkVersion: Int, callingPackage: String?) { throw UnsupportedOperationException("IContentService.notifyChange is not hosted") }
    override fun requestSync(account: android.accounts.Account?, authority: String?, extras: android.os.Bundle?, callingPackage: String?) { throw UnsupportedOperationException("IContentService.requestSync is not hosted") }
    override fun sync(request: android.content.SyncRequest?, callingPackage: String?) { throw UnsupportedOperationException("IContentService.sync is not hosted") }
    override fun syncAsUser(request: android.content.SyncRequest?, userId: Int, callingPackage: String?) { throw UnsupportedOperationException("IContentService.syncAsUser is not hosted") }
    override fun cancelSync(account: android.accounts.Account?, authority: String?, cname: android.content.ComponentName?) { throw UnsupportedOperationException("IContentService.cancelSync is not hosted") }
    override fun cancelSyncAsUser(account: android.accounts.Account?, authority: String?, cname: android.content.ComponentName?, userId: Int) { throw UnsupportedOperationException("IContentService.cancelSyncAsUser is not hosted") }
    override fun cancelRequest(request: android.content.SyncRequest?) { throw UnsupportedOperationException("IContentService.cancelRequest is not hosted") }
    override fun getSyncAutomatically(account: android.accounts.Account?, providerName: String?): Boolean = throw UnsupportedOperationException("IContentService.getSyncAutomatically is not hosted")
    override fun getSyncAutomaticallyAsUser(account: android.accounts.Account?, providerName: String?, userId: Int): Boolean = throw UnsupportedOperationException("IContentService.getSyncAutomaticallyAsUser is not hosted")
    override fun setSyncAutomatically(account: android.accounts.Account?, providerName: String?, sync: Boolean) { throw UnsupportedOperationException("IContentService.setSyncAutomatically is not hosted") }
    override fun setSyncAutomaticallyAsUser(account: android.accounts.Account?, providerName: String?, sync: Boolean, userId: Int) { throw UnsupportedOperationException("IContentService.setSyncAutomaticallyAsUser is not hosted") }
    override fun getPeriodicSyncs(account: android.accounts.Account?, providerName: String?, cname: android.content.ComponentName?): MutableList<android.content.PeriodicSync?>? = throw UnsupportedOperationException("IContentService.getPeriodicSyncs is not hosted")
    override fun addPeriodicSync(account: android.accounts.Account?, providerName: String?, extras: android.os.Bundle?, pollFrequency: Long) { throw UnsupportedOperationException("IContentService.addPeriodicSync is not hosted") }
    override fun removePeriodicSync(account: android.accounts.Account?, providerName: String?, extras: android.os.Bundle?) { throw UnsupportedOperationException("IContentService.removePeriodicSync is not hosted") }
    override fun getIsSyncable(account: android.accounts.Account?, providerName: String?): Int = throw UnsupportedOperationException("IContentService.getIsSyncable is not hosted")
    override fun getIsSyncableAsUser(account: android.accounts.Account?, providerName: String?, userId: Int): Int = throw UnsupportedOperationException("IContentService.getIsSyncableAsUser is not hosted")
    override fun setIsSyncable(account: android.accounts.Account?, providerName: String?, syncable: Int) { throw UnsupportedOperationException("IContentService.setIsSyncable is not hosted") }
    override fun setIsSyncableAsUser(account: android.accounts.Account?, providerName: String?, syncable: Int, userId: Int) { throw UnsupportedOperationException("IContentService.setIsSyncableAsUser is not hosted") }
    override fun setMasterSyncAutomatically(flag: Boolean) { throw UnsupportedOperationException("IContentService.setMasterSyncAutomatically is not hosted") }
    override fun setMasterSyncAutomaticallyAsUser(flag: Boolean, userId: Int) { throw UnsupportedOperationException("IContentService.setMasterSyncAutomaticallyAsUser is not hosted") }
    override fun getMasterSyncAutomatically(): Boolean = throw UnsupportedOperationException("IContentService.getMasterSyncAutomatically is not hosted")
    override fun getMasterSyncAutomaticallyAsUser(userId: Int): Boolean = throw UnsupportedOperationException("IContentService.getMasterSyncAutomaticallyAsUser is not hosted")
    override fun getCurrentSyncs(): MutableList<android.content.SyncInfo?>? = throw UnsupportedOperationException("IContentService.getCurrentSyncs is not hosted")
    override fun getCurrentSyncsAsUser(userId: Int): MutableList<android.content.SyncInfo?>? = throw UnsupportedOperationException("IContentService.getCurrentSyncsAsUser is not hosted")
    override fun getSyncAdapterTypes(): Array<android.content.SyncAdapterType?>? = throw UnsupportedOperationException("IContentService.getSyncAdapterTypes is not hosted")
    override fun getSyncAdapterTypesAsUser(userId: Int): Array<android.content.SyncAdapterType?>? = throw UnsupportedOperationException("IContentService.getSyncAdapterTypesAsUser is not hosted")
    override fun getSyncAdapterPackagesForAuthorityAsUser(authority: String?, userId: Int): Array<String?>? = throw UnsupportedOperationException("IContentService.getSyncAdapterPackagesForAuthorityAsUser is not hosted")
    override fun getSyncAdapterPackageAsUser(accountType: String?, authority: String?, userId: Int): String? = throw UnsupportedOperationException("IContentService.getSyncAdapterPackageAsUser is not hosted")
    override fun isSyncActive(account: android.accounts.Account?, authority: String?, cname: android.content.ComponentName?): Boolean = throw UnsupportedOperationException("IContentService.isSyncActive is not hosted")
    override fun getSyncStatus(account: android.accounts.Account?, authority: String?, cname: android.content.ComponentName?): android.content.SyncStatusInfo? = throw UnsupportedOperationException("IContentService.getSyncStatus is not hosted")
    override fun getSyncStatusAsUser(account: android.accounts.Account?, authority: String?, cname: android.content.ComponentName?, userId: Int): android.content.SyncStatusInfo? = throw UnsupportedOperationException("IContentService.getSyncStatusAsUser is not hosted")
    override fun isSyncPending(account: android.accounts.Account?, authority: String?, cname: android.content.ComponentName?): Boolean = throw UnsupportedOperationException("IContentService.isSyncPending is not hosted")
    override fun isSyncPendingAsUser(account: android.accounts.Account?, authority: String?, cname: android.content.ComponentName?, userId: Int): Boolean = throw UnsupportedOperationException("IContentService.isSyncPendingAsUser is not hosted")
    override fun addStatusChangeListener(mask: Int, callback: android.content.ISyncStatusObserver?) { throw UnsupportedOperationException("IContentService.addStatusChangeListener is not hosted") }
    override fun removeStatusChangeListener(callback: android.content.ISyncStatusObserver?) { throw UnsupportedOperationException("IContentService.removeStatusChangeListener is not hosted") }
    override fun putCache(packageName: String?, key: android.net.Uri?, value: android.os.Bundle?, userId: Int) { throw UnsupportedOperationException("IContentService.putCache is not hosted") }
    override fun getCache(packageName: String?, key: android.net.Uri?, userId: Int): android.os.Bundle? = throw UnsupportedOperationException("IContentService.getCache is not hosted")
    override fun resetTodayStats() { throw UnsupportedOperationException("IContentService.resetTodayStats is not hosted") }
    override fun onDbCorruption(tag: String?, message: String?, stacktrace: String?) { throw UnsupportedOperationException("IContentService.onDbCorruption is not hosted") }
}
