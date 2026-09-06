package dev.artzig

import android.os.Parcel
import java.io.FileDescriptor

/**
 * Pinned Parcel::write/read(FlattenableHelperInterface) and GraphicBuffer's GB01
 * header. The native-handle payload identifies the hosted GBM mapper, in the
 * same place that an Android gralloc mapper puts its private transport words.
 * Native import validates that payload and imports the transferred DMA-BUF.
 */
object HardwareBufferParcel {
    private const val WORD_COUNT = 20 // Thirteen GraphicBuffer words + seven mapper words.

    @JvmStatic fun write(handle: Long, parcel: Parcel?) {
        if (parcel == null) return // Pinned JNI's null parcelForJavaObject path.
        val words = nativeDescribe(handle)
        check(words.size == WORD_COUNT + 1) // Last word is a borrowed export FD.
        parcel.writeInt(WORD_COUNT * 4)
        parcel.writeInt(1)
        for (i in 0 until WORD_COUNT) parcel.writeInt(words[i])
        val descriptor = FileDescriptor().apply { `setInt$`(words[WORD_COUNT]) }
        // Parcel duplicates the descriptor; the native buffer retains ownership.
        parcel.writeFileDescriptor(descriptor)
    }

    @JvmStatic fun read(parcel: Parcel?): Long {
        if (parcel == null || parcel.dataAvail() < 8) return 0
        val byteCount = parcel.readInt()
        val fdCount = parcel.readInt()
        if (byteCount != WORD_COUNT * 4 || fdCount != 1 || parcel.dataAvail() < byteCount) return 0
        val words = IntArray(WORD_COUNT) { parcel.readInt() }
        val descriptor = parcel.readFileDescriptor() ?: return 0
        return descriptor.use { nativeImport(words, it.fileDescriptor.`getInt$`()) }
    }

    @JvmStatic private external fun nativeDescribe(handle: Long): IntArray
    @JvmStatic private external fun nativeImport(words: IntArray, fd: Int): Long
}
