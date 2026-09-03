@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every IStorageStatsManager method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedStorageStatsManager : android.app.usage.IStorageStatsManager.Stub() {
    override fun isQuotaSupported(volumeUuid: String?, callingPackage: String?): Boolean = throw UnsupportedOperationException("IStorageStatsManager.isQuotaSupported is not hosted")
    override fun isReservedSupported(volumeUuid: String?, callingPackage: String?): Boolean = throw UnsupportedOperationException("IStorageStatsManager.isReservedSupported is not hosted")
    override fun getTotalBytes(volumeUuid: String?, callingPackage: String?): Long = throw UnsupportedOperationException("IStorageStatsManager.getTotalBytes is not hosted")
    override fun getFreeBytes(volumeUuid: String?, callingPackage: String?): Long = throw UnsupportedOperationException("IStorageStatsManager.getFreeBytes is not hosted")
    override fun getCacheBytes(volumeUuid: String?, callingPackage: String?): Long = throw UnsupportedOperationException("IStorageStatsManager.getCacheBytes is not hosted")
    override fun getCacheQuotaBytes(volumeUuid: String?, uid: Int, callingPackage: String?): Long = throw UnsupportedOperationException("IStorageStatsManager.getCacheQuotaBytes is not hosted")
    override fun queryStatsForPackage(volumeUuid: String?, packageName: String?, userId: Int, callingPackage: String?): android.app.usage.StorageStats? = throw UnsupportedOperationException("IStorageStatsManager.queryStatsForPackage is not hosted")
    override fun queryArtManagedStats(packageName: String?, userId: Int, uid: Int): android.app.usage.StorageStats? = throw UnsupportedOperationException("IStorageStatsManager.queryArtManagedStats is not hosted")
    override fun queryStatsForUid(volumeUuid: String?, uid: Int, callingPackage: String?): android.app.usage.StorageStats? = throw UnsupportedOperationException("IStorageStatsManager.queryStatsForUid is not hosted")
    override fun queryStatsForUser(volumeUuid: String?, userId: Int, callingPackage: String?): android.app.usage.StorageStats? = throw UnsupportedOperationException("IStorageStatsManager.queryStatsForUser is not hosted")
    override fun queryExternalStatsForUser(volumeUuid: String?, userId: Int, callingPackage: String?): android.app.usage.ExternalStorageStats? = throw UnsupportedOperationException("IStorageStatsManager.queryExternalStatsForUser is not hosted")
    override fun queryCratesForPackage(volumeUuid: String?, packageName: String?, userId: Int, callingPackage: String?): android.content.pm.ParceledListSlice<*>? = throw UnsupportedOperationException("IStorageStatsManager.queryCratesForPackage is not hosted")
    override fun queryCratesForUid(volumeUuid: String?, uid: Int, callingPackage: String?): android.content.pm.ParceledListSlice<*>? = throw UnsupportedOperationException("IStorageStatsManager.queryCratesForUid is not hosted")
    override fun queryCratesForUser(volumeUuid: String?, userId: Int, callingPackage: String?): android.content.pm.ParceledListSlice<*>? = throw UnsupportedOperationException("IStorageStatsManager.queryCratesForUser is not hosted")
}
