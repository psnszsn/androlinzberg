package dev.artzig

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor

/**
 * Native TrafficStats boundary following netd/client/NetdClient.cpp checkSocket
 * and FwmarkClient.send. There is no hosted fwmark server: AOSP returns success
 * when that server cannot be connected. Keep the missing-accounting diagnostic;
 * this does not assign a UID/tag or claim to collect Android BPF counters.
 */
object HostedSocketAccounting {
    @JvmStatic fun tagSocketFd(fd: FileDescriptor, tag: Int, uid: Int): Int =
        unavailable(fd, "tagSocket", "tag=$tag, uid=$uid")

    @JvmStatic fun untagSocketFd(fd: FileDescriptor): Int =
        unavailable(fd, "untagSocket", "remove socket tag")

    private fun unavailable(fd: FileDescriptor, operation: String, detail: String): Int {
        if (!fd.valid()) return -OsConstants.EBADF
        val family = try {
            Os.getsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_DOMAIN)
        } catch (e: ErrnoException) {
            return -e.errno
        }
        if (family != OsConstants.AF_INET && family != OsConstants.AF_INET6) {
            return -OsConstants.EAFNOSUPPORT
        }
        ArtzigTestDiagnostics.reportMissing("android.net.TrafficStats", operation,
            "Android per-UID socket accounting is unavailable ($detail)")
        return 0
    }
}
