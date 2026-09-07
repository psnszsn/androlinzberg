@file:Suppress("DEPRECATION")

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
        dev.artzig.HostContexts.prepareApplicationEnvironment(frameworkRes)
        dev.artzig.HostContexts.configureDisplay(640, 480, 120)
        // Install this package's fixture APK: the session's package manager
        // parses the real manifest (client A asserts the parsed values).
        val fixture = checkNotNull(System.getenv("AZK_FIXTURE_APK_B")) { "AZK_FIXTURE_APK_B is not set" }
        val installer = checkNotNull(dev.artzig.HostedServiceClient.installerOrNull())
        check(installer.installPackage(fixture, null) == "dev.artzig.test.b")
        // The stock bindApplication message installs and publishes the parsed
        // providers before Application.onCreate.
        val context: android.content.Context =
            dev.artzig.HostedServiceClient.attachApplication("dev.artzig.test.b")
        check(ApplicationBindingEvents.events == listOf("application:attach",
            "provider:SecretProvider", "provider:TestProvider", "application:create")) {
            "application/provider startup order: ${ApplicationBindingEvents.events}"
        }
        val wifi = checkNotNull(context.getSystemService(android.net.wifi.WifiManager::class.java))
        check(runCatching { wifi.wifiState }.exceptionOrNull() is SecurityException)
        check(runCatching { wifi.createWifiLock("denied").acquire() }.exceptionOrNull() is SecurityException)
        check(runCatching { wifi.createMulticastLock("denied").acquire() }.exceptionOrNull() is SecurityException)
        val fingerprints = checkNotNull(context.getSystemService(android.hardware.fingerprint.FingerprintManager::class.java))
        val companions = android.companion.ICompanionDeviceManager.Stub.asInterface(
            android.os.ServiceManager.getService(android.content.Context.COMPANION_DEVICE_SERVICE))
        check(companions.getAssociations(context.packageName, 0).isEmpty())
        check(runCatching { companions.getAssociations("dev.artzig.test.a", 0) }.exceptionOrNull() is SecurityException)
        check(runCatching { companions.getAllAssociationsForUser(0) }.exceptionOrNull() is SecurityException)
        val associationCallback = object : android.companion.IAssociationRequestCallback.Stub() {
            override fun onAssociationPending(intent: android.app.PendingIntent) { error("unauthorized association") }
            override fun onAssociationCreated(info: android.companion.AssociationInfo) { error("unauthorized association") }
            override fun onFailure(code: Int, error: CharSequence?) {}
        }
        check(runCatching {
            companions.associate(android.companion.AssociationRequest.Builder()
                .setSelfManaged(true).setDisplayName("Unauthorized").build(),
                associationCallback, context.packageName, 0)
        }.exceptionOrNull() is SecurityException)
        val networkScores = checkNotNull(context.getSystemService(android.net.NetworkScoreManager::class.java))
        check(runCatching { networkScores.clearScores() }.exceptionOrNull() is SecurityException)
        check(runCatching { networkScores.allValidScorers }.exceptionOrNull() is SecurityException)
        check(runCatching { networkScores.updateScores(emptyArray()) }.exceptionOrNull() is SecurityException)
        check(runCatching { fingerprints.isHardwareDetected }.exceptionOrNull() is SecurityException) {
            "fingerprint capability queries must enforce USE_FINGERPRINT/USE_BIOMETRIC"
        }
        check(runCatching { fingerprints.sensorPropertiesInternal }.exceptionOrNull() is SecurityException) {
            "fingerprint sensor metadata must enforce the internal permission"
        }
        StockAppOpsSystemSmoke.run(context)
        StockPermissionCheckerSystemSmoke.run(context)
        // A plain Binder service the cross-language native client (libbinder_ndk)
        // looks up and calls remotely; onTransact enforces the same interface
        // descriptor the native side's prepareTransaction writes as a token.
        ServiceManager.addService("dev.artzig.test.b.ndkprobe", NdkProbeService())
        MemoryIntArraySystemSmoke.publish()
        HardwareBufferSystemSmoke.publish()
        SurfaceSyncSystemSmoke.publish()
        ActivityRecreationSystemSmoke.publish()
        ActivityUriGrantSystemSmoke.publish(context)
        // A real android.os.Messenger over the binder stack: replies what+1 /
        // arg1*2 through msg.replyTo (client A asserts the cross-process round trip).
        val messengerHandler = android.os.Handler(Looper.getMainLooper()) { msg ->
            val reply = android.os.Message.obtain(null, msg.what + 1, msg.arg1 * 2, 0)
            reply.data = android.os.Bundle().apply { putString("from", "b") }
            msg.replyTo?.send(reply)
            true
        }
        ServiceManager.addService("dev.artzig.test.b.messenger", android.os.Messenger(messengerHandler).binder)
        // A real MediaSession over the hosted ISessionManager: client A controls it through
        // a MediaController built from the token this process publishes, and through the
        // media-key dispatch of the session manager.
        val mediaSession = android.media.session.MediaSession(context, "azk-test-b")
        mediaSession.setCallback(object : android.media.session.MediaSession.Callback() {
            // MediaSession.Callback.onMediaButtonEvent only routes a media key to
            // onPlay/onPause when the current state advertises the matching action.
            private val actions = android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE
            override fun onPlay() {
                mediaSession.setPlaybackState(
                    android.media.session.PlaybackState.Builder().setActions(actions)
                        .setState(android.media.session.PlaybackState.STATE_PLAYING, 0, 1f).build(),
                )
                TestMediaSessionState.played.countDown()
            }
            override fun onPause() {
                mediaSession.setPlaybackState(
                    android.media.session.PlaybackState.Builder().setActions(actions)
                        .setState(android.media.session.PlaybackState.STATE_PAUSED, 0, 0f).build(),
                )
                TestMediaSessionState.paused.countDown()
            }
        })
        mediaSession.setMetadata(
            android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "azk track").build(),
        )
        mediaSession.setPlaybackState(
            android.media.session.PlaybackState.Builder()
                .setActions(android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE)
                .setState(android.media.session.PlaybackState.STATE_STOPPED, 0, 0f).build(),
        )
        mediaSession.isActive = true
        ServiceManager.addService("dev.artzig.test.b.mediasession", mediaSession.sessionToken.binder.asBinder())
        System.err.println("system-test-host-b: attached pid=" + Process.myPid() + " uid=" + Process.myUid())
        // A job of this package: the hosted job scheduler binds BJobService into
        // this process from the system process and drives it to jobFinished.
        val scheduler = checkNotNull(context.getSystemService(android.app.job.JobScheduler::class.java))
        val jobResult = scheduler.schedule(
            android.app.job.JobInfo.Builder(
                11, android.content.ComponentName("dev.artzig.test.b", BJobService::class.java.name),
            ).build(),
        )
        check(jobResult == android.app.job.JobScheduler.RESULT_SUCCESS) { "schedule returned " + jobResult }
        Thread {
            val exited = EchoService.exitLatch.await(120, java.util.concurrent.TimeUnit.SECONDS)
            val jobRan = TestJobService.b.finished.await(30, java.util.concurrent.TimeUnit.SECONDS)
            val jobUnbound = jobRan && TestJobService.b.destroyed.await(30, java.util.concurrent.TimeUnit.SECONDS)
            val jobGone = scheduler.getPendingJob(11) == null
            val sessionPlayed = TestMediaSessionState.played.await(30, java.util.concurrent.TimeUnit.SECONDS)
            val sessionPaused = sessionPlayed && TestMediaSessionState.paused.await(30, java.util.concurrent.TimeUnit.SECONDS)
            mediaSession.release()
            System.err.println("system-test-host-b: exiting (requested=" + exited + ", unbound=" + EchoService.unbound.get() +
                ", job ran=" + jobRan + ", job service destroyed=" + jobUnbound + ", job removed=" + jobGone +
                ", session played=" + sessionPlayed + ", session paused=" + sessionPaused + ")")
            Runtime.getRuntime().exit(if (exited && EchoService.unbound.get() && jobRan && jobUnbound && jobGone && sessionPlayed && sessionPaused) 0 else 3)
        }.apply { isDaemon = true }.start()
        Looper.loop()
    }

    /** What the media session in this process observed (client A drives it). */
    internal object TestMediaSessionState {
        @JvmField val played = java.util.concurrent.CountDownLatch(1)
        @JvmField val paused = java.util.concurrent.CountDownLatch(1)
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
