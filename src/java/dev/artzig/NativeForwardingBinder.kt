package dev.artzig

import android.os.Binder
import android.os.Parcel

/**
 * A Java Binder backed by a native AIBinder in libbinder_ndk. Created by the
 * JNI bridge (AIBinder_toJavaBinder, local case); its onTransact forwards the
 * transaction to the native object through the library's `nativeTransact` entry
 * (implemented in src/binder/ndk.zig). The native library holds one strong ref
 * to the AIBinder for this wrapper, released when the wrapper is finalized.
 */
class NativeForwardingBinder(private val nativePtr: Long) : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
        nativeTransact(nativePtr, code, data, reply, flags)

    @Suppress("removal", "deprecation")
    protected fun finalize() {
        nativeRelease(nativePtr)
    }

    private external fun nativeTransact(ptr: Long, code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean
    private external fun nativeRelease(ptr: Long)
}
