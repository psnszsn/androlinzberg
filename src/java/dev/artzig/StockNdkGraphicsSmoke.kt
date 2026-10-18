package dev.artzig

import android.graphics.Bitmap
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.RandomAccessFile

/** Public NDK bitmap/window and fortified libc interfaces, using real Java-owned objects. */
internal object StockNdkGraphicsSmoke {
    private external fun bitmapAndWindow(bitmap: Bitmap, emptySurface: android.view.Surface): Boolean
    private external fun windowParcel(): Boolean
    private external fun sharedMemory(memory: android.os.SharedMemory): Boolean
    private external fun checkedIo(file: FileDescriptor, socket: FileDescriptor): Boolean

    private external fun symbolOwner(symbol: String): String?

    private fun verifyNativeOwners() {
        val owners = mapOf(
            "AIBinder_new" to "libbinder_ndk.so",
            "AAssetManager_fromJava" to "libandroid_platform.so",
            "AAssetManager_open" to "libandroid_platform.so",
            "AParcel_create" to "libbinder_ndk.so",
            "AHardwareBuffer_allocate" to "libandroid_platform.so",
            "AMediaCodec_createDecoderByType" to "libandroid_platform.so",
            "AAudio_createStreamBuilder" to "libandroid_platform.so",
            "ASensorManager_getInstance" to "libandroid_platform.so",
        )
        for ((symbol, expected) in owners) {
            val owner = checkNotNull(symbolOwner(symbol)) { "Native symbol is absent: $symbol" }
            check(File(owner).name == expected) { "$symbol resolves to $owner, expected $expected" }
        }
        val mappings = File("/proc/self/maps").readLines().mapNotNull { line ->
            val fields = line.split(Regex("\\s+"), limit = 6)
            if (fields.size == 6 && fields[5].startsWith("/")) fields else null
        }
        for (name in owners.values.toSet() + "libbinder.so") {
            val images = mappings.filter { File(it[5]).name == name }
                .map { it[3] to it[4] }.toSet()
            check(images.size == 1) { "Expected one mapped $name, found $images" }
        }
        val decodeLibraries = listOf("libavcodec.", "libavformat.", "libavutil.", "libswscale.", "libva.")
        check(mappings.none { row -> decodeLibraries.any { File(row[5]).name.startsWith(it) } }) {
            "Media decode backend was loaded into the application process"
        }
        println("stock-native-owners: PASS runtime NDK owners, unique ELF images and media decode process boundary")
    }

    fun run() {
        verifyNativeOwners()
        check(windowParcel()) { "Original native-window DSO parcel loader failed" }
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        val surface = android.view.Surface()
        try {
            bitmap.eraseColor(android.graphics.Color.BLACK)
            check(bitmapAndWindow(bitmap, surface)) { "NDK bitmap/window contracts failed" }
            check(bitmap.getPixel(0, 0) == 0xff123456.toInt()) { "NDK RGBA bytes did not match Java bitmap colors" }
        } finally {
            bitmap.recycle()
            surface.release()
        }
        val file = File.createTempFile("stock-ndk-", ".bin")
        val left = FileDescriptor()
        val right = FileDescriptor()
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, left, right)
        try {
            RandomAccessFile(file, "rw").use { stream ->
                stream.write(byteArrayOf(0, 0, 0, 0))
                stream.seek(1)
                check(checkedIo(stream.fd, left)) { "Fortified libc operations failed" }
                check(stream.filePointer == 1L) { "pwrite changed the file position" }
                stream.seek(0)
                val actual = ByteArray(4)
                stream.readFully(actual)
                check(actual.contentEquals(byteArrayOf(0, 0, 4, 5)))
            }
            val received = ByteArray(4)
            check(Os.read(right, received, 0, received.size) == 2)
            check(received[0] == 4.toByte() && received[1] == 5.toByte())
        } finally {
            Os.close(left)
            Os.close(right)
            file.delete()
        }
        android.os.SharedMemory.create("stock-java-ndk", 4096).use { memory ->
            check(sharedMemory(memory)) { "NDK shared memory ownership/protection contracts failed" }
            check(memory.size == 4096) { "NDK duplicate closed the Java owner" }
        }
        println("stock-ndk-graphics: PASS original window parcel loader, bitmap ownership, pixel mutation, empty surface, fortified I/O and sealed shared memory")
    }
}
