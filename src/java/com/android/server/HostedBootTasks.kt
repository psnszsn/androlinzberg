package com.android.server

import android.util.Dumpable
import java.io.PrintWriter

/**
 * SystemServer's package-private pool startup and hosted failure diagnostics.
 * Android's StackTracesDumpHelper writes /data/anr and asks debuggerd/Watchdog
 * about device daemons. This process instead dumps its ART threads to stderr;
 * pool scheduling, timeouts, shutdown ordering and exceptions remain AOSP.
 */
object HostedBootTasks {
    @JvmStatic
    fun start(): Dumpable = SystemServerInitThreadPool.start()

    @JvmStatic
    fun dumpInitThreads() {
        val writer = PrintWriter(System.err)
        writeThreadDump(writer)
        writer.flush()
    }

    internal fun writeThreadDump(writer: PrintWriter) {
        writer.println("Hosted system initialization thread dump")
        for ((thread, stack) in Thread.getAllStackTraces().entries.sortedBy { it.key.name }) {
            writer.println("\"${thread.name}\" id=${thread.id} state=${thread.state}")
            for (frame in stack) writer.println("    at $frame")
        }
    }
}
