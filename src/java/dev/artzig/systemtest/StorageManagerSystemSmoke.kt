package dev.artzig.systemtest

import android.content.Context
import android.os.Environment
import android.os.ServiceManager
import android.os.UserHandle
import android.os.storage.IStorageManager
import android.os.storage.StorageEventListener
import android.os.storage.StorageManager
import android.os.storage.VolumeInfo
import java.io.File
import java.io.IOException

/** Focused cross-process assertions for the stock StorageManager client and hosted mount service. */
internal object StorageManagerSystemSmoke {
    fun run(context: Context) {
        // AOSP StorageManager resolves its Binder transport by the historical
        // service-manager name "mount"; Context.STORAGE_SERVICE names only the
        // Context wrapper.
        val binder = checkNotNull(ServiceManager.getService("mount")) {
            "mount service is published"
        }
        val transport = checkNotNull(IStorageManager.Stub.asInterface(binder))
        check(transport.isCeStorageUnlocked(UserHandle.myUserId())) {
            "the single hosted user's CE storage is unlocked"
        }

        val storage = checkNotNull(context.getSystemService(StorageManager::class.java))
        val volumes = storage.storageVolumes
        check(volumes.size == 1) { "the host exposes exactly one shared-storage volume" }
        val primary = volumes.single()
        check(primary.isPrimary && primary.isEmulated && !primary.isRemovable) {
            "the hosted volume is primary, emulated, and fixed"
        }
        check(primary.state == Environment.MEDIA_MOUNTED)

        val internal = checkNotNull(storage.findVolumeById(VolumeInfo.ID_PRIVATE_INTERNAL))
        check(internal.type == VolumeInfo.TYPE_PRIVATE && internal.isMountedWritable) {
            "the private backing volume is mounted and writable"
        }
        val listener = object : StorageEventListener() {}
        storage.registerListener(listener)
        storage.unregisterListener(listener)

        val expectedRoot = File(checkNotNull(System.getenv("EXTERNAL_STORAGE"))).canonicalFile
        check(primary.directory?.canonicalFile == expectedRoot) {
            "the volume uses the launcher's per-session external-storage root"
        }
        val containingVolume = storage.getStorageVolume(File(expectedRoot, "Download/item"))
        check(
            containingVolume?.uuid == primary.uuid &&
                containingVolume?.directory?.canonicalFile == primary.directory?.canonicalFile,
        ) { "StorageManager resolves a path inside the primary volume" }
        check(storage.getStorageVolume(File(expectedRoot.parentFile, expectedRoot.name + "-sibling")) == null) {
            "volume containment does not accept a sibling with the same path prefix"
        }

        check(storage.getUuidForPath(expectedRoot) == StorageManager.UUID_DEFAULT)
        check(storage.getUuidForPath(android.os.Environment.getDataDirectory()) == StorageManager.UUID_DEFAULT)
        check(storage.getUuidForPath(android.os.Environment.getDataUserCeDirectory(null, 0)) == StorageManager.UUID_DEFAULT)
        check(storage.getUuidForPath(android.os.Environment.getDataUserDeDirectory(null, 0)) == StorageManager.UUID_DEFAULT)
        val allocatable = storage.getAllocatableBytes(StorageManager.UUID_DEFAULT)
        check(allocatable >= 0L && allocatable <= expectedRoot.usableSpace)

        val appDirectory = File(expectedRoot, "Android/data/${context.packageName}/files/storage-smoke")
        storage.mkdirs(appDirectory)
        check(appDirectory.isDirectory) { "StorageManager.mkdirs creates an app-owned external directory" }

        // StorageManagerService recognizes all app directory families. Creation
        // enforces caller ownership; fixup resolves the owner named in the path.
        for (family in listOf("media", "obb", "sandbox")) {
            val managed = File(expectedRoot, "Android/$family/${context.packageName}/storage-smoke")
            storage.mkdirs(managed)
            check(managed.isDirectory)
            storage.fixupAppDir(managed)
            check(managed.delete())
        }
        val foreignRepair = File(expectedRoot, "Android/data/android/files/storage-repair-smoke")
        check(!foreignRepair.exists())
        storage.fixupAppDir(foreignRepair)
        check(!foreignRepair.exists()) { "ownership repair does not create directories" }
        val invalidRepair = File(context.cacheDir, "storage-invalid-repair")
        storage.fixupAppDir(invalidRepair)
        check(!invalidRepair.exists()) { "invalid fixup paths are ignored" }

        val outside = File(expectedRoot, "Android/data/not.${context.packageName}/files/rejected")
        check(runCatching { storage.mkdirs(outside) }.exceptionOrNull() is SecurityException) {
            "StorageManager.mkdirs rejects another package's external directory"
        }

        testObb(context, storage, transport, expectedRoot)

        val proxyFailure = runCatching {
            storage.openProxyFileDescriptor(
                android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                object : android.os.ProxyFileDescriptorCallback() {
                    override fun onGetSize(): Long = 0L
                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int = 0
                    override fun onRelease() {}
                },
            )
        }.exceptionOrNull()
        // AppFuseMountScope.open wraps vold's refusal in AppFuseMountException,
        // which StorageManagerService rethrows as a parcelable IllegalStateException.
        check(proxyFailure is IllegalStateException && proxyFailure.message == "Failed to mount") {
            "the original client reports the absent AppFuse bridge: $proxyFailure"
        }
        check(storage.proxyFileDescriptorMountPointId == -1)
    }

    private fun testObb(context: Context, storage: StorageManager, transport: IStorageManager, root: File) {
        val noCallback = object : android.os.storage.OnObbStateChangeListener() {
            override fun onObbStateChange(path: String?, state: Int) { error("invalid OBB was queued") }
        }
        val missing = runCatching { storage.mountObb(File(root, "missing.obb").path, null, noCallback) }.exceptionOrNull()
        check(missing is IllegalArgumentException && missing.cause == null &&
            missing.message?.startsWith("OBB file does not exist:") == true)
        val file = File(root, "Android/obb/${context.packageName}/main.7.${context.packageName}.obb")
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        // Pinned androidfw ObbFile v1 footer, with an independent known payload.
        val name = context.packageName.toByteArray(Charsets.UTF_8)
        val fixture = java.nio.ByteBuffer.allocate(32 + name.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(1).putInt(7).putInt(0).putLong(0).putInt(name.size).put(name)
            .putInt(24 + name.size).putInt(0x01059983).array()
        try {
            file.writeBytes(fixture.copyOf(12))
            val truncated = runCatching { storage.mountObb(file.path, null, noCallback) }.exceptionOrNull()
            check(truncated is IllegalArgumentException && truncated.cause is IOException)
            file.writeBytes(fixture)
            val info = android.content.res.ObbScanner.getObbInfo(file.path)
            check(info.packageName == context.packageName && info.version == 7 && info.flags == 0)
            check(!storage.isObbMounted(file.path) && storage.getMountedObbPath(file.path) == null)

            // Use a callback-thread client so this system test need not pump the
            // application's main looper while waiting for asynchronous results.
            val thread = android.os.HandlerThread("obb-client-test").apply { start() }
            try {
                val client = StorageManager(context, thread.looper)
                val results = java.util.concurrent.LinkedBlockingQueue<Pair<String?, Int>>()
                val listener = object : android.os.storage.OnObbStateChangeListener() {
                    override fun onObbStateChange(path: String?, state: Int) { results.add(path to state) }
                }
                check(client.mountObb(file.path, null, listener))
                check(results.poll(5, java.util.concurrent.TimeUnit.SECONDS) ==
                    (file.path to android.os.storage.OnObbStateChangeListener.ERROR_COULD_NOT_MOUNT))
                // StorageManagerService.unmountObb logs an unknown mount and
                // queues no action: the listener hears nothing.
                check(client.unmountObb(file.path, false, listener))
                check(results.poll(1, java.util.concurrent.TimeUnit.SECONDS) == null)
                val token = object : android.os.storage.IObbActionListener.Stub() {
                    override fun onObbResult(path: String?, nonce: Int, status: Int) { results.add(path to status) }
                }
                // validateObbInfo rejects an OBB whose path names another package.
                info.packageName = "foreign.package"
                check(runCatching { transport.mountObb(file.path, file.canonicalPath, token, 1, info) }.exceptionOrNull() is SecurityException)
                check(results.isEmpty())
            } finally { thread.quitSafely(); thread.join(5000); check(!thread.isAlive) }
        } finally { check(file.delete()) }

    }
}
