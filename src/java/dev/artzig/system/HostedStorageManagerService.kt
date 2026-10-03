package dev.artzig.system

import android.content.res.ObbInfo
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.storage.IObbActionListener
import android.os.storage.OnObbStateChangeListener
import com.android.internal.os.AppFuseMount
import com.android.internal.os.BackgroundThread
import com.android.server.LocalServices
import android.os.Binder
import android.os.Environment
import android.os.ParcelableException
import android.os.Process
import android.os.RemoteCallbackList
import android.os.UserHandle
import android.os.storage.DiskInfo
import android.os.storage.IStorageEventListener
import android.os.storage.IStorageManager
import android.os.storage.ICeStorageLockEventListener
import android.os.storage.StorageManager
import android.os.storage.StorageManagerInternal
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
    private val packageService: android.content.pm.IPackageManager,
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
        adjustAllocateFlags(flags)
        requireInternalVolume(volumeUuid)
        return canonicalDataRoot.usableSpace
    }

    override fun allocateBytes(
        volumeUuid: String?,
        bytes: Long,
        flags: Int,
        callingPackage: String?,
    ) {
        adjustAllocateFlags(flags)
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

    override fun getExternalStorageMountMode(uid: Int, packageName: String?): Int {
        android.app.ActivityManager.checkComponentPermission(android.Manifest.permission.WRITE_MEDIA_STORAGE,
            Binder.getCallingUid(), -1, true).let {
            if (it != android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw SecurityException("getExternalStorageMountMode requires ${android.Manifest.permission.WRITE_MEDIA_STORAGE}")
        }
        return local.getExternalStorageMountMode(uid, packageName)
    }

    private val ceStoragePrepared = HashSet<Int>()
    private val uidsWithLegacyExternalStorage = HashSet<Int>()
    private val resetListeners = ArrayList<StorageManagerInternal.ResetListener>()
    private val cloudProviderListeners = java.util.concurrent.CopyOnWriteArraySet<StorageManagerInternal.CloudProviderChangeListener>()
    private val ceStorageLockListeners = java.util.concurrent.CopyOnWriteArrayList<ICeStorageLockEventListener>()
    private val appOps by lazy {
        com.android.internal.app.IAppOpsService.Stub.asInterface(android.os.ServiceManager.getService(android.content.Context.APP_OPS_SERVICE))
    }

    /** StorageManagerService.getProviderInfo, resolved per query: the authorities
     * are fixed system providers, and the host has no servicesReady snapshot. */
    private fun authorityAppId(authority: String): Int =
        LocalServices.getService(android.content.pm.PackageManagerInternal::class.java)
            ?.resolveContentProvider(authority, android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE.toLong() or
                android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE.toLong(), UserHandle.USER_SYSTEM, Process.SYSTEM_UID)
            ?.let { UserHandle.getAppId(it.applicationInfo.uid) } ?: -1

    /** StorageManagerService.getMountModeInternal. */
    private fun mountMode(uid: Int, requestedPackage: String?): Int {
        if (Process.isIsolated(uid) || Process.isSdkSandboxUid(uid)) return StorageManager.MOUNT_MODE_EXTERNAL_NONE
        val packages = packageService.getPackagesForUid(uid)
        if (packages.isNullOrEmpty()) return StorageManager.MOUNT_MODE_EXTERNAL_NONE
        val packageName = requestedPackage ?: packages[0]
        val instant = Binder.withCleanCallingIdentity<Boolean> {
            LocalServices.getService(android.content.pm.PackageManagerInternal::class.java)
                .isInstantApp(packageName, UserHandle.getUserId(uid))
        }
        if (instant) return StorageManager.MOUNT_MODE_EXTERNAL_NONE
        if (local.isExternalStorageService(uid)) return StorageManager.MOUNT_MODE_EXTERNAL_PASS_THROUGH
        val appId = UserHandle.getAppId(uid)
        if (authorityAppId(android.provider.Downloads.Impl.AUTHORITY) == appId ||
            authorityAppId(android.provider.DocumentsContract.EXTERNAL_STORAGE_PROVIDER_AUTHORITY) == appId) {
            return StorageManager.MOUNT_MODE_EXTERNAL_ANDROID_WRITABLE
        }
        val granted = android.content.pm.PackageManager.PERMISSION_GRANTED
        if (packageService.checkUidPermission(android.Manifest.permission.ACCESS_MTP, uid) == granted &&
            packageService.getApplicationInfo(packageName, 0L, UserHandle.getUserId(uid))?.isSignedWithPlatformKey == true) {
            return StorageManager.MOUNT_MODE_EXTERNAL_ANDROID_WRITABLE
        }
        val hasInstall = packageService.checkUidPermission(android.Manifest.permission.INSTALL_PACKAGES, uid) == granted
        val hasInstallOp = packages.any {
            appOps.checkOperation(android.app.AppOpsManager.OP_REQUEST_INSTALL_PACKAGES, uid, it) == android.app.AppOpsManager.MODE_ALLOWED
        }
        return if (hasInstall || hasInstallOp) StorageManager.MOUNT_MODE_EXTERNAL_INSTALLER else StorageManager.MOUNT_MODE_EXTERNAL_DEFAULT
    }

    /**
     * StorageManagerService.StorageManagerInternalImpl over the hosted volume.
     * No FUSE daemon mounts user storage and no vold exists to reset, fix up OBB
     * directories, or prepare a move target, so those paths keep the stock
     * not-mounted answers; fs-verity goes to installd as in the original.
     */
    val local: StorageManagerInternal = object : StorageManagerInternal() {
        override fun isFuseMounted(userId: Int): Boolean = false

        override fun prepareStorageDirs(userId: Int, packageList: MutableSet<String>?, processName: String?): Boolean {
            android.util.Slog.w(TAG, "User $userId is not unlocked yet so skip mounting obb")
            return false
        }

        override fun getExternalStorageMountMode(uid: Int, packageName: String?): Int = mountMode(uid, packageName)

        override fun hasExternalStorageAccess(uid: Int, packageName: String?): Boolean {
            val mode = appOps.checkOperation(android.app.AppOpsManager.OP_MANAGE_EXTERNAL_STORAGE, uid, packageName)
            if (mode == android.app.AppOpsManager.MODE_DEFAULT) {
                return packageService.checkUidPermission(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE, uid) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            return mode == android.app.AppOpsManager.MODE_ALLOWED
        }

        override fun addResetListener(listener: StorageManagerInternal.ResetListener) {
            synchronized(resetListeners) { resetListeners.add(listener) }
        }

        // H_RESET resets vold, which the host does not run; listeners are only
        // invoked with a connected vold.
        override fun resetUser(userId: Int) {}

        override fun onAppOpsChanged(code: Int, uid: Int, packageName: String?, mode: Int, previousMode: Int) {
            Binder.withCleanCallingIdentity {
                when (code) {
                    android.app.AppOpsManager.OP_REQUEST_INSTALL_PACKAGES ->
                        if (previousMode == android.app.AppOpsManager.MODE_ALLOWED && mode != android.app.AppOpsManager.MODE_ALLOWED) killAppForOpChange(code, uid)
                    android.app.AppOpsManager.OP_MANAGE_EXTERNAL_STORAGE ->
                        if (mode != android.app.AppOpsManager.MODE_ALLOWED) killAppForOpChange(code, uid)
                    android.app.AppOpsManager.OP_LEGACY_STORAGE -> synchronized(uidsWithLegacyExternalStorage) {
                        if (mode == android.app.AppOpsManager.MODE_ALLOWED) uidsWithLegacyExternalStorage.add(uid)
                        else uidsWithLegacyExternalStorage.remove(uid)
                    }
                }
            }
        }

        override fun hasLegacyExternalStorage(uid: Int): Boolean =
            synchronized(uidsWithLegacyExternalStorage) { uid in uidsWithLegacyExternalStorage }

        // vold.fixupAppDir repairs OBB ownership; host processes share the
        // session uid (see fixupAppDir).
        override fun prepareAppDataAfterInstall(packageName: String, uid: Int) {}

        override fun isExternalStorageService(uid: Int): Boolean =
            authorityAppId(android.provider.MediaStore.AUTHORITY) == UserHandle.getAppId(uid)

        // StorageSessionController.freeCache asks the bound ExternalStorageService;
        // without FUSE none is bound and no cache is held on its behalf.
        override fun freeCache(volumeUuid: String?, bytes: Long) {}

        override fun getPrimaryVolumeIds(): List<String> = getVolumes(0).filter { it.isPrimary }.map { it.id }

        override fun markCeStoragePrepared(userId: Int) {
            synchronized(ceStoragePrepared) { ceStoragePrepared.add(userId) }
        }

        override fun isCeStoragePrepared(userId: Int): Boolean = synchronized(ceStoragePrepared) { userId in ceStoragePrepared }

        override fun registerCloudProviderChangeListener(listener: StorageManagerInternal.CloudProviderChangeListener) {
            cloudProviderListeners.add(listener)
            BackgroundThread.getHandler().post {
                cloudMediaProvider?.let { listener.onCloudProviderChanged(UserHandle.USER_SYSTEM, it) }
            }
        }

        override fun prepareUserStorageForMove(fromVolumeUuid: String?, toVolumeUuid: String?, users: MutableList<android.content.pm.UserInfo>?) {
            throw dev.artzig.ArtzigTestDiagnostics.unsupported("StorageManagerInternal", "prepareUserStorageForMove",
                "Moving storage from $fromVolumeUuid to $toVolumeUuid needs vold; the host has one internal volume")
        }

        override fun createFsveritySetupAuthToken(authFd: ParcelFileDescriptor?, uid: Int): android.os.IInstalld.IFsveritySetupAuthToken =
            try { installd().createFsveritySetupAuthToken(authFd, uid) } catch (e: Exception) { throw IOException(e) }

        override fun enableFsverity(authToken: android.os.IInstalld.IFsveritySetupAuthToken?, filePath: String?, packageName: String?): Int =
            try { installd().enableFsverity(authToken, filePath, packageName) } catch (e: Exception) { throw IOException(e) }

        override fun registerStorageLockEventListener(listener: ICeStorageLockEventListener) {
            if (!ceStorageLockListeners.add(listener)) android.util.Slog.w(TAG, "Failed to register listener: $listener")
        }

        override fun unregisterStorageLockEventListener(listener: ICeStorageLockEventListener) {
            if (!ceStorageLockListeners.remove(listener)) android.util.Slog.w(TAG, "Unregistering $listener that was not registered")
        }
    }

    private fun installd(): android.os.IInstalld =
        android.os.IInstalld.Stub.asInterface(android.os.ServiceManager.getService("installd"))

    private fun killAppForOpChange(code: Int, uid: Int) {
        try {
            android.app.ActivityManager.getService().killUid(UserHandle.getAppId(uid), UserHandle.USER_ALL,
                "${android.app.AppOpsManager.opToName(code)} changed.")
        } catch (_: RemoteException) {
        }
    }

    override fun isAppIoBlocked(
        volumeUuid: String?,
        uid: Int,
        tid: Int,
        reason: Int,
    ): Boolean = false

    override fun setCloudMediaProvider(authority: String?) {
        cloudMediaProvider = authority
        // H_CLOUD_MEDIA_PROVIDER_CHANGED for the session's single user.
        BackgroundThread.getHandler().post {
            cloudProviderListeners.forEach { it.onCloudProviderChanged(UserHandle.USER_SYSTEM, authority) }
        }
    }

    override fun getCloudMediaProvider(): String? = cloudMediaProvider

    override fun getInternalStorageBlockDeviceSize(): Long = canonicalDataRoot.totalSpace

    override fun getInternalStorageRemainingLifetime(): Int = -1

    /** vold fscrypt_prepare_user_storage followed by vold_prepare_subdirs
     * "prepare", for the internal volume. File-based encryption policies,
     * SELinux labels and per-uid ownership have no host counterpart: every
     * hosted process shares the session uid. */
    override fun prepareUserStorage(volumeUuid: String?, userId: Int, flags: Int) {
        enforceStorageInternal("prepareUserStorage")
        if (volumeUuid != null) throw RuntimeException(dev.artzig.ArtzigTestDiagnostics.unsupported(
            "IStorageManager", "prepareUserStorage", "Adoptable storage volume $volumeUuid is not hosted"))
        try {
            for ((directory, mode) in userStorage(userId, flags)) prepareDirectory(directory, mode)
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    /** vold destroy_subdirs then fscrypt_destroy_user_storage; failures are
     * logged, as StorageManagerService.destroyUserStorage does. */
    override fun destroyUserStorage(volumeUuid: String?, userId: Int, flags: Int) {
        enforceStorageInternal("destroyUserStorage")
        if (volumeUuid != null) return
        for ((directory, _) in userStorage(userId, flags).asReversed()) {
            if (directory.exists() && !directory.deleteRecursively()) android.util.Slog.wtf(TAG, "Failed to destroy $directory")
        }
    }

    private fun enforceStorageInternal(operation: String) {
        if (android.app.ActivityManager.checkComponentPermission(android.Manifest.permission.STORAGE_INTERNAL,
                Binder.getCallingUid(), -1, true) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("$operation requires ${android.Manifest.permission.STORAGE_INTERNAL}")
        }
    }

    /** vold Utils BuildData*Path and prepare_subdirs, parents before children. */
    private fun userStorage(userId: Int, flags: Int): List<Pair<File, Int>> {
        val data = Environment.getDataDirectory()
        fun dir(path: String) = File(data, path)
        val result = ArrayList<Pair<File, Int>>()
        if (flags and StorageManager.FLAG_STORAGE_DE != 0) {
            val miscDe = dir("misc_de/$userId")
            val vendorDe = dir("vendor_de/$userId")
            result += listOf(dir("system/users/$userId") to "700".toInt(8), dir("misc/profiles/cur/$userId") to "771".toInt(8),
                dir("system_de/$userId") to "770".toInt(8), vendorDe to "771".toInt(8), miscDe to "1771".toInt(8),
                dir("user_de/$userId") to "771".toInt(8), File(miscDe, "sdksandbox") to "771".toInt(8))
            result += listOf("vold", "storaged", "rollback", "apexrollback").map { File(miscDe, it) to "700".toInt(8) }
            result += apexData(miscDe)
            result += listOf(File(vendorDe, "fpdata") to "700".toInt(8), File(vendorDe, "facedata") to "700".toInt(8))
        }
        if (flags and StorageManager.FLAG_STORAGE_CE != 0) {
            val systemCe = dir("system_ce/$userId")
            val vendorCe = dir("vendor_ce/$userId")
            val miscCe = dir("misc_ce/$userId")
            result += listOf(systemCe to "770".toInt(8), vendorCe to "771".toInt(8), dir("media/$userId") to "2770".toInt(8),
                miscCe to "1771".toInt(8), dir("user/$userId") to "771".toInt(8), File(miscCe, "sdksandbox") to "771".toInt(8))
            result += listOf("vold", "storaged", "rollback", "apexrollback").map { File(miscCe, it) to "700".toInt(8) }
            result += apexData(miscCe)
            result += listOf(File(miscCe, "checkin") to "770".toInt(8), File(systemCe, "backup") to "700".toInt(8),
                File(systemCe, "backup_stage") to "700".toInt(8), File(vendorCe, "facedata") to "700".toInt(8))
        }
        return result
    }

    /** prepare_apex_subdirs: one directory per APEX listed in /data/misc/apexdata. */
    private fun apexData(parent: File): List<Pair<File, Int>> {
        val apexData = File(parent, "apexdata")
        val apexes = File(Environment.getDataDirectory(), "misc/apexdata").listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
        return listOf(apexData to "711".toInt(8)) + apexes.orEmpty().map { File(apexData, it.name) to "771".toInt(8) }
    }

    /** fs_prepare_dir: create a missing directory, then apply its mode. */
    private fun prepareDirectory(directory: File, mode: Int) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Failed to prepare $directory")
        android.system.Os.chmod(directory.path, mode)
    }

    override fun supportsCheckpoint(): Boolean = false
    // StorageManagerService.needsCheckpoint asks vold; a filesystem without
    // checkpoint support never needs one (StagingManager restores staged sessions).
    override fun needsCheckpoint(): Boolean {
        if (android.app.ActivityManager.checkComponentPermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS,
                android.os.Binder.getCallingUid(), -1, true) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("needsCheckpoint requires ${android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS}")
        }
        return false
    }

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

    /** StorageManagerService.adjustAllocateFlags: aggressive allocation needs
     * ALLOCATE_AGGRESSIVE; callingPackage only selects camera-reserved space,
     * which this host does not reserve. The package manager allocates for
     * installs with the system context's package under the installer's uid. */
    private fun adjustAllocateFlags(flags: Int) {
        if (flags and StorageManager.FLAG_ALLOCATE_AGGRESSIVE != 0 &&
            android.app.ActivityManager.checkComponentPermission(android.Manifest.permission.ALLOCATE_AGGRESSIVE,
                Binder.getCallingUid(), -1, true) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("allocateBytes requires ${android.Manifest.permission.ALLOCATE_AGGRESSIVE}")
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
        const val TAG = "HostedStorageManager"
        const val DEFAULT_CACHE_QUOTA_BYTES = 64L * 1024L * 1024L

        fun configuredExternalRoot(dataRoot: File): File {
            val configured = System.getenv("EXTERNAL_STORAGE")
            return if (configured.isNullOrEmpty()) File(dataRoot, "external") else File(configured)
        }

        fun contains(root: File, candidate: File): Boolean =
            candidate == root || candidate.path.startsWith(root.path + File.separator)
    }
}
