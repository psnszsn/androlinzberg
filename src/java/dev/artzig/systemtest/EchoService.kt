package dev.artzig.systemtest

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A real `android.app.Service` bound across processes in system-test. Its
 * binder speaks `IBinderTestService` so the client can reuse those probes.
 */
internal class EchoService : Service() {
    private val binder = object : dev.artzig.binder.IBinderTestService.Stub() {
        override fun add(a: Int, b: Int): Int = a + b

        override fun echo(value: String?): String? = value

        override fun roundTripBundle(value: Bundle?): Bundle? {
            if (value == null) return null
            val out = Bundle(value)
            out.putString("server", "seen")
            out.putString("action", lastIntent?.action)
            out.putString("data", lastIntent?.data?.toString())
            value.getParcelable("creator-intent", Intent::class.java)?.let {
                if (!android.security.Flags.preventIntentRedirect()) {
                    it.setCreatorToken(checkNotNull(value.getBinder("creator-token")))
                }
                out.putBoolean("creator-valid-in-peer", dev.artzig.system.HostedIntentCreators.isValid(it))
            }
            return out
        }

        override fun callBack(callback: dev.artzig.binder.IBinderTestCallback, value: Int): Int =
            callback.ping(value) + 1

        override fun registerCallback(callback: dev.artzig.binder.IBinderTestCallback?) {}

        override fun getCallback(): dev.artzig.binder.IBinderTestCallback? = null

        override fun identity(binder: IBinder?): IBinder? = binder

        override fun noteSeq(seq: Int) {
            // system-test probes: a broadcast and a clipboard set from this
            // process, both observed by the client across processes.
            sendBroadcast(Intent("dev.artzig.test.PING").putExtra("seq", seq))
            getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("b", "clip-" + seq))
        }

        override fun drainSeqs(expected: Int): IntArray = IntArray(0)

        override fun throwIllegalArgument(message: String?) {
            throw IllegalArgumentException(message)
        }

        override fun writeToPipe(fd: ParcelFileDescriptor, text: String) {
            ParcelFileDescriptor.AutoCloseOutputStream(fd).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }

        override fun selfPid(): Int = Process.myPid()

        override fun callerPid(): Int = Binder.getCallingPid()

        override fun callerUid(): Int = Binder.getCallingUid()

        override fun queueNotes(callbacks: Bundle, count: Int) {
            val targets = (0 until callbacks.size()).map {
                dev.artzig.binder.IBinderTestCallback.Stub.asInterface(
                    checkNotNull(callbacks.getBinder(it.toString())))
            }
            for (seq in 0 until count) for (target in targets) target.note(seq)
        }

        override fun exit() {
            exitLatch.countDown()
        }
    }

    private var lastIntent: Intent? = null

    override fun onCreate() {
        created.set(true)
    }

    override fun onBind(intent: Intent?): IBinder {
        lastIntent = intent
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        unbound.set(true)
        return false
    }

    companion object {
        @JvmField val exitLatch = CountDownLatch(1)
        @JvmField val created = AtomicBoolean(false)
        @JvmField val unbound = AtomicBoolean(false)
    }
}
