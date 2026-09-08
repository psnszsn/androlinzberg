package dev.artzig

import android.system.Os
import java.io.FileDescriptor

/** The Java pump must dispatch callbacks registered through the public NDK API. */
internal object StockNdkLooperSmoke {
    private external fun attach(fd: FileDescriptor): Boolean
    private external fun callbackCount(): Int
    private external fun detach(fd: FileDescriptor)

    fun run(pump: () -> Unit) {
        val pipe = Os.pipe()
        try {
            check(attach(pipe[0])) { "Java and NDK do not share a native looper" }
            check(callbackCount() == 0)
            Os.write(pipe[1], byteArrayOf(1), 0, 1)
            pump()
            check(callbackCount() == 1) { "Java pump did not dispatch the NDK fd callback" }
            pump()
            check(callbackCount() == 1) { "NDK callback returning zero was not removed" }
        } finally {
            detach(pipe[0])
            Os.close(pipe[0])
            Os.close(pipe[1])
        }
        println("stock-ndk-looper: PASS shared Java/NDK callbacks and removal")
    }
}
