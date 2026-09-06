package dev.artzig

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.android.internal.os.ApplicationSharedMemory
import java.io.FileDescriptor

/**
 * Linux file backing for stock ApplicationSharedMemory JNI. libcore's existing
 * Posix interface owns descriptor operations; the pinned AOSP C++ code owns the
 * memory layout, mapping, feature cache, and cross-process atomic accesses.
 * Its libcutils host ashmem implementation cannot enforce write protection,
 * so replace only allocation and read-only duplication with sealed memfds.
 */
object ApplicationSharedMemoryBackend {
    private var layoutSize = 0L

    /** Called before replacing nativeCreate, while the stock factory is installed. */
    @JvmStatic fun initialize() {
        if (layoutSize != 0L) return
        // Ask the actual compiled native layout for its size instead of copying
        // a private C++ structure or an architecture-dependent size into Kotlin.
        ApplicationSharedMemory.create().use { layoutSize = Os.fstat(it.fileDescriptor).st_size }
        check(layoutSize > 0)
    }

    // Pinned bionic Linux UAPI linux/memfd.h and linux/fcntl.h. These Linux-only
    // flags are not exported by the pinned android.system.OsConstants API.
    private const val MFD_CLOEXEC = 1
    private const val MFD_ALLOW_SEALING = 2
    private const val F_ADD_SEALS = 1033
    private const val F_SEAL_SHRINK = 2
    private const val F_SEAL_GROW = 4
    private const val F_SEAL_FUTURE_WRITE = 16

    @JvmStatic fun create(): Int {
        check(layoutSize > 0) { "ApplicationSharedMemory layout is not initialized" }
        var descriptor: FileDescriptor? = null
        try {
            descriptor = Os.memfd_create("ApplicationSharedMemory", MFD_CLOEXEC or MFD_ALLOW_SEALING)
            Os.ftruncate(descriptor, layoutSize)
            Os.fcntlInt(descriptor, F_ADD_SEALS, F_SEAL_GROW or F_SEAL_SHRINK)
            return descriptor.`getInt$`() // ApplicationSharedMemory adopts this descriptor.
        } catch (failure: ErrnoException) {
            descriptor?.let { closeAfterFailure(it) }
            throw RuntimeException("Failed to create application shared memory: ${failure.message}", failure)
        }
    }

    @JvmStatic fun duplicateReadOnly(rawFd: Int): Int {
        val source = FileDescriptor().apply { `setInt$`(rawFd) } // Borrowed from the stock owner.
        var duplicate: FileDescriptor? = null
        try {
            duplicate = FileDescriptor().apply { `setInt$`(Os.fcntlInt(source, OsConstants.F_DUPFD_CLOEXEC, 0)) }
            // Like libcutils' memfd_set_prot_region: existing writable mappings
            // keep working, while future writable mappings and writes are denied
            // through every descriptor, including those passed over Binder.
            Os.fcntlInt(duplicate, F_ADD_SEALS, F_SEAL_FUTURE_WRITE)
            return duplicate.`getInt$`()
        } catch (failure: ErrnoException) {
            duplicate?.let { closeAfterFailure(it) }
            throw RuntimeException("Failed to duplicate application shared memory read-only: ${failure.message}", failure)
        }
    }

    private fun closeAfterFailure(descriptor: FileDescriptor) {
        try { Os.close(descriptor) } catch (_: ErrnoException) {}
    }
}
