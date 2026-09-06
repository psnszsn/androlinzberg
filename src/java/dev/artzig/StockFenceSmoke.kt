package dev.artzig

import android.hardware.SyncFence
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.system.Os
import java.time.Duration

internal object StockFenceSmoke {
    @JvmStatic private external fun nativeTest()

    fun run() {
        nativeTest()
        javaFence()
        System.err.println("stock-fence: native waits, shared ownership and Parcel descriptor lifetime passed")
    }

    private fun javaFence() {
        // Pinned SyncFence distinguishes its null native pointer from a libui
        // Fence(-1): both are invalid, but only the latter passes nWait.
        SyncFence.createEmpty().use { empty ->
            check(!empty.isValid && empty.signalTime == SyncFence.SIGNAL_TIME_INVALID)
            check(!empty.await(Duration.ZERO))
            check(runCatching { empty.fdDup }.exceptionOrNull() is IllegalStateException)
            roundTrip(empty).use { check(!it.isValid) }
        }
        SyncFence.adopt(-1).use { invalid ->
            check(!invalid.isValid && invalid.awaitForever())
            check(invalid.signalTime == SyncFence.SIGNAL_TIME_INVALID)
        }

        // A pipe provides deterministic poll readiness without requiring a GPU
        // sync_file. Native FenceTest separately covers malformed sync ioctls.
        val pipe = ParcelFileDescriptor.createPipe()
        val originalFd = pipe[0].fd
        pipe[1].use { writer ->
            val fence = SyncFence.create(pipe[0])
            val copy = SyncFence(fence)
            try {
                check(fence.isValid && copy.isValid)
                check(fence.describeContents() == Parcelable.CONTENTS_FILE_DESCRIPTOR)
                check(!fence.await(Duration.ZERO))
                pipe[0].close() // Ownership was detached; closing the wrapper is harmless.
                fence.fdDup.use { duplicate ->
                    check(duplicate.fd != originalFd)
                    fence.close()
                    fence.close()
                    check(!fence.isValid && copy.isValid)
                    Os.write(writer.fileDescriptor, byteArrayOf(42), 0, 1)
                    check(copy.await(Duration.ZERO))
                    roundTrip(copy).use { parcelled ->
                        copy.close()
                        check(parcelled.isValid && parcelled.awaitForever())
                        parcelled.fdDup.use { reader ->
                            val bytes = ByteArray(1)
                            check(Os.read(reader.fileDescriptor, bytes, 0, 1) == 1)
                            check(bytes[0] == 42.toByte())
                        }
                    }
                    check(Os.fstat(duplicate.fileDescriptor).st_mode != 0)
                }
            } finally {
                fence.close()
                copy.close()
                pipe[0].close()
            }
        }
        System.err.println("stock-sync-fence: JNI ownership, copies, invalid fences, waits and parceling passed")
    }

    private fun roundTrip(fence: SyncFence): SyncFence {
        val parcel = Parcel.obtain()
        return try {
            fence.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            SyncFence.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
