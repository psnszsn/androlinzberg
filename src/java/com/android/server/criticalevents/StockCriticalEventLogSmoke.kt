package com.android.server.criticalevents

import android.os.Environment
import android.server.ServerProtoEnums
import com.android.server.criticalevents.nano.CriticalEventLogStorageProto
import java.nio.file.Files

/** Exercises the stock ANR log's observable persistence, retention and privacy contracts. */
object StockCriticalEventLogSmoke {
    private class TestLog(directory: String) : CriticalEventLog(
        directory, 3, 1_000, 0, true, LogLoader(),
    ) {
        var now = 10_000L
        override fun getWallTimeMillis() = now
    }

    fun run() {
        val directory = Files.createTempDirectory("critical-events-").toFile()
        try {
            val log = TestLog(directory.path)
            log.logAnr("private-first", ServerProtoEnums.DATA_APP, "first", 10_001, 41)
            log.logJavaCrash("private-second", ServerProtoEnums.DATA_APP, "second", 10_002, 42)
            val first = log.logLinesForTraceFile(ServerProtoEnums.DATA_APP, "first", 10_001)
            check("private-first" in first && "private-second" !in first)
            val system = log.logLinesForSystemServerTraceFile()
            check("private-first" in system && "private-second" in system)

            val stored = CriticalEventLogStorageProto.parseFrom(
                directory.resolve(CriticalEventLog.FILENAME).readBytes(),
            )
            check(stored.events.size == 2)
            check(stored.events[0].anr.subject == "private-first")
            check(stored.events[1].javaCrash.exceptionClass == "private-second")
            check("private-second" in TestLog(directory.path).logLinesForSystemServerTraceFile())

            log.now += 1_001
            check("private-first" !in log.logLinesForSystemServerTraceFile())
            repeat(4) { log.logHalfWatchdog("monitor-$it") }
            val bounded = log.logLinesForSystemServerTraceFile()
            check("monitor-0" !in bounded && "monitor-1" in bounded && "monitor-3" in bounded)
            check(java.io.File(com.android.server.am.StackTracesDumpHelper.ANR_TRACE_DIR) ==
                Environment.getDataDirectory().resolve("anr"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
