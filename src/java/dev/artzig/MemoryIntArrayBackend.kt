package dev.artzig

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.IOException

/**
 * Linux backing for android_util_MemoryIntArray.cpp's create/open operations.
 * The stock libcutils host ashmem implementation cannot restrict future writable
 * mappings. Use sealed memfds and libcore Posix here; stock JNI retains atomic
 * get/set, size validation, unmapping, and descriptor closure.
 */
object MemoryIntArrayBackend {
    private const val MFD_CLOEXEC = 1
    private const val MFD_ALLOW_SEALING = 2
    private const val F_ADD_SEALS = 1033
    private const val F_GET_SEALS = 1034
    private const val SIZE_SEALS = 2 or 4 // F_SEAL_SHRINK | F_SEAL_GROW
    private const val F_SEAL_FUTURE_WRITE = 16
    private const val INT_BYTES = 4 // Pinned Android ABI: sizeof(std::atomic_int).

    @JvmStatic fun create(name: String?, size: Int): Int {
        if (name == null) throw IOException("bad name")
        if (size <= 0) throw IOException("bad size")
        var descriptor: FileDescriptor? = null
        try {
            descriptor = Os.memfd_create(name, MFD_CLOEXEC or MFD_ALLOW_SEALING)
            Os.ftruncate(descriptor, size.toLong() * INT_BYTES)
            // Freeze size before publishing the FD, preventing the mapping/size
            // race checked by the AOSP ashmem implementation.
            Os.fcntlInt(descriptor, F_ADD_SEALS, SIZE_SEALS)
            return descriptor.`getInt$`()
        } catch (failure: ErrnoException) {
            descriptor?.let { closeAfterFailure(it) }
            throw IOException("ashmem creation failed", failure)
        }
    }

    @JvmStatic fun open(rawFd: Int, owner: Boolean): Long {
        if (rawFd < 0) throw IOException("bad file descriptor")
        val descriptor = FileDescriptor().apply { `setInt$`(rawFd) }
        var address = 0L
        var byteCount = 0L
        try {
            val seals = Os.fcntlInt(descriptor, F_GET_SEALS, 0)
            if (seals and SIZE_SEALS != SIZE_SEALS ||
                (!owner && seals and F_SEAL_FUTURE_WRITE == 0)) {
                throw IOException("bad file descriptor")
            }
            byteCount = Os.fstat(descriptor).st_size
            if (byteCount <= 0 || byteCount > Int.MAX_VALUE || byteCount % INT_BYTES != 0L) {
                throw IOException("bad ashmem size")
            }
            address = Os.mmap(0, byteCount,
                OsConstants.PROT_READ or (if (owner) OsConstants.PROT_WRITE else 0),
                OsConstants.MAP_SHARED, descriptor, 0)
            // A fresh memfd is zero-filled, matching C++20 value initialization
            // of the stock std::atomic_int array. Existing owner mappings keep
            // write access after sealing, while every future mapping is read-only.
            if (owner) Os.fcntlInt(descriptor, F_ADD_SEALS, F_SEAL_FUTURE_WRITE)
            return address
        } catch (failure: Exception) {
            if (address != 0L) {
                try { Os.munmap(address, byteCount) } catch (_: ErrnoException) {}
            }
            // Both stock constructors transfer the descriptor before nativeOpen;
            // no successfully constructed Java owner exists on this failure path.
            closeAfterFailure(descriptor)
            if (failure is IOException) throw failure
            throw IOException("cannot mmap ashmem", failure)
        }
    }

    private fun closeAfterFailure(descriptor: FileDescriptor) {
        try { Os.close(descriptor) } catch (_: ErrnoException) {}
    }
}
