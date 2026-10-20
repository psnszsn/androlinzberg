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
        verify(info, false)
        verify(info, true)
    }

    private fun verify(info: ApplicationInfo, webView: Boolean) {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val serviceName = "$DESCRIPTOR.${Process.myPid()}.$webView"
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
        val uid = if (webView) 99990 else info.uid
        val privateFile = java.io.File(checkNotNull(info.dataDir), "zygote-contract-${Process.myPid()}")
        check(privateFile.parentFile!!.isDirectory || privateFile.parentFile!!.mkdirs())
        privateFile.writeText("private launch fixture")
        var webViewPackage: android.content.pm.PackageInfo? = null
        fun selectProvider(info: android.content.pm.PackageInfo) {
            android.webkit.WebViewZygote::class.java.getDeclaredMethod("onWebViewProviderChanged",
                android.content.pm.PackageInfo::class.java).apply { isAccessible = true }.invoke(null, info)
        }
        fun start(requestedUid: Int, name: String, args: Array<String>): Process.ProcessStartResult =
            if (webView) Process.startWebView(StockProcessLaunchEntry::class.java.name, name,
                requestedUid, requestedUid, null, 0, 0, sdk, info.seInfo,
                Build.SUPPORTED_ABIS.first(), null, info.dataDir, null, info.packageName, null, args)
            else Process.start(StockProcessLaunchEntry::class.java.name, name,
                requestedUid, requestedUid, null, 0, 0, sdk, info.seInfo,
                Build.SUPPORTED_ABIS.first(), null, info.dataDir, null, info.packageName,
                Process.ZYGOTE_POLICY_FLAG_EMPTY, false, null, null, null, false, false, false, args)
        // Valid wire input whose escaped setup exceeds Linux's 128 KiB per-string
        // exec limit. Exercise the same real launch as the Unicode/identity check.
        val expectedText = "launch 雪/🙂 preserved" + "\u0002".repeat(24000)
        var childPid = -1
        try {
            val controller = if (webView) {
                val service = android.webkit.IWebViewUpdateService.Stub.asInterface(
                    ServiceManager.getService(android.content.Context.WEBVIEW_UPDATE_SERVICE))
                webViewPackage = checkNotNull(service.currentWebViewPackage)
                check(webViewPackage!!.applicationInfo!!.primaryCpuAbi in Build.SUPPORTED_ABIS) {
                    "Package manager did not publish the provider's native ABI"
                }
                selectProvider(checkNotNull(webViewPackage))
                val zygote = checkNotNull(android.webkit.WebViewZygote.getProcess()) {
                    "WebView zygote did not start"
                } as android.os.ChildZygoteProcess
                check(zygote.getZygotePid(Build.SUPPORTED_ABIS.first()) == zygote.pid)
                check(Process.getUidForPid(zygote.pid) == Process.WEBVIEW_ZYGOTE_UID)
                zygote.pid
            } else Process.ZYGOTE_PROCESS.getZygotePid(Build.SUPPORTED_ABIS.first()).also {
                check(it > 0 && Process.getUidForPid(it) == Process.SYSTEM_UID)
            }
            val publicApk = webViewPackage?.applicationInfo?.sourceDir ?: info.sourceDir
            val result = start(uid, "process-launch-contract", arrayOf(serviceName, expectedText,
                privateFile.absolutePath, publicApk))
            childPid = result.pid
            check(childPid > 0 && childPid != controller && childPid != Process.myPid() && !result.usingWrapper)
            check(arrived.await(20, TimeUnit.SECONDS)) { "Stock process entry did not report" }
            check(identity.contentEquals(intArrayOf(childPid, uid)))
            check(report.contentEquals(intArrayOf(childPid, uid, uid, uid, sdk, controller,
                if (webView) 0 else 1, 1))) { "Unexpected process report: ${report.toList()}" }
            check(text == expectedText)
            check(Process.getUidForPid(childPid) == uid)
            check(runCatching {
                start(if (webView) info.uid else info.uid + 1, "wrong-package-uid", arrayOf(serviceName, "denied"))
            }.exceptionOrNull() is RuntimeException)
        } finally {
            release.countDown()
            if (childPid > 0) {
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                while (Process.getUidForPid(childPid) >= 0 && android.os.SystemClock.uptimeMillis() < deadline)
                    Thread.sleep(10)
                if (Process.getUidForPid(childPid) >= 0) Process.killProcess(childPid)
            }
            webViewPackage?.let(::selectProvider) // Original provider-change cleanup closes/kills this controller.
            check(privateFile.delete())
        }
        println("Stock Process.${if (webView) "startWebView" else "start"}: real PID/parent, kernel UID/GID, SDK, large Unicode args, data visibility and wrong-UID rejection")
    }
}

/** Framework entry loaded by the original RuntimeInit.applicationInit. */
object StockProcessLaunchEntry {
    @JvmStatic fun main(arguments: Array<String>) {
        val uid = Process.myUid()
        if (Process.isIsolated()) {
            val update = checkNotNull(android.webkit.IWebViewUpdateService.Stub.asInterface(
                ServiceManager.getService(android.content.Context.WEBVIEW_UPDATE_SERVICE))) {
                "Isolated WebView child cannot find its update service"
            }
            val provider = update.waitForAndGetProvider()
            check(provider.status == android.webkit.WebViewFactory.LIBLOAD_SUCCESS)
            check(provider.packageInfo?.applicationInfo?.sourceDir == arguments[3])
        }
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
                android.system.Os.getgid(), dalvik.system.VMRuntime.getRuntime().targetSdkVersion,
                kernelId("PPid"), if (java.io.File(arguments[2]).exists()) 1 else 0,
                if (java.io.File(arguments[3]).canRead()) 1 else 0))
            data.writeString(arguments[1])
            check(binder.transact(1, data, reply, 0))
            reply.readException()
        } finally { data.recycle(); reply.recycle() }
        System.exit(0)
    }
}
