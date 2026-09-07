package dev.artzig.system

import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.RouteInfo
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface

/** The default route in the host network namespace, observed without changing its routing. */
internal data class HostedNetworkSnapshot(
    val network: Network,
    val info: NetworkInfo,
    val capabilities: NetworkCapabilities,
    val links: LinkProperties,
)

internal fun interface HostedNetworkSource {
    fun snapshot(): HostedNetworkSnapshot?
    fun supportsLegacyType(type: Int): Boolean = snapshot()?.info?.type == type
}

/** Linux replaces netd/NetworkAgent discovery; Android values and callbacks remain stock. */
internal class LinuxNetworkSource : HostedNetworkSource {
    private val ids = HashMap<String, Int>()
    private var nextId = 100
    private data class Route(val iface: String, val gateway: InetAddress, val metric: Long)

    override fun supportsLegacyType(type: Int): Boolean = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { iface ->
            !iface.isLoopback && type == if (File("/sys/class/net/${iface.name}/wireless").isDirectory)
                ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_ETHERNET
        }
    } catch (_: IOException) { false } catch (_: SecurityException) { false }

    @Synchronized
    override fun snapshot(): HostedNetworkSnapshot? = try {
        val routes = ipv4Routes() + ipv6Routes()
        val selected = routes.sortedBy { it.metric }.firstOrNull { route ->
            NetworkInterface.getByName(route.iface)?.let { it.isUp && !it.isLoopback } == true
        }
        selected?.let { route ->
            val iface = checkNotNull(NetworkInterface.getByName(route.iface))
            val wifi = File("/sys/class/net/${route.iface}/wireless").isDirectory
            val type = if (wifi) ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_ETHERNET
            val info = NetworkInfo(type, 0, if (wifi) "WIFI" else "ETHERNET", "").apply {
                setIsAvailable(true)
                setDetailedState(NetworkInfo.DetailedState.CONNECTED, null, route.iface)
            }
            val links = LinkProperties().apply {
                interfaceName = route.iface
                mtu = iface.mtu
                for (address in iface.interfaceAddresses) {
                    addLinkAddress(LinkAddress(address.address, address.networkPrefixLength.toInt()))
                }
                for (entry in routes.filter { it.iface == route.iface }) {
                    val bits = if (entry.gateway.address.size == 4) 4 else 16
                    addRoute(RouteInfo(IpPrefix(InetAddress.getByAddress(ByteArray(bits)), 0),
                        entry.gateway, route.iface, RouteInfo.RTN_UNICAST))
                }
                for (line in read("/etc/resolv.conf").lineSequence()) {
                    val fields = line.substringBefore('#').trim().split(Regex("\\s+"))
                    if (fields.size >= 2 && fields[0] == "nameserver") {
                        runCatching { InetAddresses.parseNumericAddress(fields[1]) }.getOrNull()?.let(::addDnsServer)
                    }
                }
            }
            val capabilities = NetworkCapabilities().apply {
                addTransportType(if (wifi) NetworkCapabilities.TRANSPORT_WIFI else NetworkCapabilities.TRANSPORT_ETHERNET)
                addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                // EthernetTracker supplies these for non-cellular, non-VCN networks.
                // Linux discovery here exposes only Ethernet/Wi-Fi and has no Android VCN.
                addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
                addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
                // HostedNetworkPolicyService has no metered transport policy. A route is
                // not evidence of Internet validation, so VALIDATED is deliberately absent.
                addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
            HostedNetworkSnapshot(Network(ids.getOrPut(route.iface) { nextId++ }), info, capabilities, links)
        }
    } catch (_: IOException) { null } catch (_: SecurityException) { null }

    private fun read(path: String): String = try { File(path).readText() } catch (_: IOException) { "" }

    private fun ipv4Routes(): List<Route> = read("/proc/net/route").lineSequence().drop(1).mapNotNull { line ->
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 8 || fields[1] != "00000000" || fields[7] != "00000000") return@mapNotNull null
        val flags = fields[3].toIntOrNull(16) ?: return@mapNotNull null
        if (flags and 1 == 0) return@mapNotNull null
        val gateway = fields[2].toLongOrNull(16) ?: return@mapNotNull null
        Route(fields[0], InetAddress.getByAddress(ByteArray(4) { (gateway shr (8 * it)).toByte() }),
            fields[6].toLongOrNull() ?: return@mapNotNull null)
    }.toList()

    private fun ipv6Routes(): List<Route> = read("/proc/net/ipv6_route").lineSequence().mapNotNull { line ->
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 10 || fields[0] != "0".repeat(32) || fields[1] != "00") return@mapNotNull null
        val flags = fields[8].toIntOrNull(16) ?: return@mapNotNull null
        if (flags and 1 == 0 || fields[4].length != 32) return@mapNotNull null
        val gateway = fields[4].chunked(2).map { it.toIntOrNull(16)?.toByte() ?: return@mapNotNull null }.toByteArray()
        Route(fields[9], InetAddress.getByAddress(gateway), fields[5].toLongOrNull(16) ?: return@mapNotNull null)
    }.toList()
}
