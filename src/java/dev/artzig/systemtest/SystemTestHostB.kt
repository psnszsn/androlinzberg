package dev.artzig.systemtest

import android.app.ActivityThread
import android.app.ContextImpl
import android.os.HostedBinderRuntime
import android.os.Looper
import android.os.Process

/**
 * system-test peer: package `dev.artzig.test.b`. Its manifest is the
 * `dev.artzig.test.b` fixture APK (installed server-side; the classes live in
 * framework.jar). Hosts [EchoService] through the session's activity manager
 * and exits 0 when a client calls `exit()` on the service.
 */
internal object SystemTestHostB {
    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        HostedBinderRuntime.bootstrap("dev.artzig.test.b", false)
        val frameworkRes = checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES"))
        // Own package identity (the clipboard's AppOps checkPackage verifies
        // the calling package against this process's uid).
        val context = ContextImpl(
            ContextImpl.createSystemContext(frameworkRes, 640, 480, 120, 0).resources,
            "dev.artzig.test.b",
            0,
        )
        ActivityThread.configureServiceHost(context, SystemTestHostB::class.java.classLoader)
        // Install this package's fixture APK: the session's package manager
        // parses the real manifest (client A asserts the parsed values).
        val fixtures = checkNotNull(System.getenv("AZK_FIXTURE_APKS")) { "AZK_FIXTURE_APKS is not set" }
        val am = checkNotNull(android.app.HostedServiceClient.activityManagerOrNull())
        check(am.installPackage("$fixtures/dev.artzig.test.b.apk") == "dev.artzig.test.b")
        ActivityThread.attachApplicationHosted("dev.artzig.test.b")
        // Publish the test providers under their parsed ProviderInfos (client
        // A queries them cross-process; the non-exported one asserts the
        // acquisition gate).
        val pm = context.packageManager
        val providerInfo = pm.getProviderInfo(
            android.content.ComponentName("dev.artzig.test.b", TestProvider::class.java.name), 0,
        )
        val secretInfo = pm.getProviderInfo(
            android.content.ComponentName("dev.artzig.test.b", SecretProvider::class.java.name), 0,
        )
        val provider = TestProvider().also { it.attachInfo(context, providerInfo) }
        val secret = SecretProvider().also { it.attachInfo(context, secretInfo) }
        am.publishContentProviders(
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
