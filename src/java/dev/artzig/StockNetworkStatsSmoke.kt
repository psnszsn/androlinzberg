package dev.artzig

import android.net.TrafficStats
import android.net.netstats.StatsResult
import android.os.Parcel
import dev.artzig.system.HostedNetworkCounters
import dev.artzig.system.HostedNetworkStatsService
import dev.artzig.system.LinuxNetworkCounters
import java.io.FileDescriptor
import kotlin.concurrent.thread

internal object StockNetworkStatsSmoke {
    fun run() {
        val text = """Inter-| Receive | Transmit
            face |bytes packets errs drop fifo frame compressed multicast|bytes packets errs drop fifo colls carrier compressed
            lo: 100 2 0 0 0 0 0 0 200 3 0 0 0 0 0 0
            eth0: 700 11 0 0 0 0 0 0 900 13 0 0 0 0 0 0
        """.trimIndent()
        var snapshot = LinuxNetworkCounters.parse(text)
        check(snapshot?.get("lo") == StatsResult(100, 2, 200, 3))
        check(LinuxNetworkCounters.parse("garbage") == null)
        check(LinuxNetworkCounters.parse(text.replace("700", "-1")) == null)
        check(LinuxNetworkCounters.parse(text.replace("700", "9223372036854775808")) == null)
        check(LinuxNetworkCounters.parse(text.replace("eth0:", "lo:")) == null)
        val service = HostedNetworkStatsService(HostedNetworkCounters { snapshot })
        check(runCatching { service.getIfaceStats(null) }.exceptionOrNull() is NullPointerException)
        val parcel = Parcel.obtain()
        try {
            val total = checkNotNull(service.totalStats)
            total.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            check(StatsResult.CREATOR.createFromParcel(parcel) == StatsResult(800, 13, 1100, 16))
        } finally { parcel.recycle() }
        TrafficStats.setServiceForTest(service)
        try {
            TrafficStats.reinitRateLimitCacheForTest()
            check(TrafficStats.getTotalRxBytes() == 800L && TrafficStats.getTotalTxBytes() == 1100L)
            check(TrafficStats.getTotalRxPackets() == 13L && TrafficStats.getTotalTxPackets() == 16L)
            check(TrafficStats.getRxBytes("eth0") == 700L && TrafficStats.getTxPackets("lo") == 3L)
            check(TrafficStats.getRxBytes("missing") == TrafficStats.UNSUPPORTED.toLong())
            check(TrafficStats.getRxBytes("../../etc/passwd") == TrafficStats.UNSUPPORTED.toLong())
            check(TrafficStats.getUidRxBytes(android.os.Process.myUid()) == TrafficStats.UNSUPPORTED.toLong())
            check(TrafficStats.getUidTxPackets(android.os.Process.myUid()) == TrafficStats.UNSUPPORTED.toLong())
            snapshot = null
            check(TrafficStats.getTotalRxBytes() == TrafficStats.UNSUPPORTED.toLong())
            check(runCatching { service.getIfaceStats(null) }.exceptionOrNull() is NullPointerException)
            snapshot = mapOf("a" to StatsResult(Long.MAX_VALUE, 0, 0, 0), "b" to StatsResult(1, 0, 0, 0))
            check(TrafficStats.getTotalRxBytes() == TrafficStats.UNSUPPORTED.toLong())
        } finally {
            TrafficStats.setServiceForTest(null)
        }

        TrafficStats.clearThreadStatsTag()
        TrafficStats.clearThreadStatsUid()
        check(TrafficStats.getAndSetThreadStatsTag(0x1234) == -1)
        TrafficStats.setThreadStatsUid(12345)
        var childTag = 0
        var childUid = 0
        thread {
            childTag = TrafficStats.getThreadStatsTag()
            childUid = TrafficStats.getThreadStatsUid()
            TrafficStats.setThreadStatsTag(99)
            TrafficStats.setThreadStatsUid(10001)
        }.join()
        check(childTag == -1 && childUid == -1)
        check(TrafficStats.getThreadStatsTag() == 0x1234 && TrafficStats.getThreadStatsUid() == 12345)
        TrafficStats.clearThreadStatsTag()
        TrafficStats.clearThreadStatsUid()
        check(TrafficStats.getThreadStatsTag() == -1 && TrafficStats.getThreadStatsUid() == -1)

        // Exercise the registered JNI surface without claiming an accounting backend exists.
        val tag = TrafficStats::class.java.getDeclaredMethod("native_tagSocketFd",
            FileDescriptor::class.java, Integer.TYPE, Integer.TYPE).apply { isAccessible = true }
        val untag = TrafficStats::class.java.getDeclaredMethod("native_untagSocketFd",
            FileDescriptor::class.java).apply { isAccessible = true }
        check(tag.invoke(null, FileDescriptor(), 1, -1) == -android.system.OsConstants.EBADF)
        check(untag.invoke(null, FileDescriptor()) == -android.system.OsConstants.EBADF)
        val pipe = android.system.Os.pipe2(android.system.OsConstants.O_CLOEXEC)
        try {
            check(tag.invoke(null, pipe[0], 1, -1) == -android.system.OsConstants.ENOTSOCK)
        } finally { pipe.forEach { android.system.Os.close(it) } }
        check(!checkNotNull(LinuxNetworkCounters.snapshot()).isEmpty())
    }
}
