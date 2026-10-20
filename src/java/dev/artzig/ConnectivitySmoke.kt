package dev.artzig

import android.net.InetAddresses
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkUtils
import android.net.ProxyInfo
import android.net.RouteInfo
import android.os.Parcel
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

        dev.artzig.system.LinuxNetworkSource().snapshot()?.let { active ->
            check(active.info.isConnected)
            check(active.capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_FOREGROUND)) {
                "The host default route must be usable by foreground network requests"
            }
        }

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

        val linkAddress = LinkAddress("192.0.2.5/24")
        check(linkAddress.isIpv4) { "stock LinkAddress IPv4 classification" }
        check(linkAddress.prefixLength == 24) { "stock LinkAddress prefix" }
        check(linkAddress.toString() == "192.0.2.5/24") { "stock LinkAddress string" }

        val properties = LinkProperties().apply {
            interfaceName = "host0"
            check(addLinkAddress(linkAddress)) { "add stock link address" }
            check(addDnsServer(InetAddresses.parseNumericAddress("192.0.2.53"))) {
                "add stock DNS server"
            }
            check(addRoute(RouteInfo(LinkAddress("192.0.2.0/24"), null, "host0"))) {
                "add stock route"
            }
            httpProxy = ProxyInfo.buildDirectProxy("proxy.example", 8080)
            mtu = 1500
        }
        val parcel = Parcel.obtain()
        val propertiesRoundTrip = try {
            properties.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            checkNotNull(LinkProperties.CREATOR.createFromParcel(parcel))
        } finally {
            parcel.recycle()
        }
        check(propertiesRoundTrip == properties) { "stock LinkProperties parcel round trip" }
        check(propertiesRoundTrip.interfaceName == "host0") { "stock interface name" }
        check(propertiesRoundTrip.linkAddresses == listOf(linkAddress)) { "stock link addresses" }
        check(propertiesRoundTrip.dnsServers.single().hostAddress == "192.0.2.53") {
            "stock DNS servers"
        }
        check(propertiesRoundTrip.routes.single().`interface` == "host0") { "stock routes" }
        check(propertiesRoundTrip.httpProxy?.host == "proxy.example") { "stock proxy" }
        check(propertiesRoundTrip.mtu == 1500) { "stock MTU" }

        expectIllegalArgument("zero handle") { Network.fromNetworkHandle(0L) }
        expectIllegalArgument("bad handle magic") { Network.fromNetworkHandle(42L shl 32) }

        val bind = NetworkUtils::class.java.getDeclaredMethod(
            "bindSocketToNetworkHandle",
            FileDescriptor::class.java,
            java.lang.Long.TYPE,
        ).apply { isAccessible = true }
        // Pinned JNI delegates the NDK's POSIX return ABI: -1 on failure.
        val malformed = bind.invoke(null, FileDescriptor(), 42L shl 32) as Int
        check(malformed == -1) { "malformed native handle result: $malformed" }

        val socket = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, 0)
        try {
            check((bind.invoke(null, socket, handle) as Int) == -1) { "unknown network must fail" }
            expectSocketException("unknown network") { network.bindSocket(socket) }
            val hostNetwork = Network(dev.artzig.system.HOST_NAMESPACE_NETWORK_ID)
            check((bind.invoke(null, socket, hostNetwork.networkHandle) as Int) == 0) { "host namespace bind" }
            check((bind.invoke(null, socket, 0L) as Int) == 0) { "unset native handle bind" }
            hostNetwork.bindSocket(socket)

            Os.connect(socket, InetSocketAddress("127.0.0.1", 9))
            expectSocketException("connected socket") { hostNetwork.bindSocket(socket) }
        } finally {
            Os.close(socket)
        }

        val invalidFd = FileDescriptor()
        val invalidFdResult = bind.invoke(null, invalidFd, handle) as Int
        check(invalidFdResult == -1) { "invalid socket result: $invalidFdResult" }

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
