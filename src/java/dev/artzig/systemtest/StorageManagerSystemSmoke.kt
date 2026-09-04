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
        val allocatable = storage.getAllocatableBytes(StorageManager.UUID_DEFAULT)
        check(allocatable >= 0L && allocatable <= expectedRoot.usableSpace)

        val appDirectory = File(expectedRoot, "Android/data/${context.packageName}/files/storage-smoke")
        storage.mkdirs(appDirectory)
        check(appDirectory.isDirectory) { "StorageManager.mkdirs creates an app-owned external directory" }

        val outside = File(expectedRoot, "Android/data/not.${context.packageName}/files/rejected")
        check(runCatching { storage.mkdirs(outside) }.exceptionOrNull() is SecurityException) {
            "StorageManager.mkdirs rejects another package's external directory"
        }

        check(!storage.mountObb(File(expectedRoot, "missing.obb").path, null, object :
            android.os.storage.OnObbStateChangeListener() {})) {
            "OBB mounting reports that no vold-backed operation was queued"
        }

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
        check(proxyFailure is IOException) { "AppFuse proxy descriptors fail explicitly on the host" }
    }
}
