package dev.artzig

import android.net.Network
import android.net.NetworkUtils
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.net.InetSocketAddress
import java.net.SocketException

/** Focused coverage for the hosted Network handle and socket-binding boundary. */
internal object ConnectivitySmoke {
    private const val HANDLE_MAGIC = 0xcafed00dL

    @JvmStatic
    fun main(args: Array<String>) {
        check(args.isEmpty()) { "unexpected arguments" }

        check(Network(0).networkHandle == 0L) { "NETID_UNSET handle" }
        val network = Network(42)
        val handle = network.networkHandle
        check(handle == ((42L shl 32) or HANDLE_MAGIC)) { "encoded network handle" }
        check(Network.fromNetworkHandle(handle).netId == 42) { "network handle round trip" }

        val privateDnsNetwork = Network(42, true)
        val privateDnsHandle = privateDnsNetwork.networkHandle
        val privateDnsRoundTrip = Network.fromNetworkHandle(privateDnsHandle)
        check(privateDnsRoundTrip.netId == 42) { "private-DNS handle netId" }
        check(privateDnsRoundTrip.netIdForResolv == (Int.MIN_VALUE or 42)) {
            "private-DNS bypass bit"
        }
        check(privateDnsRoundTrip.networkHandle == privateDnsHandle) {
            "private-DNS handle round trip"
        }

        expectIllegalArgument("zero handle") { Network.fromNetworkHandle(0L) }
        expectIllegalArgument("bad handle magic") { Network.fromNetworkHandle(42L shl 32) }

        val bind = NetworkUtils::class.java.getDeclaredMethod(
            "bindSocketToNetworkHandle",
            FileDescriptor::class.java,
            java.lang.Long.TYPE,
        ).apply { isAccessible = true }
        val malformed = bind.invoke(null, null, 42L shl 32) as Int
        check(malformed == -OsConstants.EINVAL) { "malformed native handle errno: $malformed" }

        val socket = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, 0)
        try {
            check((bind.invoke(null, socket, handle) as Int) == 0) { "valid native handle bind" }
            check((bind.invoke(null, socket, 0L) as Int) == 0) { "unset native handle bind" }
            network.bindSocket(socket)

            Os.connect(socket, InetSocketAddress("127.0.0.1", 9))
            expectSocketException("connected socket") { network.bindSocket(socket) }
        } finally {
            Os.close(socket)
        }

        val invalidFd = FileDescriptor()
        val invalidFdResult = bind.invoke(null, invalidFd, handle) as Int
        check(invalidFdResult == -OsConstants.EBADF) { "invalid socket errno: $invalidFdResult" }

        println("Connectivity smoke: PASS")
    }

    private inline fun expectIllegalArgument(label: String, action: () -> Unit) {
        try {
            action()
            error("$label did not fail")
        } catch (_: IllegalArgumentException) {
        }
    }

    private inline fun expectSocketException(label: String, action: () -> Unit) {
        try {
            action()
            error("$label did not fail")
        } catch (_: SocketException) {
        }
    }
}
