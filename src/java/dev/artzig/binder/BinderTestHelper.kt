package dev.artzig.binder

import android.os.IBinder
import android.os.Process
import android.os.ServiceManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Test peer: registers a callback object with the server so a third process
 * can obtain it through the server and call back into this one. Exits 0 when
 * its death recipient for the server fires.
 */
internal object BinderTestHelper {
    private val serverDied = CountDownLatch(1)

    private class Callback : IBinderTestCallback.Stub() {
        override fun ping(value: Int): Int = value * 2
        override fun note(seq: Int) {}
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val binder: IBinder = ServiceManager.waitForService(BinderTestServer.SERVICE_NAME)
            ?: run {
                System.err.println("binder-test-helper: server not found")
                Runtime.getRuntime().exit(2)
                return
            }
        val server = IBinderTestService.Stub.asInterface(binder)
        val callback = Callback()
        server.registerCallback(callback)
        binder.linkToDeath({ serverDied.countDown() }, 0)
        System.err.println("binder-test-helper: registered callback pid=" + Process.myPid())
        val died = serverDied.await(120, TimeUnit.SECONDS)
        val alive = binder.isBinderAlive
        System.err.println("binder-test-helper: server died=$died alive=$alive")
        Runtime.getRuntime().exit(if (died && !alive) 0 else 3)
    }
}
