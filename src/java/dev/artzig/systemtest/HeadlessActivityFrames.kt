package dev.artzig.systemtest

import android.os.Handler
import android.os.Looper
import android.view.Choreographer

/** Compositor clock for Binder-only activity fixtures. Stock roots install traversal
 * barriers, so frames must continue even while another process is being queried or
 * waiting for this process's pause acknowledgement. Production APKs use the host clock. */
internal object HeadlessActivityFrames {
    private var started = false

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (started) return
        started = true
        val handler = Handler.createAsync(Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                Choreographer.getInstance().`doFrame$artzig`(System.nanoTime())
                handler.postDelayed(this, 16)
            }
        })
    }
}
