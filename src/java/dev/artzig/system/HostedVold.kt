package dev.artzig.system

import android.os.Environment
import android.os.IVold
import android.os.IVoldListener
import android.os.IVoldMountCallback
import android.os.IVoldTaskListener
import android.os.ServiceSpecificException
import android.os.UserHandle
import android.os.storage.StorageManager
import android.system.OsConstants
import dev.artzig.ArtzigTestDiagnostics
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.Executors

/**
 * The vold daemon of a hosted session: the `IVold` Binder that stock
 * `StorageManagerService` connects to. Pinned system/vold owns disks, volume
 * state, user storage directories, encryption keys and OBB images. Here the
 * session has one internal private volume and one emulated volume per started
 * user, backed by plain directories the single host user already owns: there
 * are no removable disks, no file-based encryption and no FUSE, so key and
 * mount requests complete without a kernel operation, and OBB images, app
 * FUSE and storage moves are declined.
 *
 * Volume identities and events follow vold's VolumeManager and EmulatedVolume:
 * `emulated;<user>` is created on user start, mounted on request through the
 * mount callback, and destroyed on user stop. User 0's emulated volume is the
 * launcher's `EXTERNAL_STORAGE` root, which every hosted process already uses;
 * other users get `media/<user>` below the data root, as /data/media/<user>.
 */
class HostedVold : IVold.Stub(), IVold by unsupported<IVold>() {
    private val data: File get() = Environment.getDataDirectory()
    private val lock = Any()
    private var listener: IVoldListener? = null
    private val addedUsers = HashSet<Int>()
    private val startedUsers = HashSet<Int>()
    // init runs `vold --fscrypt init_user0` before the system server: user 0's
    // CE storage is unlocked when StorageManagerService connects and reads
    // getUnlockedUsers. Other users unlock through LockSettingsService.
    private val unlockedUsers = hashSetOf(UserHandle.USER_SYSTEM)
    private val mounted = HashSet<String>()
    // vold answers mount callbacks from its own Binder threads; the caller of
    // mount() blocks until the callback returns, as it does across processes.
    private val callbacks = Executors.newSingleThreadExecutor { Runnable -> Thread(Runnable, "vold-mount") }

    private fun enforceSystem() {
        if (android.os.Binder.getCallingUid() != android.os.Process.SYSTEM_UID) {
            throw SecurityException("vold is only available to the system server")
        }
    }

    override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
        if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) enforceSystem()
        return super.onTransact(code, data, reply, flags)
    }

    private fun declined(member: String, detail: String): ServiceSpecificException {
        ArtzigTestDiagnostics.unsupported("vold", member, detail)
        return ServiceSpecificException(OsConstants.ENOTSUP, detail)
    }

    private fun emulatedId(userId: Int) = "emulated;$userId"

    /** EmulatedVolume's mount, /storage/emulated: StorageManagerService derives
     * each user's directory as `<path>/<user>` (VolumeInfo.getPathForUser).
     * The session's mount view (src/binder/StorageView.zig) binds user 0's
     * directory over the launcher's external storage root before the system
     * server starts; vold's bind for another user has no host counterpart yet. */
    private val emulatedRoot = File("/storage/emulated")

    private fun emulatedUserDirectory(userId: Int): File {
        val directory = File(emulatedRoot, userId.toString())
        if (!directory.isDirectory) throw declined("mount", "$directory is not mounted in the session's storage view")
        return directory
    }

    private fun notify(action: (IVoldListener) -> Unit) {
        val target = synchronized(lock) { listener } ?: return
        try { action(target) } catch (e: android.os.RemoteException) { android.util.Slog.w(TAG, "listener lost", e) }
    }

    override fun setListener(listener: IVoldListener?) { synchronized(lock) { this.listener = listener } }
    override fun abortFuse() {}
    override fun monitor() {}
    override fun shutdown() {}

    /** VolumeManager::reset: forget users and recreate volumes from scratch. */
    override fun reset() {
        val gone = synchronized(lock) {
            val ids = startedUsers.map(::emulatedId)
            addedUsers.clear(); startedUsers.clear(); mounted.clear()
            ids
        }
        for (id in gone) notify { it.onVolumeDestroyed(id) }
    }

    override fun onUserAdded(userId: Int, userSerial: Int, sharesStorageWithUserId: Int) {
        synchronized(lock) { addedUsers.add(userId) }
    }

    override fun onUserRemoved(userId: Int) {
        onUserStopped(userId)
        synchronized(lock) { addedUsers.remove(userId) }
    }

    /** VolumeManager::onUserStarted and createEmulatedVolumesForUser. */
    override fun onUserStarted(userId: Int) {
        val created = synchronized(lock) { startedUsers.add(userId) }
        if (created) notify { it.onVolumeCreated(emulatedId(userId), IVold.VOLUME_TYPE_EMULATED, "", "", userId) }
    }

    override fun onUserStopped(userId: Int) {
        val destroyed = synchronized(lock) {
            mounted.remove(emulatedId(userId))
            startedUsers.remove(userId)
        }
        if (destroyed) {
            notify { it.onVolumeStateChanged(emulatedId(userId), IVold.VOLUME_STATE_UNMOUNTED, userId) }
            notify { it.onVolumeDestroyed(emulatedId(userId)) }
        }
    }

    override fun onSecureKeyguardStateChanged(isShowing: Boolean) {}

    /** EmulatedVolume::doMount: the FUSE session is the external storage
     * service's; the device descriptor it receives is a placeholder here. */
    override fun mount(volId: String, mountFlags: Int, mountUserId: Int, callback: IVoldMountCallback?) {
        val userId = synchronized(lock) { startedUsers.firstOrNull { emulatedId(it) == volId } }
            ?: throw ServiceSpecificException(OsConstants.ENOENT, "no volume $volId")
        emulatedUserDirectory(userId)
        val path = emulatedRoot.path
        notify { it.onVolumeStateChanged(volId, IVold.VOLUME_STATE_CHECKING, userId) }
        notify { it.onVolumePathChanged(volId, path) }
        // No FUSE: the volume is its own lower filesystem.
        notify { it.onVolumeInternalPathChanged(volId, path) }
        val accepted = if (callback == null) true else callbacks.submit<Boolean> {
            // The receiver closes the FUSE device descriptor. A remote callback
            // receives a Binder duplicate; a local stub takes this descriptor
            // itself, so it is closed here only when the callback is remote.
            val device = android.system.Os.open("/dev/null", OsConstants.O_RDWR, 0)
            try {
                callback.onVolumeChecking(device, path, path)
            } finally {
                if (callback.asBinder() !is android.os.Binder) android.system.Os.close(device)
            }
        }.get()
        if (!accepted) {
            notify { it.onVolumeStateChanged(volId, IVold.VOLUME_STATE_UNMOUNTABLE, userId) }
            throw ServiceSpecificException(OsConstants.EIO, "external storage service declined $volId")
        }
        synchronized(lock) { mounted.add(volId) }
        notify { it.onVolumeStateChanged(volId, IVold.VOLUME_STATE_MOUNTED, userId) }
    }

    override fun unmount(volId: String) {
        val userId = synchronized(lock) {
            if (!mounted.remove(volId)) return
            startedUsers.firstOrNull { emulatedId(it) == volId } ?: -1
        }
        notify { it.onVolumeStateChanged(volId, IVold.VOLUME_STATE_EJECTING, userId) }
        notify { it.onVolumeStateChanged(volId, IVold.VOLUME_STATE_UNMOUNTED, userId) }
    }

    override fun remountUid(uid: Int, remountMode: Int) {}
    override fun remountAppStorageDirs(uid: Int, pid: Int, packageNames: Array<String>?) {}
    override fun unmountAppStorageDirs(uid: Int, pid: Int, packageNames: Array<String>?) {}

    /** PrepareAppDirFromRoot: every component below the volume, owned by the
     * host user; the Android app UID has no Linux counterpart here. */
    override fun setupAppDir(path: String, appUid: Int) {
        val directory = File(path)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw ServiceSpecificException(OsConstants.EIO, "cannot create $path")
        }
    }

    override fun fixupAppDir(path: String, appUid: Int) {} // Ownership is already the host user's.
    override fun ensureAppDirsCreated(paths: Array<String>, appUid: Int) { for (path in paths) setupAppDir(path, appUid) }

    override fun createObb(sourcePath: String, ownerGid: Int): String =
        throw declined("createObb", "OBB images need a loop device and a kernel mount")
    override fun destroyObb(volId: String) {}

    private fun finish(listener: IVoldTaskListener?) {
        try { listener?.onFinished(0, android.os.PersistableBundle()) } catch (_: android.os.RemoteException) {}
    }
    override fun fstrim(fstrimFlags: Int, listener: IVoldTaskListener?) = finish(listener)
    override fun runIdleMaint(needGC: Boolean, listener: IVoldTaskListener?) = finish(listener)
    override fun abortIdleMaint(listener: IVoldTaskListener?) = finish(listener)
    override fun getStorageLifeTime(): Int = -1
    override fun setGCUrgentPace(neededSegments: Int, minSegmentThreshold: Int, dirtyReclaimRate: Float,
        reclaimWeight: Float, gcPeriod: Int, minGCSleepTime: Int, targetDirtyRatio: Int) {}
    override fun refreshLatestWrite() {}
    override fun getWriteAmount(): Int = 0

    override fun mountAppFuse(uid: Int, mountId: Int): FileDescriptor =
        throw declined("mountAppFuse", "App FUSE needs /dev/fuse")
    override fun unmountAppFuse(uid: Int, mountId: Int) {}
    override fun openAppFuseFile(uid: Int, mountId: Int, fileId: Int, flags: Int): FileDescriptor =
        throw declined("openAppFuseFile", "App FUSE needs /dev/fuse")

    // File-based encryption is absent: storage keys exist only as unlock state.
    override fun createUserStorageKeys(userId: Int, ephemeral: Boolean) { synchronized(lock) { unlockedUsers.add(userId) } }
    override fun destroyUserStorageKeys(userId: Int) { synchronized(lock) { unlockedUsers.remove(userId) } }
    override fun setCeStorageProtection(userId: Int, secret: ByteArray?) {}
    override fun getUnlockedUsers(): IntArray = synchronized(lock) { unlockedUsers.sorted().toIntArray() }
    override fun unlockCeStorage(userId: Int, secret: ByteArray?) { synchronized(lock) { unlockedUsers.add(userId) } }
    override fun lockCeStorage(userId: Int) { synchronized(lock) { unlockedUsers.remove(userId) } }

    /** fscrypt_prepare_user_storage followed by vold_prepare_subdirs "prepare", for
     * the internal volume. SELinux labels and per-uid ownership have no host
     * counterpart: every hosted process shares the session uid. */
    override fun prepareUserStorage(uuid: String?, userId: Int, storageFlags: Int) {
        if (uuid != null) throw declined("prepareUserStorage", "Adoptable storage volume $uuid is not hosted")
        try {
            for ((directory, mode) in userStorage(userId, storageFlags)) prepareDirectory(directory, mode)
        } catch (e: IOException) {
            throw ServiceSpecificException(OsConstants.EIO, e.message)
        }
    }

    /** vold destroy_subdirs then fscrypt_destroy_user_storage. */
    override fun destroyUserStorage(uuid: String?, userId: Int, storageFlags: Int) {
        if (uuid != null) return
        for ((directory, _) in userStorage(userId, storageFlags).asReversed()) {
            if (directory.exists() && !directory.deleteRecursively()) android.util.Slog.wtf(TAG, "Failed to destroy $directory")
        }
    }

    override fun prepareSandboxForApp(packageName: String, appId: Int, sandboxId: String, userId: Int) {}
    override fun destroySandboxForApp(packageName: String, sandboxId: String, userId: Int) {}

    override fun startCheckpoint(retry: Int) = throw declined("startCheckpoint", "No checkpointing filesystem")
    override fun needsCheckpoint(): Boolean = false
    override fun needsRollback(): Boolean = false
    override fun isCheckpointing(): Boolean = false
    override fun supportsCheckpoint(): Boolean = false
    override fun supportsBlockCheckpoint(): Boolean = false
    override fun supportsFileCheckpoint(): Boolean = false
    override fun resetCheckpoint() {}
    override fun markBootAttempt() {}
    override fun earlyBootEnded() {}
    override fun getStorageSize(): Long = data.totalSpace
    override fun getStorageRemainingLifetime(): Int = -1
    override fun incFsEnabled(): Boolean = false

    /** vold Utils BuildData*Path and prepare_subdirs, parents before children. */
    private fun userStorage(userId: Int, flags: Int): List<Pair<File, Int>> {
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
        val apexes = File(data, "misc/apexdata").listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
        return listOf(apexData to "711".toInt(8)) + apexes.orEmpty().map { File(apexData, it.name) to "771".toInt(8) }
    }

    /** fs_prepare_dir: create a missing directory, then apply its mode. */
    private fun prepareDirectory(directory: File, mode: Int) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Failed to prepare $directory")
        android.system.Os.chmod(directory.path, mode)
    }

    private companion object { const val TAG = "HostedVold" }
}
