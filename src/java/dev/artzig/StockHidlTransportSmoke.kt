package dev.artzig

import android.os.HidlSupport
import android.os.HwBinder
import android.os.HwBlob
import android.os.HwParcel
import android.hidl.manager.V1_2.IServiceManager
import java.util.NoSuchElementException

/** Original HIDL value containers and Android's own no-hardware service-manager path. */
internal object StockHidlTransportSmoke {
    private inline fun <reified T : Throwable> rejects(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        check(failure is T) { "Expected ${T::class.java.name}, got $failure" }
    }
    fun run() {
        check(!HidlSupport.isHidlSupported())
        val binder = HwBinder.getService("android.hidl.manager@1.2::IServiceManager", "default")
        val local = checkNotNull(IServiceManager.asInterface(binder))
        check(local.list().isEmpty() && local.debugDump().isEmpty())
        check(local.getTransport("android.hardware.cas@1.2::IMediaCasService", "default").toInt() == 0)
        check(local.get("android.hardware.cas@1.2::IMediaCasService", "default") == null)
        // Force generated proxy serialization through the native HwNoService transaction path.
        val proxy = IServiceManager.Proxy(binder)
        check(proxy.list().isEmpty() && proxy.debugDump().isEmpty())
        check(proxy.getTransport("android.hardware.cas@1.2::IMediaCasService", "default").toInt() == 0)
        check(proxy.get("android.hardware.cas@1.2::IMediaCasService", "default") == null)
        rejects<NoSuchElementException> { HwBinder.getService("android.hardware.cas@1.2::IMediaCasService", "default", false) }
        val parcel = HwParcel()
        try {
            parcel.writeInterfaceToken("android.hidl.manager@1.2::IServiceManager")
            rejects<NullPointerException> { binder.transact(1, null, parcel, 0) }
        } finally { parcel.releaseTemporaryStorage(); parcel.release() }
        val blob = HwBlob(128)
        blob.putBool(0, true)
        blob.putInt8(1, -2)
        blob.putInt16(2, 0x1234)
        blob.putInt32(4, -987654)
        blob.putInt64(8, Long.MIN_VALUE + 7)
        blob.putFloat(16, 1.25f)
        blob.putDouble(24, -2.5)
        check(blob.getBool(0) && blob.getInt8(1).toInt() == -2 && blob.getInt16(2).toInt() == 0x1234)
        check(blob.getInt32(4) == -987654 && blob.getInt64(8) == Long.MIN_VALUE + 7)
        check(blob.getFloat(16) == 1.25f && blob.getDouble(24) == -2.5)
        blob.putInt32Array(32, intArrayOf(1, -2, 3))
        val array = IntArray(3)
        blob.copyToInt32Array(32, array, 3)
        check(array.contentEquals(intArrayOf(1, -2, 3)))
        blob.putString(64, "HIDL café")
        check(blob.getString(64) == "HIDL café")
        rejects<IndexOutOfBoundsException> { blob.getInt64(124) }
        rejects<IndexOutOfBoundsException> { blob.putInt32(128, 3) }
        rejects<IllegalArgumentException> { blob.copyToInt32Array(32, IntArray(1), 3) }
        check(android.hardware.gnss.V2_1.IGnssMeasurementCallback.GnssMeasurementFlags.HAS_FULL_ISB == 65536)
        val a = arrayListOf(intArrayOf(1, 2), intArrayOf(3))
        val b = arrayListOf(intArrayOf(1, 2), intArrayOf(3))
        check(HidlSupport.deepEquals(a, b) && HidlSupport.deepHashCode(a) == HidlSupport.deepHashCode(b))
        b[1][0] = 4
        check(!HidlSupport.deepEquals(a, b))
        println("stock-hidl-transport: PASS native blobs, generated proxies and stock no-service discovery")
    }
}
