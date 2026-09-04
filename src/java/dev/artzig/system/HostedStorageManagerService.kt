package dev.artzig.system

import android.os.Binder
import android.os.Environment
import android.os.ParcelableException
import android.os.Process
import android.os.RemoteCallbackList
import android.os.UserHandle
import android.os.storage.DiskInfo
import android.os.storage.IStorageEventListener
import android.os.storage.IStorageManager
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.os.storage.VolumeInfo
import android.os.storage.VolumeRecord
import java.io.File
import java.io.IOException

/**
 * The session's single emulated volume on the stock [IStorageManager] Binder surface.
 *
 * Source note: this follows the query and validation boundary of AOSP
 * `StorageManagerService` (`getVolumeList`, `getVolumes`, `mkdirs`,
 * `getAllocatableBytes`, and CE-state queries). Android's vold, installd quota,
 * checkpoint, AppFuse, OBB, and disk-management backends do not exist on the host;
 * those methods deliberately remain on [unsupported] instead of returning
 * successful-looking defaults.
 *
 * The launcher exports `ARTZIG_DATA_DIR` and `EXTERNAL_STORAGE` beneath the
 * per-session root. The service never exposes or creates paths outside that root.
 */
class HostedStorageManagerService @JvmOverloads constructor(
    private val packageService: HostedPackageManagerService,
    private val dataRoot: File = Environment.getDataDirectory(),
    private val externalRoot: File = configuredExternalRoot(dataRoot),
) : IStorageManager.Stub(), IStorageManager by unsupported<IStorageManager>() {
    private val listeners = RemoteCallbackList<IStorageEventListener>()
    private val canonicalDataRoot = dataRoot.canonicalFile
    private val canonicalExternalRoot = externalRoot.canonicalFile

    @Volatile
    private var cloudMediaProvider: String? = null

    init {
        check(canonicalDataRoot.isDirectory || canonicalDataRoot.mkdirs()) {
            "cannot create hosted data root: $canonicalDataRoot"
        }
        check(canonicalExternalRoot.isDirectory || canonicalExternalRoot.mkdirs()) {
            "cannot create hosted external-storage root: $canonicalExternalRoot"
        }
        check(contains(canonicalDataRoot, canonicalExternalRoot)) {
            "external storage must be inside the hosted data root"
        }
    }

    override fun asBinder(): android.os.IBinder = this

    override fun registerListener(listener: IStorageEventListener?) {
        listeners.register(requireNotNull(listener) { "listener" })
    }

    override fun unregisterListener(listener: IStorageEventListener?) {
        if (listener != null) listeners.unregister(listener)
    }

    override fun getVolumeList(
        userId: Int,
        callingPackage: String?,
        flags: Int,
    ): Array<StorageVolume> {
        enforceCallingPackage(callingPackage)
        if (userId != UserHandle.USER_SYSTEM) return emptyArray()
        return arrayOf(primaryVolume(userId))
    }

    override fun getDisks(): Array<DiskInfo> = emptyArray()

    override fun getVolumes(flags: Int): Array<VolumeInfo> = arrayOf(internalVolume())

    override fun getVolumeRecords(flags: Int): Array<VolumeRecord> = emptyArray()

    override fun getPrimaryStorageUuid(): String? = StorageManager.UUID_PRIVATE_INTERNAL

    override fun isCeStorageUnlocked(userId: Int): Boolean = userId == UserHandle.USER_SYSTEM

    override fun getCacheQuotaBytes(volumeUuid: String?, uid: Int): Long {
        requireInternalVolume(volumeUuid)
        return DEFAULT_CACHE_QUOTA_BYTES
    }

    override fun getCacheSizeBytes(volumeUuid: String?, uid: Int): Long {
        requireInternalVolume(volumeUuid)
        val packages = packageService.getPackagesForUid(uid) ?: return 0L
        return packages.sumOf { packageName ->
            val dataDir = packageService
                .getApplicationInfo(packageName, 0L, UserHandle.USER_SYSTEM)
                ?.dataDir
                ?.let(::File)
                ?: return@sumOf 0L
            sizeOf(File(dataDir, "cache")) + sizeOf(File(dataDir, "code_cache"))
        }
    }

    override fun getAllocatableBytes(
        volumeUuid: String?,
        flags: Int,
        callingPackage: String?,
    ): Long {
        enforceCallingPackage(callingPackage)
        requireInternalVolume(volumeUuid)
        return canonicalDataRoot.usableSpace
    }

    override fun allocateBytes(
        volumeUuid: String?,
        bytes: Long,
        flags: Int,
        callingPackage: String?,
    ) {
        enforceCallingPackage(callingPackage)
        requireInternalVolume(volumeUuid)
        if (bytes < 0L) throw ParcelableException(IOException("negative allocation: $bytes"))
        if (bytes > canonicalDataRoot.usableSpace) {
            throw ParcelableException(IOException("requested $bytes bytes exceed available space"))
        }
        // StorageManagerService frees cache until this much space is available;
        // the host has no quota/cache daemon, and the condition is already true.
    }

    override fun mkdirs(callingPkg: String?, path: String?) {
        val packageName = requireNotNull(callingPkg) { "callingPkg" }
        enforceCallingPackage(packageName)
        val directory = requireAppExternalPath(packageName, requireNotNull(path) { "path" })
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IllegalStateException("failed to create $directory")
        }
    }

    override fun fixupAppDir(path: String?) {
        val directory = requireHostedPath(requireNotNull(path) { "path" })
        if (Binder.getCallingUid() != Process.SYSTEM_UID && Binder.getCallingUid() != 0) {
            val packages = callingPackages()
            if (packages.none { isAppExternalPath(it, directory) }) {
                throw SecurityException("path is not owned by the calling uid: $directory")
            }
        }
        // Host processes share the session uid and create files directly; there
        // is no sdcardfs/FUSE ownership layer to repair.
    }

    override fun getExternalStorageMountMode(uid: Int, packageName: String?): Int =
        StorageManager.MOUNT_MODE_EXTERNAL_DEFAULT

    override fun isAppIoBlocked(
        volumeUuid: String?,
        uid: Int,
        tid: Int,
        reason: Int,
    ): Boolean = false

    override fun setCloudMediaProvider(authority: String?) {
        cloudMediaProvider = authority
    }

    override fun getCloudMediaProvider(): String? = cloudMediaProvider

    override fun getInternalStorageBlockDeviceSize(): Long = canonicalDataRoot.totalSpace

    override fun getInternalStorageRemainingLifetime(): Int = -1

    override fun supportsCheckpoint(): Boolean = false

    private fun primaryVolume(userId: Int): StorageVolume =
        StorageVolume.Builder(
            "${VolumeInfo.ID_EMULATED_INTERNAL};$userId",
            canonicalExternalRoot,
            "Internal shared storage",
            UserHandle.of(userId),
            Environment.MEDIA_MOUNTED,
        )
            .setPrimary(true)
            .setRemovable(false)
            .setEmulated(true)
            .build()

    private fun internalVolume(): VolumeInfo =
        VolumeInfo(VolumeInfo.ID_PRIVATE_INTERNAL, VolumeInfo.TYPE_PRIVATE, null, null).apply {
            state = VolumeInfo.STATE_MOUNTED
            path = canonicalDataRoot.path
            internalPath = canonicalDataRoot.path
        }

    private fun requireInternalVolume(volumeUuid: String?) {
        if (volumeUuid != StorageManager.UUID_PRIVATE_INTERNAL) {
            throw ParcelableException(IOException("unknown hosted volume UUID: $volumeUuid"))
        }
    }

    private fun enforceCallingPackage(packageName: String?) {
        val uid = Binder.getCallingUid()
        if (uid == Process.SYSTEM_UID || uid == 0) return
        if (packageName == null || packageName !in callingPackages()) {
            throw SecurityException("uid $uid does not own package $packageName")
        }
    }

    private fun callingPackages(): Set<String> =
        packageService.getPackagesForUid(Binder.getCallingUid())?.toSet() ?: emptySet()

    private fun requireAppExternalPath(packageName: String, path: String): File {
        val directory = requireHostedPath(path)
        if (!isAppExternalPath(packageName, directory)) {
            throw SecurityException("path is outside $packageName external storage: $directory")
        }
        return directory
    }

    private fun isAppExternalPath(packageName: String, path: File): Boolean {
        val androidRoot = File(canonicalExternalRoot, "Android")
        return contains(File(androidRoot, "data/$packageName").canonicalFile, path) ||
            contains(File(androidRoot, "obb/$packageName").canonicalFile, path)
    }

    private fun requireHostedPath(path: String): File {
        val file = File(path)
        if (!file.isAbsolute) throw SecurityException("path must be absolute: $path")
        val canonical = file.canonicalFile
        if (!contains(canonicalDataRoot, canonical)) {
            throw SecurityException("path is outside hosted storage: $canonical")
        }
        return canonical
    }

    private fun sizeOf(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf(::sizeOf) ?: 0L
    }

    private companion object {
        const val DEFAULT_CACHE_QUOTA_BYTES = 64L * 1024L * 1024L

        fun configuredExternalRoot(dataRoot: File): File {
            val configured = System.getenv("EXTERNAL_STORAGE")
            return if (configured.isNullOrEmpty()) File(dataRoot, "external") else File(configured)
        }

        fun contains(root: File, candidate: File): Boolean =
            candidate == root || candidate.path.startsWith(root.path + File.separator)
    }
}
