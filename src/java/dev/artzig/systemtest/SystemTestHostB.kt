package dev.artzig.systemtest

import android.app.ActivityThread
import android.app.ContextImpl
import android.os.HostedBinderRuntime
import android.os.Looper
import android.os.Process
import dev.artzig.system.HostedProcessManifest

/**
 * system-test peer: package `dev.artzig.test.b`. Hosts [EchoService] through
 * the session's activity manager and exits 0 when a client calls `exit()` on
 * the service.
 */
internal object SystemTestHostB {
    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        HostedBinderRuntime.bootstrap("dev.artzig.test.b", false)
        val frameworkRes = checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES"))
        val context = ContextImpl.createSystemContext(frameworkRes, 640, 480, 120, 0)
        ActivityThread.configureServiceHost(context, SystemTestHostB::class.java.classLoader)
        // Register this package with the session's package manager so peers can
        // resolve it cross-process (client A asserts these values).
        val echoInfo = android.content.pm.ServiceInfo()
        echoInfo.packageName = "dev.artzig.test.b"
        echoInfo.name = EchoService::class.java.name
        echoInfo.enabled = true
        android.content.pm.PackageManager.configureHostedPackage(
            "dev.artzig.test.b", 7, "7.0", "/nonexistent/b.apk", "/tmp/b-data",
        )
        android.content.pm.PackageManager.configureHostedServices(
            listOf(echoInfo),
            mapOf(echoInfo.name to setOf("dev.artzig.test.ECHO")),
        )
        val hostAppInfo = android.content.pm.ApplicationInfo().apply {
            packageName = "dev.artzig.test.b"
            processName = "dev.artzig.test.b"
        }
        val providerInfo = android.content.pm.ProviderInfo()
        providerInfo.packageName = "dev.artzig.test.b"
        providerInfo.name = TestProvider::class.java.name
        providerInfo.authority = "dev.artzig.test.b.provider"
        providerInfo.exported = true
        providerInfo.applicationInfo = hostAppInfo
        val secretInfo = android.content.pm.ProviderInfo()
        secretInfo.packageName = "dev.artzig.test.b"
        secretInfo.name = TestProvider::class.java.name
        secretInfo.authority = "dev.artzig.test.b.secret"
        secretInfo.exported = false
        secretInfo.applicationInfo = hostAppInfo
        android.content.pm.PackageManager.configureHostedProviders(listOf(providerInfo, secretInfo))
        val manifest = HostedProcessManifest(
            "dev.artzig.test.b",
            "dev.artzig.test.b",
            null,
            listOf(
                HostedProcessManifest.ServiceEntry(
                    EchoService::class.java.name,
                    null,
                    true,
                    listOf("dev.artzig.test.ECHO"),
                ),
            ),
            emptyList(),
        )
        ActivityThread.attachApplicationHosted(manifest)
        // Publish the test providers (client A queries them cross-process; the
        // non-exported one asserts the acquisition gate).
        val provider = TestProvider().also { it.attachInfo(context, providerInfo) }
        val secret = TestProvider().also { it.attachInfo(context, secretInfo) }
        android.app.HostedServiceClient.activityManagerOrNull()!!.publishContentProviders(
            listOf(
                dev.artzig.system.HostedContentProviderHolder(
                    providerInfo,
                    provider.getIContentProvider().asBinder(),
                ),
                dev.artzig.system.HostedContentProviderHolder(
                    secretInfo,
                    secret.getIContentProvider().asBinder(),
                ),
            ),
        )
        System.err.println("system-test-host-b: attached pid=" + Process.myPid() + " uid=" + Process.myUid())
        Thread {
            val exited = EchoService.exitLatch.await(120, java.util.concurrent.TimeUnit.SECONDS)
            System.err.println("system-test-host-b: exiting (requested=" + exited + ", unbound=" + EchoService.unbound.get() + ")")
            Runtime.getRuntime().exit(if (exited && EchoService.unbound.get()) 0 else 3)
        }.apply { isDaemon = true }.start()
        Looper.loop()
    }
}
