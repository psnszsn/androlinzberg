package dev.artzig.window

import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.TaskStackListener
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import dev.artzig.ApkHost
import dev.artzig.BinderSession
import dev.artzig.HostContexts
import java.util.concurrent.atomic.AtomicBoolean

/** Session controller: ATMS starts application processes and the system task
 * organizer owns their presentation. This process never attaches as an APK. */
object DesktopLauncher {
    @JvmStatic fun main(args: Array<String>) {
        check(WindowBackend.stock && android.view.HostedRootFrames.stockClients) {
            "Task presentation requires stock window services and compiled stock window clients"
        }
        val owner = when (args.single()) {
            "owner" -> true
            "joined" -> false
            else -> error("Invalid desktop controller role")
        }
        Looper.prepareMainLooper()
        if (owner) BinderSession.setIdentity("android", true) else ApkHost.prepareProcessIdentity()
        val context = HostContexts.systemContext(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")),
            640, 480, 120, 0)
        checkNotNull(android.os.ServiceManager.checkService("dev.artzig.window.presenter")) {
            "The joined session has no task presenter"
        }
        val packageName = ApkHost.installForDesktop()
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(packageName)) {
            "No launcher activity in $packageName"
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tasks = ActivityTaskManager.getService()
        val changed = AtomicBoolean(true)
        val listener = object : TaskStackListener() {
            override fun onTaskStackChanged() { changed.set(true) }
        }
        if (owner) tasks.registerTaskStackListener(listener)
        try {
            val result = tasks.startActivity(null, if (owner) "android" else packageName, null,
                intent, null, null, null, -1, 0, null, null)
            check(ActivityManager.isStartResultSuccessful(result)) { "Activity launch rejected: $result ($intent)" }
            println("Desktop launch accepted: $packageName")
            // A joined launch leaves lifetime with the existing session owner.
            if (!owner) return
            var observedTask = false
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (true) {
                if (changed.getAndSet(false)) {
                    val live = tasks.getTasks(128, false, true, android.view.Display.DEFAULT_DISPLAY)
                        .any { it.baseActivity != null && it.numActivities > 0 &&
                            (it.activityType == android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD ||
                                it.activityType == android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED) }
                    if (live) observedTask = true else if (observedTask) return
                }
                check(observedTask || SystemClock.uptimeMillis() < deadline) { "ATMS never created an application task" }
                val message = Looper.myQueue().`pollDueMessage$artzig`()
                if (message == null) SystemClock.sleep(5)
                else try { message.target.dispatchMessage(message) }
                finally { android.os.MessageQueue.`recycleDispatched$artzig`(message) }
            }
        } finally {
            if (owner) tasks.unregisterTaskStackListener(listener)
        }
    }
}
