package dev.artzig.binder

import android.os.Binder
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ServiceManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Test peer: exercises `IBinderTestService` across the broker and exits 0
 * when every check passes. Failures print `FAIL: ...` and exit 1.
 */
internal object BinderTestClient {
    private var failures = 0

    private fun check(condition: Boolean, what: String) {
        if (condition) {
            System.err.println("binder-test-client: ok: $what")
        } else {
            failures++
            System.err.println("binder-test-client: FAIL: $what")
        }
    }

    private class Callback : IBinderTestCallback.Stub() {
        @Volatile var lastThread: Thread? = null
        override fun ping(value: Int): Int {
            lastThread = Thread.currentThread()
            return value * 3
        }
        override fun note(seq: Int) {}
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run()
        } catch (t: Throwable) {
            failures++
            System.err.println("binder-test-client: FAIL: uncaught " + t)
            t.printStackTrace()
        }
        System.err.println("binder-test-client: failures=$failures")
        Runtime.getRuntime().exit(if (failures == 0) 0 else 1)
    }

    private fun run() {
        val binder: IBinder = ServiceManager.waitForService(BinderTestServer.SERVICE_NAME)
            ?: throw IllegalStateException("server not found")
        val server = IBinderTestService.Stub.asInterface(binder)
        check(Binder.isProxy(server), "asInterface returns a proxy for a remote binder")
        check(binder.interfaceDescriptor == IBinderTestService.DESCRIPTOR, "interface descriptor over INTERFACE_TRANSACTION")
        check(binder.pingBinder(), "pingBinder")
        check(binder.isBinderAlive, "isBinderAlive before exit")

        // Primitives, strings, null, exceptions, Bundle.
        check(server.add(2, 40) == 42, "int arguments and result")
        check(server.echo("héllo") == "héllo", "string round trip")
        check(server.echo(null) == null, "null string round trip")
        val bundle = Bundle()
        bundle.putInt("i", 7)
        bundle.putString("s", "text")
        bundle.putLong("l", 1L shl 40)
        bundle.putBoolean("b", true)
        bundle.putStringArray("sa", arrayOf("x", "y"))
        val nested = Bundle()
        nested.putDouble("d", 2.5)
        bundle.putBundle("n", nested)
        bundle.putBinder("callback", Callback())
        val back = server.roundTripBundle(bundle)
        check(back != null, "bundle round trip returned")
        if (back != null) {
            check(back.getInt("i") == 7, "bundle int (got " + back.getInt("i") + ")")
            check(back.getString("s") == "text", "bundle string (got " + back.getString("s") + ")")
            check(back.getLong("l") == (1L shl 40), "bundle long (got " + back.getLong("l") + ")")
            check(back.getBoolean("b"), "bundle boolean")
            check(back.getStringArray("sa")?.toList() == listOf("x", "y"), "bundle string array (got " + back.getStringArray("sa")?.toList() + ")")
            check(back.getBundle("n")?.getDouble("d") == 2.5, "bundle nested (got " + back.getBundle("n")?.getDouble("d") + ")")
            check(back.getString("server") == "seen", "bundle server marker (got " + back.getString("server") + ")")
            check(back.getBinder("callback") === bundle.getBinder("callback"), "bundle binder identity")
        }
        check(server.roundTripBundle(null) == null, "null Bundle round trip")
        var threw = false
        try {
            server.throwIllegalArgument("boom")
        } catch (e: IllegalArgumentException) {
            threw = e.message == "boom"
        }
        check(threw, "remote IllegalArgumentException is rethrown with its message")

        // Nested synchronous callback executes on the waiting thread.
        val callback = Callback()
        val main = Thread.currentThread()
        check(server.callBack(callback, 5) == 16, "server calls back into the client during the call")
        check(callback.lastThread === main, "nested callback ran on the waiting thread")

        // Identity: our own object comes back as the same instance; proxies are cached.
        check(server.identity(callback) === callback, "local binder returned by the server is the same instance")
        val helperCallback = waitForHelperCallback(server)
        check(helperCallback != null, "helper's callback is available through the server")
        val again = server.callback
        check(again != null && again.asBinder() === helperCallback!!.asBinder(), "proxy identity is stable for repeated receipt")
        check(helperCallback!!.ping(21) == 42, "binder forwarded through the server reaches the helper process")
        check(helperCallback.asBinder() !== binder, "forwarded binder is distinct from the server binder")

        // Oneway ordering.
        for (i in 1..50) server.noteSeq(i)
        val seqs = server.drainSeqs(50)
        check(seqs.toList() == (1..50).toList(), "oneway calls keep per-object order")

        checkWorkerReuseAndOrdering(server)

        // File descriptor passing.
        val pipe = ParcelFileDescriptor.createPipe()
        server.writeToPipe(pipe[1], "hello")
        pipe[1].close()
        val text = ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { String(it.readBytes(), Charsets.UTF_8) }
        check(text == "hello", "pipe descriptor crosses the broker and stays usable")

        // Calling identity.
        check(server.callerPid() == Process.myPid(), "callee sees the caller's pid")
        check(server.callerUid() == Process.myUid(), "callee sees the caller's package uid")
        check(Process.myUid() != Process.SYSTEM_UID && Process.myUid() >= Process.FIRST_APPLICATION_UID, "app peer has an app uid")
        var denied = false
        try {
            ServiceManager.addService("activity", Callback())
        } catch (e: SecurityException) {
            denied = true
        }
        check(denied, "an app may not register a system service name")
        check(server.selfPid() != Process.myPid(), "server runs in another process")
        check(Binder.getCallingPid() == Process.myPid(), "calling identity is self outside a transaction")

        // Death.
        val died = CountDownLatch(1)
        val workersBeforeDeath = Thread.getAllStackTraces().keys
        val deathThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val recipient = IBinder.DeathRecipient {
            deathThread.set(Thread.currentThread())
            died.countDown()
        }
        binder.linkToDeath(recipient, 0)
        server.exit()
        check(died.await(20, TimeUnit.SECONDS), "death recipient fires once the server exits")
        check(deathThread.get() in workersBeforeDeath, "death notification reuses an attached worker")
        check(!binder.isBinderAlive, "isBinderAlive is false after death")
        var dead = false
        try {
            server.add(1, 1)
        } catch (e: DeadObjectException) {
            dead = true
        }
        check(dead, "calls after death throw DeadObjectException")
        check(!binder.unlinkToDeath(recipient, 0), "unlinkToDeath after death reports the recipient is gone")
        var gone = false
        for (i in 0 until 100) {
            if (ServiceManager.checkService(BinderTestServer.SERVICE_NAME) == null) { gone = true; break }
            Thread.sleep(50)
        }
        check(gone, "service name disappears with its owner")
    }

    private fun checkWorkerReuseAndOrdering(server: IBinderTestService) {
        val warmed = checkCallbackBatch(server, saturate = true)
        val reused = checkCallbackBatch(server, saturate = false)
        check(warmed.size == 32, "blocking callbacks grow the shared pool to its bound")
        check(reused.size <= 32 && reused.all { it in warmed },
            "new Binder objects reuse attached workers (${reused.size} observed)")
    }

    private fun checkCallbackBatch(server: IBinderTestService, saturate: Boolean): Set<Thread> {
        val targetCount = 64
        val notesPerTarget = 8
        val saturated = CountDownLatch(32)
        val threads = java.util.concurrent.ConcurrentHashMap.newKeySet<Thread>()
        val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val othersStarted = CountDownLatch(targetCount - 1)
        val completed = CountDownLatch(targetCount * notesPerTarget)
        val callbacks = Array(targetCount) { index ->
            object : IBinderTestCallback.Stub() {
                private val active = java.util.concurrent.atomic.AtomicBoolean()
                private var next = 0
                override fun ping(value: Int) = value
                override fun note(seq: Int) {
                    if (active.getAndSet(true)) errors.add("Concurrent callback on object $index")
                    try {
                        threads.add(Thread.currentThread())
                        if (seq != next++) errors.add("Object $index expected ${next - 1}, got $seq")
                        if (seq == 0) {
                            if (saturate && index < 32) {
                                saturated.countDown()
                                if (!saturated.await(10, TimeUnit.SECONDS)) errors.add("Pool failed to grow for blocked work")
                            }
                            if (index == 0) {
                                if (!othersStarted.await(10, TimeUnit.SECONDS)) errors.add("Blocked object starved other targets")
                            } else othersStarted.countDown()
                        }
                        // A serial callback can itself transact without blocking
                        // the reader or losing per-object FIFO exclusivity.
                        if (server.add(index, seq) != index + seq) errors.add("Nested call failed")
                    } catch (failure: Throwable) {
                        errors.add(failure.toString())
                    } finally {
                        active.set(false)
                        completed.countDown()
                    }
                }
            }
        }
        val exported = Bundle()
        callbacks.forEachIndexed { index, callback -> exported.putBinder(index.toString(), callback) }
        server.queueNotes(exported, notesPerTarget)
        check(completed.await(20, TimeUnit.SECONDS), "64 Binder objects complete 512 ordered callbacks")
        check(errors.isEmpty(), "per-object exclusion, FIFO and independent progress: $errors")
        check(threads.size <= 32, "callbacks obey the shared worker limit (${threads.size} observed)")
        return threads
    }

    private fun waitForHelperCallback(server: IBinderTestService): IBinderTestCallback? {
        for (i in 0 until 600) {
            server.callback?.let { return it }
            Thread.sleep(100)
        }
        return null
    }
}
