package dev.artzig

import android.net.NetworkUtils
import android.system.ErrnoException
import android.system.GaiException
import android.system.Os
import android.system.OsConstants
import android.system.StructAddrinfo
import com.android.internal.net.NetworkUtilsInternal
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** Exercise original JNI and the actual Java/native libc policy boundary. */
object StockNetworkingSmoke {
    @JvmStatic private external fun nativeSocketError(): Int
    @JvmStatic private external fun nativeDnsError(): Int

    private fun checkNativeNetworking(expected: Int) {
        val socketError = nativeSocketError()
        val dnsError = nativeDnsError()
        check(socketError == expected && dnsError == expected) {
            "native networking: socket=$socketError DNS=$dnsError expected=$expected"
        }
    }

    @JvmStatic
    fun run() {
        val hints = StructAddrinfo().apply {
            ai_family = OsConstants.AF_INET
            ai_flags = OsConstants.AI_NUMERICHOST
            ai_socktype = OsConstants.SOCK_STREAM
        }
        NetworkUtilsInternal.setAllowNetworkingForProcess(true)
        check(NetworkUtils.bindProcessToNetworkForHostResolution(0))
        check(!NetworkUtils.bindProcessToNetworkForHostResolution(99999))
        check(NetworkUtils.getBoundNetworkForProcess() == 0)
        check(Os.android_getaddrinfo("127.0.0.1", hints, 0).isNotEmpty())
        val hostNetwork = dev.artzig.system.HOST_NAMESPACE_NETWORK_ID
        check(Os.android_getaddrinfo("127.0.0.1", hints, hostNetwork).isNotEmpty())
        checkNativeNetworking(0)
        check(NetworkUtils.getDnsNetwork().netId == hostNetwork)
        // The stock raw resolver owns and invalidates the descriptor on result
        // and cancellation. Bad packets and unknown networks fail before DNS I/O.
        val malformed = NetworkUtils.resNetworkSend(0, byteArrayOf(1, 2, 3), 3, 0)
        val malformedError = runCatching { NetworkUtils.resNetworkResult(malformed) }.exceptionOrNull()
        check(malformedError is ErrnoException && malformedError.errno == OsConstants.EINVAL)
        check(!malformed.valid())
        val unknown = NetworkUtils.resNetworkQuery(42,
            "example.com", 1, 1, 0)
        val unknownError = runCatching { NetworkUtils.resNetworkResult(unknown) }.exceptionOrNull()
        check(unknownError is ErrnoException && unknownError.errno == OsConstants.ENONET)
        check(!unknown.valid())
        val canceled = NetworkUtils.resNetworkSend(0, byteArrayOf(1, 2, 3), 3, 0)
        NetworkUtils.resNetworkCancel(canceled)
        check(!canceled.valid())
        val socket = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, 0)
        try {
            check(NetworkUtils.getSocketCookie(socket) > 0)
            NetworkUtils.attachDropAllBPFFilter(socket)
            NetworkUtils.detachBPFFilter(socket)
            check(NetworkUtils.bindSocketToNetwork(socket, 0) == 0)
            check(NetworkUtils.bindSocketToNetwork(socket, hostNetwork) == 0)
            check(NetworkUtils.bindProcessToNetwork(hostNetwork))
            check(NetworkUtils.getBoundNetworkForProcess() == hostNetwork)
            check(NetworkUtils.bindProcessToNetwork(0))
            check(NetworkUtils.bindSocketToNetwork(socket, 99999) == -1) // original JNI errno ABI
            check(!NetworkUtilsInternal.protectFromVpn(socket))
            val tag = android.net.TrafficStats::class.java.getDeclaredMethod("native_tagSocketFd",
                java.io.FileDescriptor::class.java, Integer.TYPE, Integer.TYPE).apply { isAccessible = true }
            check(tag.invoke(null, socket, 123, -1) == -OsConstants.EOPNOTSUPP)
            NetworkUtilsInternal.setAllowNetworkingForProcess(false)
            for (family in listOf(OsConstants.AF_INET, OsConstants.AF_INET6)) {
                val failure = runCatching { Os.socket(family, OsConstants.SOCK_DGRAM, 0) }.exceptionOrNull()
                check(failure is ErrnoException && failure.errno == OsConstants.EPERM) { "socket policy: $failure" }
            }
            check(runCatching { ServerSocket(0).use { error("denied Internet listener opened") } }.exceptionOrNull() is java.net.SocketException)
            check(runCatching { java.nio.channels.SocketChannel.open() }.exceptionOrNull() is java.net.SocketException)
            val dnsFailure = runCatching { Os.android_getaddrinfo("127.0.0.1", hints, 0) }.exceptionOrNull()
            check(dnsFailure is GaiException && dnsFailure.error == OsConstants.EAI_SYSTEM)
            val asyncFailure = runCatching { NetworkUtils.resNetworkQuery(0, "example.com", 1, 1, 0) }.exceptionOrNull()
            check(asyncFailure is ErrnoException && asyncFailure.errno == OsConstants.EPERM)
            checkNativeNetworking(OsConstants.EPERM)
            // AOSP denies new Internet sockets, not existing FDs or local IPC.
            check(Os.getsockoptInt(socket, OsConstants.SOL_SOCKET, OsConstants.SO_TYPE) == OsConstants.SOCK_DGRAM)
            val local = Os.socket(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0)
            Os.close(local)
        } finally {
            NetworkUtilsInternal.setAllowNetworkingForProcess(true)
            check(NetworkUtils.bindProcessToNetwork(0))
            Os.close(socket)
        }
        checkNativeNetworking(0)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            listener.soTimeout = 3000
            Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { client ->
                listener.accept().use { peer ->
                    peer.soTimeout = 3000
                    client.getOutputStream().write(73)
                    check(peer.getInputStream().read() == 73)
                }
            }
        }
        println("stock-networking: PASS original JNI, Java/native deny and restore, DNS gate, loopback and explicit unsupported policies")
    }
}
