package dev.artzig

import android.os.Process
import java.io.File

/** Public Process naming must update the real Linux metadata through AndroidRuntime. */
internal object StockRuntimeMetadataSmoke {
    fun run() {
        fun arguments() = File("/proc/self/cmdline").readBytes().takeWhile { it != 0.toByte() }
            .toByteArray().toString(Charsets.UTF_8)
        fun threadName() = File("/proc/self/task/${Process.myTid()}/comm").readText().trimEnd('\n')
        val previous = Process.myProcessName() ?: arguments()
        try {
            val name = "dev.artzig.original.runtime.é.metadata"
            Process.setArgV0(name)
            check(arguments() == name) { "Process name did not reach exec argument storage" }
            check(threadName() == name.toByteArray().takeLast(15).toByteArray().toString(Charsets.UTF_8))
            check(Process.myProcessName() == name)
            Process.setArgV0("")
            check(arguments() == name) { "Empty name changed native metadata" }
            check(Process.myProcessName() == "")
            Process.setArgV0("short-name")
            check(arguments() == "short-name" && threadName() == "short-name")
            val invalid = runCatching {
                Process::class.java.getMethod("setArgV0", String::class.java).invoke(null, null)
            }.exceptionOrNull()
            check(invalid is java.lang.reflect.InvocationTargetException && invalid.cause is NullPointerException)
        } finally {
            Process.setArgV0(previous)
        }
        println("stock-runtime-metadata: PASS exec argv, thread-name suffix, UTF-8 and empty-name semantics")
    }
}
