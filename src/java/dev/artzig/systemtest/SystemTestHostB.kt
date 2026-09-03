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
        dev.artzig.HostContexts.systemContext(frameworkRes, 640, 480, 120, 0)
        // Install this package's fixture APK: the session's package manager
        // parses the real manifest (client A asserts the parsed values).
        val fixture = checkNotNull(System.getenv("AZK_FIXTURE_APK_B")) { "AZK_FIXTURE_APK_B is not set" }
        val installer = checkNotNull(android.app.HostedServiceClient.installerOrNull())
        check(installer.installPackage(fixture, null) == "dev.artzig.test.b")
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
