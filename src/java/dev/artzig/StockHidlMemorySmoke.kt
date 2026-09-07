package dev.artzig

import android.os.HidlMemory
import android.os.NativeHandle
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.IOException

/** Public ownership contracts and the original Java/native HIDL value conversion. */
internal object StockHidlMemorySmoke {
    @JvmStatic private external fun nativeRoundTrip(memory: HidlMemory): HidlMemory?

    private inline fun <reified T : Throwable> rejects(action: () -> Unit): T {
        try { action() } catch (error: Throwable) {
            check(error is T) { "Expected ${T::class.java.name}, got $error" }
            return error
        }
        error("Expected ${T::class.java.name}")
    }

    private fun read(fd: FileDescriptor): ByteArray = ByteArray(4).also {
        check(Os.pread(fd, it, 0, it.size, 0) == it.size)
    }

    fun run() {
        NativeHandle().use { empty ->
            check(!empty.hasSingleFileDescriptor())
            check(empty.fileDescriptors.isEmpty() && empty.ints.isEmpty())
            rejects<IllegalStateException> { empty.fileDescriptor }
        }
        val invalid = NativeHandle()
        invalid.close()
        for (operation in listOf<() -> Unit>(
            { invalid.close() }, { invalid.fileDescriptors }, { invalid.ints },
            { invalid.hasSingleFileDescriptor() }, { invalid.fileDescriptor }
        )) {
            check(rejects<IllegalStateException>(operation).message ==
                "NativeHandle is invalidated after close.")
        }
        NativeHandle(FileDescriptor(), false).use {
            rejects<IOException> { it.dup() }
        }

        val file = File.createTempFile("stock-hidl-memory", ".bin")
        val payload = byteArrayOf(11, 23, 47, 95)
        file.writeBytes(payload)
        try {
            val sourceFd = Os.open(file.absolutePath, OsConstants.O_RDONLY, 0)
            val descriptors = arrayOf(sourceFd)
            val integers = intArrayOf(17, -29, Int.MAX_VALUE)
            val owned = NativeHandle(descriptors, integers, true)
            // The constructor copies the arrays, retaining the descriptor objects.
            descriptors[0] = FileDescriptor()
            integers[0] = 0
            HidlMemory("ashmem", payload.size.toLong(), owned).use { memory ->
                check(memory.handle === owned)
                check(owned.fileDescriptors[0] === sourceFd && owned.ints[0] == 17)
                check(!owned.hasSingleFileDescriptor())
                rejects<IllegalStateException> { owned.fileDescriptor }
                memory.dup().use { duplicate ->
                    val duplicateHandle = checkNotNull(duplicate.handle)
                    val duplicateFd = duplicateHandle.fileDescriptors.single()
                    check(duplicateFd !== sourceFd)
                    check(Os.fcntlInt(duplicateFd, OsConstants.F_GETFD, 0) and OsConstants.FD_CLOEXEC != 0)
                    check(duplicateHandle.ints.contentEquals(intArrayOf(17, -29, Int.MAX_VALUE)))
                    repeat(2) {
                        // Repeated conversion also exercises the original native context cache.
                        checkNotNull(nativeRoundTrip(memory)).use { converted ->
                            check(converted.name == "ashmem" && converted.size == 4L)
                            val handle = checkNotNull(converted.handle)
                            check(handle.ints.contentEquals(owned.ints))
                            check(read(handle.fileDescriptors.single()).contentEquals(payload))
                        }
                        // A JNI-produced handle borrows the fd; close must leave the owner usable.
                        check(read(sourceFd).contentEquals(payload))
                    }
                    owned.ints[0] = 99
                    check(duplicateHandle.ints[0] == 17)
                    memory.close()
                    check(memory.handle == null)
                    check(rejects<ErrnoException> { Os.fstat(sourceFd) }.errno == OsConstants.EBADF)
                    check(read(duplicateFd).contentEquals(payload))
                }
            }

            val borrowedFd = Os.open(file.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                NativeHandle(borrowedFd, false).use { handle ->
                    check(handle.hasSingleFileDescriptor() && handle.fileDescriptor === borrowedFd)
                }
                check(read(borrowedFd).contentEquals(payload))
                val memory = HidlMemory("ashmem", 4, NativeHandle(borrowedFd, false))
                val released = checkNotNull(memory.releaseHandle())
                memory.close()
                memory.close()
                check(memory.releaseHandle() == null && memory.handle == null)
                released.use { check(read(it.fileDescriptor).contentEquals(payload)) }
                check(read(borrowedFd).contentEquals(payload))
            } finally { Os.close(borrowedFd) }

            HidlMemory("empty", 0, null).use { empty ->
                empty.dup().use { check(it.name == "empty" && it.size == 0L && it.handle == null) }
                checkNotNull(nativeRoundTrip(empty)).use { converted ->
                    check(converted.name == "empty" && converted.size == 0L)
                    check(converted.handle == null)
                }
            }
        } finally { check(file.delete()) }
        println("stock native handle and HIDL memory ownership/conversion: PASS")
    }
}
