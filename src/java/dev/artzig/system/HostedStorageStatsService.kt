package dev.artzig.system

import android.app.usage.ExternalStorageStats
import android.app.usage.StorageStats
import android.content.pm.ParceledListSlice
import android.os.Binder
import android.os.Environment
import android.os.Process
import android.os.UserHandle
import java.io.File

/**
 * The session's storage stats over the real `IStorageStatsManager` surface,
 * published as `Context.STORAGE_STATS_SERVICE`.
 *
 * Source note: `com.android.server.usage.StorageStatsService` (`getTotalBytes`,
 * `getFreeBytes`, `queryStatsForPackage`/`Uid`/`User` and the caller checks) with
 * the installd quota walk replaced by the host filesystem: totals and free space
 * come from the data root's file store, a package's code bytes are its APK and
 * its data bytes the per-package data directory (cache and code_cache count as
 * cache), exactly the directories the hosted package manager assigns. Quota and
 * reserved space are not supported, as on a device without project quotas.
 */
class HostedStorageStatsService : UnsupportedStorageStatsManager() {
    @Volatile var packageService: HostedPackageManagerService? = null

    private fun enforceCallerIsSystemOrSameApp(packageName: String?, callingPackage: String?) {
        val uid = Binder.getCallingUid()
        if (uid == Process.SYSTEM_UID || uid == 0) return
        val packages = packageService?.getPackagesForUid(uid) ?: return
        if (packages.isNotEmpty() && !packages.contains(callingPackage)) {
            throw SecurityException("Calling uid $uid gave package $callingPackage which is owned by another uid")
        }
        if (packageName != null && packages.isNotEmpty() && !packages.contains(packageName)) {
            // StorageStatsService.enforceStatsPermission: other packages need PACKAGE_USAGE_STATS.
            throw SecurityException("Caller $callingPackage may not query stats of $packageName")
        }
    }

    private fun dataRoot(): File = Environment.getDataDirectory()

    @Suppress("UNCHECKED_CAST")
    private fun installedApplications(): List<android.content.pm.ApplicationInfo> =
        (packageService?.getInstalledApplications(0L, UserHandle.USER_SYSTEM)?.list as? List<android.content.pm.ApplicationInfo>) ?: emptyList()

    override fun isQuotaSupported(volumeUuid: String?, callingPackage: String?): Boolean = false
    override fun isReservedSupported(volumeUuid: String?, callingPackage: String?): Boolean = false
    override fun getTotalBytes(volumeUuid: String?, callingPackage: String?): Long = dataRoot().totalSpace
    override fun getFreeBytes(volumeUuid: String?, callingPackage: String?): Long = dataRoot().usableSpace
    override fun getCacheBytes(volumeUuid: String?, callingPackage: String?): Long {
        return installedApplications().sumOf { cacheBytesOf(it.dataDir) }
    }
    /** StorageStatsService.getCacheQuotaBytes: 64 MiB when no cache quota calculator ran. */
    override fun getCacheQuotaBytes(volumeUuid: String?, uid: Int, callingPackage: String?): Long = 64L * 1024 * 1024

    override fun queryStatsForPackage(volumeUuid: String?, packageName: String?, userId: Int, callingPackage: String?): StorageStats? {
        enforceCallerIsSystemOrSameApp(packageName, callingPackage)
        val info = packageService?.getApplicationInfo(packageName, 0L, UserHandle.USER_SYSTEM)
            ?: throw android.os.ParcelableException(android.content.pm.PackageManager.NameNotFoundException(packageName))
        return statsOf(listOf(info))
    }

    override fun queryArtManagedStats(packageName: String?, userId: Int, uid: Int): StorageStats? = StorageStats()

    override fun queryStatsForUid(volumeUuid: String?, uid: Int, callingPackage: String?): StorageStats? {
        enforceCallerIsSystemOrSameApp(null, callingPackage)
        val packages = packageService?.getPackagesForUid(uid) ?: arrayOf()
        return statsOf(packages.mapNotNull { packageService?.getApplicationInfo(it, 0L, UserHandle.USER_SYSTEM) })
    }

    override fun queryStatsForUser(volumeUuid: String?, userId: Int, callingPackage: String?): StorageStats? {
        enforceCallerIsSystemOrSameApp(null, callingPackage)
        return statsOf(installedApplications())
    }

    override fun queryExternalStatsForUser(volumeUuid: String?, userId: Int, callingPackage: String?): ExternalStorageStats? = ExternalStorageStats()
    override fun queryCratesForPackage(volumeUuid: String?, packageName: String?, userId: Int, callingPackage: String?): ParceledListSlice<*>? = ParceledListSlice(emptyList<android.os.storage.CrateInfo>())
    override fun queryCratesForUid(volumeUuid: String?, uid: Int, callingPackage: String?): ParceledListSlice<*>? = ParceledListSlice(emptyList<android.os.storage.CrateInfo>())
    override fun queryCratesForUser(volumeUuid: String?, userId: Int, callingPackage: String?): ParceledListSlice<*>? = ParceledListSlice(emptyList<android.os.storage.CrateInfo>())

    private fun statsOf(infos: List<android.content.pm.ApplicationInfo>): StorageStats {
        val stats = StorageStats()
        for (info in infos) {
            val apk = info.sourceDir?.let { File(it) }
            if (apk != null && apk.isFile) {
                stats.codeBytes += apk.length()
                stats.apkBytes += apk.length()
            }
            val data = info.dataDir?.let { File(it) } ?: continue
            val cache = cacheBytesOf(info.dataDir)
            stats.dataBytes += sizeOf(data)
            stats.cacheBytes += cache
        }
        return stats
    }

    private fun cacheBytesOf(dataDir: String?): Long {
        dataDir ?: return 0
        return sizeOf(File(dataDir, "cache")) + sizeOf(File(dataDir, "code_cache"))
    }

    private fun sizeOf(file: File): Long {
        if (!file.exists()) return 0
        if (file.isFile) return file.length()
        var total = 0L
        val children = file.listFiles() ?: return 0
        for (child in children) total += sizeOf(child)
        return total
    }
}
