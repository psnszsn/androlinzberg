package dev.artzig

import android.os.Binder
import android.os.Parcel

/**
 * In-process test of the libbinder_ndk JNI true-sharing bridge: with the
 * library loaded into this ART process, a native caller transacts a Java Binder
 * (fromJavaBinder + forward) and a Java caller transacts a native AIBinder
 * (toJavaBinder + reverse), both without a broker round-trip. Exits 0 on
 * success. Run with AZK_TEST_CLASS=dev.artzig.NdkBridgeTest and AZK_NDK_LIB.
 */
object NdkBridgeTest {
    @JvmStatic external fun forwardAdd(callback: Binder, a: Int, b: Int): Int
    @JvmStatic external fun makeNativeAdder(): android.os.IBinder

    /** A local Java Binder the native side calls back into. */
    private class JavaAdder : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == 1) {
                val a = data.readInt()
                val b = data.readInt()
                reply?.writeInt(a + b)
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private var failures = 0
    private fun check(cond: Boolean, what: String) {
        if (cond) System.err.println("ndk-bridge-test: ok: $what")
        else { failures++; System.err.println("ndk-bridge-test: FAIL: $what") }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val lib = args.firstOrNull() ?: System.getenv("AZK_NDK_LIB")
        // System.load requires an absolute path; the build passes a path
        // relative to the build directory.
        if (lib != null) System.load(java.io.File(lib).absolutePath) else System.loadLibrary("binder_ndk")

        // Direction A: native code calls into a Java Binder (fromJavaBinder).
        val sum = forwardAdd(JavaAdder(), 20, 22)
        check(sum == 42, "native->Java forward add(20,22)=42 (got $sum)")

        // Direction B: Java code calls into a native AIBinder (toJavaBinder).
        val nativeAdder = makeNativeAdder()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInt(3)
            data.writeInt(4)
            val handled = nativeAdder.transact(1, data, reply, 0)
            val r = reply.readInt()
            check(handled && r == 7, "Java->native reverse add(3,4)=7 (got $r, handled=$handled)")
        } finally {
            data.recycle()
            reply.recycle()
        }

        System.err.println("ndk-bridge-test: failures=$failures")
        Runtime.getRuntime().exit(if (failures == 0) 0 else 1)
    }
}
