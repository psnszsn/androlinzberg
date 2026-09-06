package dev.artzig

import android.os.Parcel
import android.os.Parcelable
import android.view.InputChannel

/** Stock InputChannel Java/JNI contracts over the shared Zig Binder/Parcel. */
object InputChannelSmoke {
    private inline fun <reified T : Throwable> expectFailure(action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            check(error is T) { "Expected ${T::class.java.name}, got $error" }
            return
        }
        error("Expected ${T::class.java.name}")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        expectFailure<IllegalArgumentException> { InputChannel.openInputChannelPair(null) }
        val empty = InputChannel()
        check(empty.name == "uninitialized" && empty.token == null)
        expectFailure<RuntimeException> { empty.dup() }

        val pair = InputChannel.openInputChannelPair("stock-input-λ")
        val retained = mutableListOf(pair[0], pair[1])
        try {
            check(pair.size == 2 && pair.all { it.name == "stock-input-λ" })
            val token = checkNotNull(pair[0].token)
            check(pair[0].token === token && pair[1].token === token)
            check(pair[0].describeContents() == Parcelable.CONTENTS_FILE_DESCRIPTOR)
            val duplicate = pair[0].dup().also { retained.add(it) }
            check(duplicate.token === token)
            val copied = InputChannel().also { retained.add(it) }
            pair[0].copyTo(copied)
            check(copied.token === token && copied.name == pair[0].name)
            expectFailure<IllegalArgumentException> { pair[0].copyTo(copied) }
            expectFailure<IllegalArgumentException> { pair[0].copyTo(null) }

            val parcel = Parcel.obtain()
            val restored: InputChannel
            try {
                parcel.writeInt(0x1234)
                pair[0].writeToParcel(parcel, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
                check(pair[0].name == "uninitialized" && pair[0].token == null)
                expectFailure<RuntimeException> { pair[0].dup() }
                parcel.writeInt(0x5678)
                parcel.setDataPosition(0)
                check(parcel.readInt() == 0x1234)
                restored = InputChannel.CREATOR.createFromParcel(parcel).also { retained.add(it) }
                check(restored.name == "stock-input-λ" && restored.token === token)
                check(parcel.readInt() == 0x5678 && parcel.dataAvail() == 0)
            } finally {
                parcel.recycle()
            }
            // Restored and duplicated channels survive source/parcel disposal.
            copied.dispose()
            duplicate.dispose()
            check(restored.name == "stock-input-λ" && restored.token === token)
            val afterRecycle = restored.dup().also { retained.add(it) }
            restored.dispose()
            check(afterRecycle.name == "stock-input-λ" && afterRecycle.token === token)

            val uninitializedParcel = Parcel.obtain()
            try {
                empty.writeToParcel(uninitializedParcel, 0)
                uninitializedParcel.setDataPosition(0)
                val uninitialized = InputChannel.CREATOR.createFromParcel(uninitializedParcel)
                retained.add(uninitialized)
                check(uninitialized.name == "uninitialized" && uninitialized.token == null)
                check(uninitializedParcel.dataAvail() == 0)
            } finally {
                uninitializedParcel.recycle()
            }
        } finally {
            retained.forEach { it.dispose() }
        }
        InputEventSmoke.run()
        InputHandleSmoke.run(args.single())
        println("InputChannel: stock JNI, native/Java Binder identity, parcel framing and disposal passed")
    }
}
