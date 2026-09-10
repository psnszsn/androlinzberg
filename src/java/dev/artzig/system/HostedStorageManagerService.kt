package dev.artzig.system

import android.content.res.ObbInfo
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.storage.IObbActionListener
import android.os.storage.OnObbStateChangeListener
import com.android.internal.os.AppFuseMount
import com.android.internal.os.BackgroundThread
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
 * checkpoint, AppFuse, OBB, and disk-management backends do not exist on the host.
 * OBB and AppFuse use the original service failure contracts below; other absent
 * daemon operations remain on [unsupported].
 *
 * The launcher exports `ARTZIG_DATA_DIR` and `EXTERNAL_STORAGE` beneath the
 * persistent application root. The service never exposes or creates paths outside that root.
 */
class HostedStorageManagerService @JvmOverloads constructor(
    private val packageService: HostedPackageManagerService,
    private val dataRoot: File = System.getenv("ARTZIG_DATA_DIR")?.let(::File) ?: Environment.getDataDirectory(),
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

    // Source: StorageManagerService mount/openProxyFileDescriptor. An absent
    // bridge returns null; the original client owns IOException and retry logic.
    override fun mountProxyFileDescriptorBridge(): AppFuseMount? = null

    override fun openProxyFileDescriptor(mountId: Int, fileId: Int, mode: Int): ParcelFileDescriptor? = null

    // Source: validateObbInfo, MountObbAction and UnmountObbAction. Translate only
    // the session's storage root; retain path/header ownership checks and async
    // failure notification. No vold means no entry can enter the mounted state.
    override fun mountObb(rawPath: String?, canonicalPath: String?, token: IObbActionListener?, nonce: Int, obbInfo: ObbInfo?) {
        val raw = java.util.Objects.requireNonNull(rawPath, "rawPath cannot be null")!!
        java.util.Objects.requireNonNull(canonicalPath, "canonicalPath cannot be null")
        val callback = java.util.Objects.requireNonNull(token, "token cannot be null")!!
        val info = java.util.Objects.requireNonNull(obbInfo, "obbInfo cannot be null")!!
        val file = File(raw).canonicalFile
        val obbRoot = File(canonicalExternalRoot, "Android/obb")
        if (!contains(obbRoot, file) || UserHandle.getUserId(Binder.getCallingUid()) != UserHandle.USER_SYSTEM) {
            throw SecurityException("Invalid path to Obb file : $file")
        }
        val parts = file.relativeTo(obbRoot).path.split(File.separatorChar)
        if (parts.size < 2 || parts[0] != info.packageName) {
            throw SecurityException("OBB path does not match package ${info.packageName}: $file")
        }
        val status = if (Binder.getCallingUid() == Process.SYSTEM_UID || info.packageName in callingPackages()) {
            OnObbStateChangeListener.ERROR_COULD_NOT_MOUNT
        } else {
            OnObbStateChangeListener.ERROR_PERMISSION_DENIED
        }
        notifyObb(callback, raw, nonce, status)
    }

    override fun unmountObb(rawPath: String?, force: Boolean, token: IObbActionListener?, nonce: Int) {
        val raw = java.util.Objects.requireNonNull(rawPath, "rawPath cannot be null")!!
        if (token != null) notifyObb(token, raw, nonce, OnObbStateChangeListener.ERROR_NOT_MOUNTED)
    }

    override fun isObbMounted(rawPath: String?): Boolean {
        java.util.Objects.requireNonNull(rawPath, "rawPath cannot be null")
        return false
    }

    override fun getMountedObbPath(rawPath: String?): String? {
        java.util.Objects.requireNonNull(rawPath, "rawPath cannot be null")
        return null
    }

    private fun notifyObb(token: IObbActionListener, rawPath: String, nonce: Int, status: Int) {
        BackgroundThread.getHandler().post {
            try {
                token.onObbResult(rawPath, nonce, status)
            } catch (e: RemoteException) {
                android.util.Log.w("HostedStorageManager", "OBB listener went away", e)
            }
        }
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

    /** StorageManagerService.fixupAppDir resolves the path's package, without checking
     * caller ownership. Providers repair completed downloads on behalf of other apps.
     * Invalid/unknown paths are logged and ignored, as in the pinned service. */
    override fun fixupAppDir(path: String?) {
        val file = File(path ?: throw NullPointerException("path"))
        val directory = try { file.canonicalFile } catch (e: IOException) {
            android.util.Log.e("HostedStorageManager", "Failed to resolve app directory: $file", e)
            return
        }
        val packageName = if (file.isAbsolute) appExternalPackage(directory) else null
        if (packageName == null || packageService.getPackageUid(packageName, 0L, UserHandle.USER_SYSTEM) < 0) {
            android.util.Log.e("HostedStorageManager", "Invalid or unknown application directory: $directory")
            return
        }
        // Host processes share the session uid; no sdcardfs/FUSE ownership repair is needed.
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

    private fun isAppExternalPath(packageName: String, path: File): Boolean = appExternalPackage(path) == packageName

    private fun appExternalPackage(path: File): String? {
        if (!contains(canonicalExternalRoot, path)) return null
        val parts = path.relativeTo(canonicalExternalRoot).path.split(File.separatorChar)
        if (parts.size < 3 || !parts[0].equals("Android", true) ||
            listOf("data", "media", "obb", "sandbox").none { it.equals(parts[1], true) }) return null
        return parts[2].takeIf { it.isNotEmpty() }
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
