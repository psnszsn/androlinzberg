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
import java.util.concurrent.atomic.AtomicReference

/** Session controller: ATMS starts application processes and the system task
 * organizer owns their presentation. This process never attaches as an APK. */
object DesktopLauncher {
    private var failure: String? = null
    @JvmStatic fun sessionFailed(): Boolean = failure != null
    private fun sessionLost(service: String) {
        if (failure != null) return
        failure = service
        android.util.Log.e("DesktopLauncher", "Desktop session lost: $service")
    }
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
        val presentation = android.os.ServiceManager.checkService("dev.artzig.window.presenter") ?: run {
            sessionLost("dev.artzig.window.presenter")
            return
        }
        val packageName = ApkHost.installForDesktop()
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(packageName)) {
            "No launcher activity in $packageName"
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tasks = ActivityTaskManager.getService() ?: run {
            sessionLost("activity_task")
            return
        }
        val lost = AtomicReference<String?>(null)
        val deaths = ArrayList<Pair<android.os.IBinder, android.os.IBinder.DeathRecipient>>()
        var registered = false
        val changed = AtomicBoolean(true)
        val listener = object : TaskStackListener() {
            override fun onTaskStackChanged() { changed.set(true) }
        }
        val testControl = owner && System.getenv("AZK_TEST_SOCKET") != null
        if (testControl) dev.artzig.testing.SessionTestControl.start()
        try {
            if (owner) {
                // Task callbacks stop when a service dies. Watch each identity
                // independently so an idle controller still retires its session.
                for ((name, binder) in listOf(
                    "activity_task" to tasks.asBinder(),
                    "dev.artzig.window.presenter" to presentation,
                    "SurfaceFlingerAIDL" to android.os.ServiceManager.checkService("SurfaceFlingerAIDL"),
                )) {
                    if (binder == null) { sessionLost(name); return }
                    val death = android.os.IBinder.DeathRecipient { lost.compareAndSet(null, name) }
                    binder.linkToDeath(death, 0)
                    deaths += binder to death
                }
                tasks.registerTaskStackListener(listener)
                registered = true
            }
            val result = tasks.startActivity(null, if (owner) "android" else packageName, null,
                intent, null, null, null, -1, 0, null, null)
            check(ActivityManager.isStartResultSuccessful(result)) { "Activity launch rejected: $result ($intent)" }
            println("Desktop launch accepted: $packageName")
            // A joined launch leaves lifetime with the existing session owner.
            if (!owner) return
            var observedTask = false
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (true) {
                // Return to the native owner for bounded Session.stop; an
                // uncaught exception would exit inside AndroidRuntime instead.
                val deadService = lost.get()
                if (deadService != null) {
                    sessionLost(deadService)
                    break
                }
                if (testControl) dev.artzig.testing.SessionTestControl.tick()
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
        } catch (dead: android.os.DeadObjectException) {
            sessionLost(lost.get() ?: "activity_task")
        } catch (dead: android.os.DeadSystemRuntimeException) {
            sessionLost(lost.get() ?: "activity_task")
        } finally {
            for ((binder, death) in deaths) binder.unlinkToDeath(death, 0)
            if (registered) try { tasks.unregisterTaskStackListener(listener) }
            catch (dead: android.os.DeadObjectException) { sessionLost(lost.get() ?: "activity_task") }
        }
    }
}
