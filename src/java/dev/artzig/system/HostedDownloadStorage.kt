package dev.artzig.system

import android.os.Environment

/** Storage operations for the original DownloadProvider on the hosted filesystem.
 * Its Helpers classifies storage using /storage volume prefixes; the host volume
 * lives below EXTERNAL_STORAGE. Classification translates strings only, while
 * access checks and file I/O keep real paths. File callbacks retain inode ownership. */
object HostedDownloadStorage {
    /** DownloadProvider.delete scans after unlinking; its openFile/update paths
     * already distinguish MEDIA_NOT_SCANNABLE entries. Such private downloads
     * without a MediaStore URI have no media index entry to remove. MediaProvider
     * is not hosted yet; retain its call for every potentially indexed file. */
    @JvmStatic
    fun scanDeletedFile(resolver: android.content.ContentResolver, file: java.io.File,
        mediaScanned: Int, mediaStoreUri: String?) {
        if (mediaScanned == android.provider.Downloads.Impl.MEDIA_NOT_SCANNABLE && mediaStoreUri == null) return
        android.provider.MediaStore.scanFile(resolver, file)
    }

    /** DownloadProvider.openFile updates size on the asynchronous close callback.
     * Its completed cache file can be renamed before that callback runs. Retain
     * an inode reference so the observed size belongs to the written file even
     * after a move or replacement of the original pathname. */
    @JvmStatic
    @Throws(java.io.IOException::class)
    fun openFile(file: java.io.File, mode: Int, handler: android.os.Handler,
        onClosed: java.util.function.LongConsumer): android.os.ParcelFileDescriptor {
        val observation = android.os.ParcelFileDescriptor.open(file, mode)
        val transferred = try { android.system.Os.dup(observation.fileDescriptor) }
        catch (e: android.system.ErrnoException) {
            observation.close()
            throw e.rethrowAsIOException()
        }
        try {
            return android.os.ParcelFileDescriptor.fromFd(transferred, handler) {
                observation.use { onClosed.accept(it.statSize) }
            }
        } catch (t: Throwable) {
            try { android.system.Os.close(transferred) } finally { observation.close() }
            throw t
        }
    }

    @JvmStatic
    fun forClassification(path: String): String {
        val root = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        return if (path == root || path.startsWith("$root/")) "/storage/emulated/0" + path.substring(root.length)
            else path
    }
}
