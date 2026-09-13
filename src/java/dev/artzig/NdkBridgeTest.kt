package dev.artzig

import android.os.Binder
import android.os.Parcel
import android.os.IBinder
import java.lang.ref.WeakReference

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
    @JvmStatic external fun roundTrip(binder: IBinder?): IBinder?
    @JvmStatic external fun mutateParcel(parcel: Parcel, binder: IBinder): Int
    @JvmStatic external fun retainBinder(binder: IBinder): Long
    @JvmStatic external fun releaseBinder(handle: Long)

    private fun retainedJavaBinder(): Pair<Long, WeakReference<IBinder>> {
        val binder = JavaAdder()
        return retainBinder(binder) to WeakReference<IBinder>(binder)
    }

    private fun collect() {
        System.gc()
        System.runFinalization()
    }

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

        val javaBinder = JavaAdder()
        check(roundTrip(javaBinder) === javaBinder, "Java Binder round-trip preserves object identity")
        check(roundTrip(null) == null, "null Binder conversion")
        val shared = Parcel.obtain()
        try {
            shared.writeInt(7)
            shared.setDataPosition(0)
            check(mutateParcel(shared, javaBinder) == 7, "NDK reads Java-owned Parcel storage")
            shared.setDataPosition(4)
            check(shared.readInt() == 11 && shared.readStrongBinder() === javaBinder && shared.readInt() == 99,
                "Java reads NDK mutations and Binder after borrowed-view deletion")
        } finally { shared.recycle() }
        val (nativeHandle, weakBinder) = retainedJavaBinder()
        check(nativeHandle != 0L, "native Binder strong reference acquired")
        try {
            collect()
            check(weakBinder.get() != null, "native reference retains Java Binder through GC")
        } finally { releaseBinder(nativeHandle) }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (weakBinder.get() != null && System.nanoTime() < deadline) {
            collect()
            Thread.sleep(10)
        }
        check(weakBinder.get() == null, "last native release permits Java Binder collection")

        // Direction B: Java code calls into a native AIBinder (toJavaBinder).
        val nativeAdder = makeNativeAdder()
        check(roundTrip(nativeAdder) === nativeAdder, "native Binder round-trip preserves Java proxy identity")
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
