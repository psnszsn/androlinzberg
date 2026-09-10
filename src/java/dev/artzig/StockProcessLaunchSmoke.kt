package dev.artzig

import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Build
import android.os.Parcel
import android.os.Process
import android.os.ServiceManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Stock Process/ZygoteProcess wire contract observed in a real exec child. */
internal object StockProcessLaunchSmoke {
    private const val DESCRIPTOR = "dev.artzig.process.launch.report"
    fun run(info: ApplicationInfo) {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val serviceName = "$DESCRIPTOR.${Process.myPid()}"
        var identity = intArrayOf()
        var report = intArrayOf()
        var text: String? = null
        val observer = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 1) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(DESCRIPTOR)
                identity = intArrayOf(Binder.getCallingPid(), Binder.getCallingUid())
                report = checkNotNull(data.createIntArray())
                text = data.readString()
                arrived.countDown()
                check(release.await(20, TimeUnit.SECONDS)) { "Process launch test did not release its child" }
                reply!!.writeNoException()
                return true
            }
        }
        ServiceManager.addService(serviceName, observer, true)
        val sdk = 31
        // Valid wire input whose escaped setup exceeds Linux's 128 KiB per-string
        // exec limit. Exercise the same real launch as the Unicode/identity check.
        val expectedText = "launch 雪/🙂 preserved" + "\u0002".repeat(24000)
        var childPid = -1
        try {
            val controller = Process.ZYGOTE_PROCESS.getZygotePid(Build.SUPPORTED_ABIS.first())
            check(controller > 0 && Process.getUidForPid(controller) == Process.SYSTEM_UID)
            val result = Process.start(StockProcessLaunchEntry::class.java.name, "process-launch-contract",
                info.uid, info.uid, null, 0, 0, sdk, info.seInfo,
                info.primaryCpuAbi ?: Build.SUPPORTED_ABIS.first(), null, info.dataDir,
                null, info.packageName, Process.ZYGOTE_POLICY_FLAG_EMPTY, false, null,
                null, null, false, false, false, arrayOf(serviceName, expectedText))
            childPid = result.pid
            check(childPid > 0 && childPid != controller && childPid != Process.myPid() && !result.usingWrapper)
            check(arrived.await(20, TimeUnit.SECONDS)) { "Stock process entry did not report" }
            check(identity.contentEquals(intArrayOf(childPid, info.uid)))
            check(report.contentEquals(intArrayOf(childPid, info.uid, info.uid, info.uid, sdk)))
            check(text == expectedText)
            check(Process.getUidForPid(childPid) == info.uid)
            check(runCatching {
                Process.start(StockProcessLaunchEntry::class.java.name, "wrong-package-uid",
                    info.uid + 1, info.uid + 1, null, 0, 0, sdk, info.seInfo,
                    Build.SUPPORTED_ABIS.first(), null, info.dataDir, null, info.packageName,
                    Process.ZYGOTE_POLICY_FLAG_EMPTY, false, null, null, null,
                    false, false, false, arrayOf(serviceName, "denied"))
            }.exceptionOrNull() is RuntimeException)
        } finally {
            release.countDown()
            if (childPid > 0) {
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                while (Process.getUidForPid(childPid) >= 0 && android.os.SystemClock.uptimeMillis() < deadline)
                    Thread.sleep(10)
                if (Process.getUidForPid(childPid) >= 0) Process.killProcess(childPid)
            }
        }
        println("Stock Process.start: real PID, kernel UID/GID, target SDK, large Unicode args and wrong-UID rejection")
    }
}

/** Framework entry loaded by the original RuntimeInit.applicationInit. */
object StockProcessLaunchEntry {
    @JvmStatic fun main(arguments: Array<String>) {
        val uid = Process.myUid()
        val status = java.io.File("/proc/self/status").inputStream().bufferedReader().use { it.readLines() }
        fun kernelId(name: String) = status.single { it.startsWith("$name:") }
            .substringAfter(':').trim().split(Regex("\\s+")).first().toInt()
        check(kernelId("Uid") == uid && kernelId("Gid") == uid)
        val binder = checkNotNull(ServiceManager.waitForService(arguments[0]))
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("dev.artzig.process.launch.report")
            data.writeIntArray(intArrayOf(Process.myPid(), uid, android.system.Os.getuid(),
                android.system.Os.getgid(), dalvik.system.VMRuntime.getRuntime().targetSdkVersion))
            data.writeString(arguments[1])
            check(binder.transact(1, data, reply, 0))
            reply.readException()
        } finally { data.recycle(); reply.recycle() }
        System.exit(0)
    }
}
