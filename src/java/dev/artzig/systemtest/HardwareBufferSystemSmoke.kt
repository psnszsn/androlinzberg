package dev.artzig.systemtest

import android.hardware.HardwareBuffer
import android.os.Binder
import android.os.Parcel
import android.os.ServiceManager

/** Exercises the GBM mapper's descriptor transport through separate Binder peers. */
internal object HardwareBufferSystemSmoke {
    private const val NAME = "dev.artzig.test.b.hardwarebuffer"
    private val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
        HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_CPU_WRITE_OFTEN

    fun publish() {
        ServiceManager.addService(NAME, object : Binder() {
            private val buffer = if (HardwareBuffer.isSupported(8, 8, 1, 1, usage))
                HardwareBuffer.create(8, 8, 1, 1, usage) else null

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(NAME)
                when (code) {
                    1 -> {
                        checkNotNull(reply).writeLong(buffer?.id ?: 0)
                        reply.writeTypedObject(buffer, 0)
                    }
                    2 -> buffer?.close()
                    else -> return super.onTransact(code, data, reply, flags)
                }
                return true
            }
        })
    }

    fun verify() {
        val service = checkNotNull(ServiceManager.waitForService(NAME))
        check(service !is Binder)
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        var reader: HardwareBuffer? = null
        try {
            request.writeInterfaceToken(NAME)
            check(service.transact(1, request, reply, 0))
            val id = reply.readLong()
            reader = reply.readTypedObject(HardwareBuffer.CREATOR)
            if (reader == null) {
                check(System.getenv("AZK_TEST_REQUIRE_EGL") != "1") { "remote GBM allocation unavailable" }
                return
            }
            check(id != 0L && reader.id == id && reader.width == 8 && reader.height == 8 && reader.usage == usage)
            request.setDataPosition(0)
            reply.setDataSize(0)
            check(service.transact(2, request, reply, 0))
            // Imported storage remains mappable after closing the allocating peer's
            // reference. A fabricated metadata-only parcel cannot satisfy this.
            val graphic = android.graphics.GraphicBuffer.createFromHardwareBuffer(reader)
            try {
                val canvas = checkNotNull(graphic.lockCanvas())
                check(canvas.width == 8 && canvas.height == 8)
                canvas.drawColor(android.graphics.Color.BLUE)
                graphic.unlockCanvasAndPost(canvas)
            } finally { graphic.destroy() }
            check(reader.id == id)
            System.err.println("system-test-client-a: ok: stock HardwareBuffer imports live DMA-BUF storage across processes")
        } finally {
            reader?.close()
            reply.recycle()
            request.recycle()
        }
    }
}
