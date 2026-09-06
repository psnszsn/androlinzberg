package dev.artzig

import android.net.TrafficStats
import android.os.StrictMode
import android.system.OsConstants
import android.util.Log
import java.io.FileDescriptor

/**
 * Pinned TrafficStats.SocketTagger ordering at the host accounting boundary.
 * Stock TrafficStats still pulls in the unfinished NetworkStats/audio closure.
 * Linux sockets work normally; Android's per-UID/tag BPF accounting is absent.
 */
object HostedTrafficSocketTagger : dalvik.system.SocketTagger() {
    @JvmStatic fun install() { set(this) }

    override fun tag(socketDescriptor: FileDescriptor) {
        val tag = TrafficStats.getThreadStatsTag()
        if (tag == -1) StrictMode.noteUntaggedSocket()
        if (tag == -1) return
        unavailable(socketDescriptor, "tagSocket", tag)
    }

    override fun untag(socketDescriptor: FileDescriptor) {
        val tag = TrafficStats.getThreadStatsTag()
        if (tag == -1) return
        unavailable(socketDescriptor, "untagSocket", tag)
    }

    private fun unavailable(descriptor: FileDescriptor, operation: String, tag: Int) {
        val errno = if (descriptor.valid()) OsConstants.ENOSYS else OsConstants.EBADF
        if (errno == OsConstants.ENOSYS) {
            ArtzigTestDiagnostics.reportMissing("android.net.TrafficStats", operation,
                "Android per-UID socket accounting is unavailable (tag=$tag)")
        }
        // The AOSP tagger logs negative native errors rather than throwing a
        // SocketException or preventing the socket from connecting.
        Log.i("TrafficStats", "$operation failed with errno ${-errno}")
    }
}
