package dev.artzig.system

import android.content.Context
import android.net.NetworkStats
import android.os.Binder
import android.os.SystemClock
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
 * Operation reports are stored for the session; snapshots contain those reports only,
 * with no attributed byte/packet observations. Historical queries remain unsupported.
 */
class HostedNetworkStatsService(
    private val counters: HostedNetworkCounters = LinuxNetworkCounters,
    private val context: Context? = null,
) : INetworkStatsService.Stub(), INetworkStatsService by unsupported<INetworkStatsService>() {
    override fun asBinder(): android.os.IBinder = this

    private val operations = NetworkStats(0, 0)

    /** NetworkStatsService.incrementOperationCount: validate before recording both
     * the tagged count and its TAG_NONE aggregate. No Android UID foreground
     * counter sets or BPF traffic maps exist here, so reports use SET_DEFAULT. */
    override fun incrementOperationCount(uid: Int, tag: Int, operationCount: Int) {
        if (Binder.getCallingUid() != uid) {
            val owner = context ?: throw SecurityException("UPDATE_DEVICE_STATS required")
            owner.enforceCallingOrSelfPermission(android.Manifest.permission.UPDATE_DEVICE_STATS, "NetworkStats")
        }
        require(operationCount >= 0) { "operation count can only be incremented" }
        require(tag != NetworkStats.TAG_NONE) { "operation count must have specific tag" }
        synchronized(operations) {
            for (entryTag in intArrayOf(tag, NetworkStats.TAG_NONE)) {
                operations.combineValues(NetworkStats.IFACE_ALL, uid, NetworkStats.SET_DEFAULT,
                    entryTag, 0L, 0L, 0L, 0L, operationCount.toLong())
            }
        }
    }

    /** The stock snapshot API restricts observations to the calling UID. This
     * host can supply reported operations, while UID byte/packet counters remain
     * unavailable through TrafficStats.getUidStats (UNSUPPORTED). */
    override fun getDataLayerSnapshotForUid(uid: Int): NetworkStats {
        val now = SystemClock.elapsedRealtime()
        if (Binder.getCallingUid() != uid) return NetworkStats(now, 0)
        return synchronized(operations) {
            operations.clone().apply {
                filter(uid, NetworkStats.INTERFACES_ALL, NetworkStats.TAG_ALL)
                elapsedRealtime = now
            }
        }
    }

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
