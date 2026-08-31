package dev.artzig.system

import android.app.ActivityThread
import android.app.ContextImpl
import android.os.HostedBinderRuntime
import android.os.Looper
import android.os.Process
import kotlin.concurrent.thread

/**
 * The hosted session's system process (a `system_server`-lite).
 *
 * Source note: follows `com.android.server.SystemServer.run()` ordering — main
 * looper, system context, service publication, then `Looper.loop()` — for the
 * few services this host provides. The AOSP class starts ~150 services and is
 * not reusable as a whole. Started by the launcher (`androlinzberg
 * --system-server`) with the session's system cookie in its environment; it
 * prints `ready` once its services are registered and exits when the
 * launcher closes its stdin.
 */
internal object SystemServer {
    @JvmStatic
    fun main(args: Array<String>) {
        // Before any framework class loads: turns on Build.IS_EMULATOR in this
        // process only, so the reused ClipboardService constructs the hosted
        // (Wayland) clipboard monitor. A JVM system property is process-local
        // by nature; the session-wide Android property area must never carry
        // ro.boot.qemu, or apps would think they run on an emulator.
        System.setProperty("azk.emulator", "true")
        Looper.prepareMainLooper()
        // Identity first: uid 1000, the only peer allowed to claim system names.
        val runtime = HostedBinderRuntime.bootstrap("android", true)
        check(runtime.uid == Process.SYSTEM_UID) { "broker assigned uid " + runtime.uid + " to the system process" }
        val frameworkRes = System.getenv("ARTZIG_FRAMEWORK_RES")
            ?: throw IllegalStateException("ARTZIG_FRAMEWORK_RES is not set")
        val context = ContextImpl.createSystemContext(frameworkRes, 800, 600, 120, 0)
        ActivityThread.markSystem()
        // Reused services read @hide settings; the verbatim AOSP Settings
        // client exempts the system server from the @Readable restriction.
        android.provider.Settings.setInSystemServer()
        SystemServices.start(context)

        // The launcher holds the other end of stdin; EOF means the session is over.
        thread(name = "SystemServer-stdin", isDaemon = true) {
            try {
                while (System.`in`.read() >= 0) { /* drain */ }
            } catch (ignored: java.io.IOException) {
            }
            Runtime.getRuntime().exit(0)
        }
        System.out.println("ready")
        System.out.flush()
        Looper.loop()
    }
}
