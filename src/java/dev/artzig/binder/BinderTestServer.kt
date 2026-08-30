package dev.artzig.binder

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ServiceManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Test peer: publishes `IBinderTestService` through the hosted Binder broker
 * and exits with status 0 when a client calls `exit()`.
 */
internal object BinderTestServer {
    const val SERVICE_NAME = "azk.test.binder.service"

    private val exitLatch = CountDownLatch(1)

    private class Service : IBinderTestService.Stub() {
        private var callback: IBinderTestCallback? = null
        private val seqs = ArrayList<Int>()
        private val seqLock = java.lang.Object()

        override fun add(a: Int, b: Int): Int = a + b

        override fun echo(value: String?): String? = value

        override fun roundTripBundle(value: Bundle?): Bundle? {
            if (value == null) return null
            val out = Bundle(value)
            out.putString("server", "seen")
            return out
        }

        override fun callBack(callback: IBinderTestCallback, value: Int): Int = callback.ping(value) + 1

        override fun registerCallback(callback: IBinderTestCallback?) {
            synchronized(this) { this.callback = callback }
        }

        override fun getCallback(): IBinderTestCallback? = synchronized(this) { callback }

        override fun identity(binder: IBinder?): IBinder? = binder

        override fun noteSeq(seq: Int) {
            synchronized(seqLock) {
                seqs.add(seq)
                seqLock.notifyAll()
            }
        }

        override fun drainSeqs(expected: Int): IntArray {
            synchronized(seqLock) {
                val deadline = System.currentTimeMillis() + 10_000
                while (seqs.size < expected) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    seqLock.wait(remaining)
                }
                val out = seqs.toIntArray()
                seqs.clear()
                return out
            }
        }

        override fun throwIllegalArgument(message: String?) {
            throw IllegalArgumentException(message)
        }

        override fun writeToPipe(fd: ParcelFileDescriptor, text: String) {
            ParcelFileDescriptor.AutoCloseOutputStream(fd).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }

        override fun selfPid(): Int = Process.myPid()

        override fun callerPid(): Int = Binder.getCallingPid()

        override fun callerUid(): Int = Binder.getCallingUid()

        override fun exit() {
            exitLatch.countDown()
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val service = Service()
        ServiceManager.addService(SERVICE_NAME, service)
        System.err.println("binder-test-server: registered $SERVICE_NAME pid=" + Process.myPid())
        val exited = exitLatch.await(120, TimeUnit.SECONDS)
        System.err.println("binder-test-server: exiting (requested=$exited)")
        Runtime.getRuntime().exit(if (exited) 0 else 3)
    }
}
