package dev.artzig.systemtest

import android.app.ActivityThread
import android.os.Binder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.ServiceManager

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
        // Package identity comes from AZK_BINDER_PACKAGE (set by the harness);
        // the binder connection is established lazily on first use.
        val frameworkRes = checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES"))
        // Own package identity (the clipboard's AppOps checkPackage verifies
        // the calling package against this process's uid).
        dev.artzig.HostContexts.systemContext(frameworkRes, 640, 480, 120, 0)
        // Install this package's fixture APK: the session's package manager
        // parses the real manifest (client A asserts the parsed values).
        val fixtures = checkNotNull(System.getenv("AZK_FIXTURE_APKS")) { "AZK_FIXTURE_APKS is not set" }
        val installer = checkNotNull(android.app.HostedServiceClient.installerOrNull())
        check(installer.installPackage("$fixtures/dev.artzig.test.b.apk", null) == "dev.artzig.test.b")
        ActivityThread.attachApplicationHosted("dev.artzig.test.b")
        // AOSP handleBindApplication: the test providers are installed from their
        // parsed ProviderInfos and published before Application.onCreate (client
        // A queries them cross-process; the non-exported one asserts the gate).
        val ipm = ActivityThread.getPackageManager()
        val providerInfo = checkNotNull(ipm.getProviderInfo(
            android.content.ComponentName("dev.artzig.test.b", TestProvider::class.java.name), 0L, 0,
        ))
        val secretInfo = checkNotNull(ipm.getProviderInfo(
            android.content.ComponentName("dev.artzig.test.b", SecretProvider::class.java.name), 0L, 0,
        ))
        val appInfo = checkNotNull(ipm.getApplicationInfo("dev.artzig.test.b", 0L, 0))
        val context: android.content.Context = ActivityThread.currentActivityThread()
            .`handleBindApplication$artzig`(appInfo, listOf(providerInfo, secretInfo))
        // A plain Binder service the cross-language native client (libbinder_ndk)
        // looks up and calls remotely; onTransact enforces the same interface
        // descriptor the native side's prepareTransaction writes as a token.
        ServiceManager.addService("dev.artzig.test.b.ndkprobe", NdkProbeService())
        // A real android.os.Messenger over the binder stack: replies what+1 /
        // arg1*2 through msg.replyTo (client A asserts the cross-process round trip).
        val messengerHandler = android.os.Handler(Looper.getMainLooper()) { msg ->
            val reply = android.os.Message.obtain(null, msg.what + 1, msg.arg1 * 2, 0)
            reply.data = android.os.Bundle().apply { putString("from", "b") }
            msg.replyTo?.send(reply)
            true
        }
        ServiceManager.addService("dev.artzig.test.b.messenger", android.os.Messenger(messengerHandler).binder)
        System.err.println("system-test-host-b: attached pid=" + Process.myPid() + " uid=" + Process.myUid())
        Thread {
            val exited = EchoService.exitLatch.await(120, java.util.concurrent.TimeUnit.SECONDS)
            System.err.println("system-test-host-b: exiting (requested=" + exited + ", unbound=" + EchoService.unbound.get() + ")")
            Runtime.getRuntime().exit(if (exited && EchoService.unbound.get()) 0 else 3)
        }.apply { isDaemon = true }.start()
        Looper.loop()
    }

    /** Serves `dev.artzig.test.b.INdkProbe`: add(a,b) and echo(String). */
    private class NdkProbeService : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                1 -> { // add(a, b) -> a + b
                    data.enforceInterface("dev.artzig.test.b.INdkProbe")
                    val a = data.readInt()
                    val b = data.readInt()
                    reply?.writeInt(a + b)
                    return true
                }
                2 -> { // echo(String) -> String
                    data.enforceInterface("dev.artzig.test.b.INdkProbe")
                    reply?.writeString(data.readString())
                    return true
                }
                3 -> { // fireOneway(n): send n ordered oneway calls to the native sink
                    data.enforceInterface("dev.artzig.test.b.INdkProbe")
                    val n = data.readInt()
                    val sink = ServiceManager.waitForService("dev.artzig.ndkclient.sink")
                    for (i in 1..n) {
                        val d = Parcel.obtain()
                        try {
                            sink.transact(i, d, null, android.os.IBinder.FLAG_ONEWAY)
                        } finally {
                            d.recycle()
                        }
                    }
                    reply?.writeInt(n)
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }
}
