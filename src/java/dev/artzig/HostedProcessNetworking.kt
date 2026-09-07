package dev.artzig

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor

/** com_android_internal_net_NetworkUtilsInternal.cpp / NetdClient.checkSocket and
 * FwmarkClient.send's absent-fwmarkd path. This host has no Android VPN/netd service;
 * ordinary Internet sockets keep their existing Linux routing and are borrowed here. */
internal object HostedProcessNetworking {
    @JvmStatic fun protectFromVpn(descriptor: FileDescriptor): Boolean {
        if (!descriptor.valid()) return false
        return try {
            val family = Os.getsockoptInt(descriptor, OsConstants.SOL_SOCKET, OsConstants.SO_DOMAIN)
            (family == OsConstants.AF_INET || family == OsConstants.AF_INET6) &&
                !(Os.geteuid() == 0 && System.getenv("ANDROID_NO_USE_FWMARK_CLIENT") != null)
        } catch (_: ErrnoException) { false }
    }

    @JvmStatic fun protectFromVpn(rawFd: Int): Boolean =
        protectFromVpn(FileDescriptor().apply { `setInt$`(rawFd) })

    @JvmStatic fun setAllowNetworkingForProcess(allowed: Boolean) {
        // NetdClient's Boolean gates both socket creation and resolver entry points in
        // bionic. Hosted libc has no such hooks. The default allowed state needs no
        // change; deny must fail explicitly rather than claim sockets were restricted.
        if (!allowed) throw ArtzigTestDiagnostics.unsupported(
            "com.android.internal.net.NetworkUtilsInternal", "setAllowNetworkingForProcess",
            "Process-wide socket and DNS denial requires hosted libc network-policy hooks")
    }
}
