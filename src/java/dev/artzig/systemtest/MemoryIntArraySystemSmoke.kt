package dev.artzig.systemtest

import android.os.Binder
import android.os.Parcel
import android.os.ServiceManager
import android.util.MemoryIntArray

/** Verifies that an owner mapping and a Binder recipient share actual pages. */
internal object MemoryIntArraySystemSmoke {
    private const val NAME = "dev.artzig.test.b.memoryarray"

    fun publish() {
        ServiceManager.addService(NAME, object : Binder() {
            private val values = MemoryIntArray(2).apply { set(0, 17) }

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(NAME)
                when (code) {
                    1 -> checkNotNull(reply).writeTypedObject(values, 0)
                    2 -> values.set(0, data.readInt())
                    3 -> values.close()
                    else -> return super.onTransact(code, data, reply, flags)
                }
                return true
            }
        })
    }

    fun verify() {
        val service = checkNotNull(ServiceManager.waitForService(NAME))
        check(service !is Binder) { "shared array test requires a remote process" }
        fun <T> transact(code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(NAME)
                write(data)
                check(service.transact(code, data, reply, 0))
                return read(reply)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
        val reader = transact(1) { checkNotNull(it.readTypedObject(MemoryIntArray.CREATOR)) }
        reader.use {
            check(!it.isWritable && it.size() == 2 && it.get(0) == 17 && it.get(1) == 0)
            check(runCatching { it.set(0, 9) }.exceptionOrNull() is UnsupportedOperationException)
            transact(2, { parcel -> parcel.writeInt(Int.MIN_VALUE) }) {}
            check(it.get(0) == Int.MIN_VALUE) { "owner updates must reach the existing recipient mapping" }
            transact(3) {}
            check(it.get(0) == Int.MIN_VALUE) { "recipient mapping must survive owner close" }
        }
        System.err.println("system-test-client-a: ok: stock MemoryIntArray shares sealed pages across processes")
    }
}
