package dev.artzig.system

import android.net.INetworkStatsService
import android.net.netstats.StatsResult
import android.net.netstats.TrafficStatsRateLimitCacheConfig
import java.io.File
import java.io.IOException

/** One coherent Linux interface-counter observation; null means the source is unavailable. */
fun interface HostedNetworkCounters {
    fun snapshot(): Map<String, StatsResult>?
}

object LinuxNetworkCounters : HostedNetworkCounters {
    override fun snapshot(): Map<String, StatsResult>? = try {
        parse(File("/proc/net/dev").readText())
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    internal fun parse(text: String): Map<String, StatsResult>? {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2 || !lines[0].contains("Inter-") || !lines[1].contains("bytes")) return null
        val result = LinkedHashMap<String, StatsResult>()
        for (line in lines.drop(2)) {
            val colon = line.lastIndexOf(':')
            if (colon < 1) return null
            val name = line.substring(0, colon).trim()
            val fields = line.substring(colon + 1).trim().split(Regex("\\s+"))
            if (name.isEmpty() || fields.size != 16 || result.containsKey(name)) return null
            val values = fields.map { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: return null }
            result[name] = StatsResult(values[0], values[1], values[8], values[9])
        }
        return result
    }
}

/**
 * Follows NetworkStatsService.get{Iface,Total,Uid}Stats: unavailable observations
 * return null, which stock TrafficStats maps to UNSUPPORTED. Android BPF maps and
 * UID attribution are absent; Linux supplies counters for this network namespace.
 * Historical queries and provider accounting remain explicit unsupported operations.
 */
class HostedNetworkStatsService(
    private val counters: HostedNetworkCounters = LinuxNetworkCounters,
) : INetworkStatsService.Stub(), INetworkStatsService by unsupported<INetworkStatsService>() {
    override fun asBinder(): android.os.IBinder = this

    override fun getIfaceStats(iface: String?): StatsResult? {
        java.util.Objects.requireNonNull(iface)
        return counters.snapshot()?.get(iface)
    }

    override fun getTotalStats(): StatsResult? {
        val snapshot = counters.snapshot() ?: return null
        var rxBytes = 0L
        var rxPackets = 0L
        var txBytes = 0L
        var txPackets = 0L
        try {
            for (entry in snapshot.values) {
                rxBytes = Math.addExact(rxBytes, entry.rxBytes)
                rxPackets = Math.addExact(rxPackets, entry.rxPackets)
                txBytes = Math.addExact(txBytes, entry.txBytes)
                txPackets = Math.addExact(txPackets, entry.txPackets)
            }
        } catch (_: ArithmeticException) {
            return null
        }
        return StatsResult(rxBytes, rxPackets, txBytes, txPackets)
    }

    override fun getUidStats(uid: Int): StatsResult? = null

    // No hosted mobile transport or client-side cache: observe the current counters.
    override fun getMobileIfaces(): Array<String> = emptyArray()
    override fun getRateLimitCacheConfig() = TrafficStatsRateLimitCacheConfig(false, 0, 0)
}
