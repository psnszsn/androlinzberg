@file:Suppress("DEPRECATION")

package dev.artzig.system

import android.app.ActivityThread
import dev.artzig.BinderSession
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
        Looper.prepareMainLooper()
        // Identity first: uid 1000, the only peer allowed to claim system names.
        BinderSession.setIdentity("android", true)
        Process.setArgV0("system_server")
        val uid = BinderSession.assignedUid()
        check(uid == Process.SYSTEM_UID) { "broker assigned uid " + uid + " to the system process" }
        // ro.boot.qemu is never seeded: the real ClipboardService takes its plain path.
        check(!android.os.Build.IS_EMULATOR) { "hosted sessions are not emulators" }
        val frameworkRes = System.getenv("ARTZIG_FRAMEWORK_RES")
            ?: throw IllegalStateException("ARTZIG_FRAMEWORK_RES is not set")
        val stockWindows = if (dev.artzig.window.WindowBackend.stock) StockWindowServices.prepare() else null
        val context = dev.artzig.HostContexts.systemContext(frameworkRes, 800, 600, 120, 0)
        // Reused services read @hide settings; the verbatim AOSP Settings
        // client exempts the system server from the @Readable restriction.
        android.provider.Settings.setInSystemServer()
        stockWindows?.attachContext(context)
        SystemServices.start(context, stockWindows) {
            System.out.println("ready")
            System.out.flush()
        }

        // The launcher holds the other end of stdin; EOF means the session is over.
        thread(name = "SystemServer-stdin", isDaemon = true) {
            try {
                while (System.`in`.read() >= 0) { /* drain */ }
            } catch (ignored: java.io.IOException) {
            }
            check(android.os.Handler(Looper.getMainLooper()).post {
                try { stockWindows?.close() }
                finally { Runtime.getRuntime().exit(0) }
            })
        }
        Looper.loop()
    }
}
