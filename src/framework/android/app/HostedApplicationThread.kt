package android.app

import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * This process's IApplicationThread given to the activity manager.
 *
 * Source note: follows `ActivityThread.ApplicationThread` — every schedule*
 * builds the AOSP data record, posts to the main looper and runs the matching
 * `ActivityThread.handle*` (AOSP: `H.CREATE_SERVICE` -> `handleCreateService`,
 * etc.), so lifecycle ordering matches Android's main-thread execution.
 * Activity transactions go to the hosted ActivityThread's TransactionExecutor.
 * Everything else the activity manager could schedule is not hosted.
 */
internal class HostedApplicationThread : UnsupportedApplicationThread() {
    private val handler = Handler(Looper.getMainLooper())
    private val thread: ActivityThread get() = ActivityThread.currentActivityThread()

    override fun scheduleCreateService(
        token: IBinder?,
        info: android.content.pm.ServiceInfo?,
        compatInfo: android.content.res.CompatibilityInfo?,
        processState: Int,
    ) {
        val data = ActivityThread.CreateServiceData()
        data.token = checkNotNull(token)
        data.info = checkNotNull(info)
        data.compatInfo = compatInfo
        handler.post { thread.handleCreateService(data) }
    }

    override fun scheduleBindService(token: IBinder?, intent: Intent?, rebind: Boolean, processState: Int, bindSeq: Long) {
        val data = ActivityThread.BindServiceData()
        data.token = checkNotNull(token)
        data.intent = checkNotNull(intent)
        data.rebind = rebind
        data.bindSeq = bindSeq
        handler.post { thread.handleBindService(data) }
    }

    override fun scheduleUnbindService(token: IBinder?, intent: Intent?) {
        val data = ActivityThread.BindServiceData()
        data.token = checkNotNull(token)
        data.intent = checkNotNull(intent)
        handler.post { thread.handleUnbindService(data) }
    }

    override fun scheduleServiceArgs(token: IBinder?, args: android.content.pm.ParceledListSlice<*>?) {
        val list = args?.list ?: return
        for (item in list) {
            val start = item as ServiceStartArgs
            val data = ActivityThread.ServiceArgsData()
            data.token = checkNotNull(token)
            data.taskRemoved = start.taskRemoved
            data.startId = start.startId
            data.flags = start.flags
            data.args = start.args
            handler.post { thread.handleServiceArgs(data) }
        }
    }

    override fun scheduleStopService(token: IBinder?) {
        val t = checkNotNull(token)
        handler.post { thread.handleStopService(t) }
    }

    override fun scheduleExit() {
        handler.post {
            thread.`shutdownServices$artzig`()
            Runtime.getRuntime().exit(0)
        }
    }

    override fun scheduleTransaction(transaction: android.app.servertransaction.ClientTransaction?) {
        ActivityThread.currentActivityThread().scheduleTransaction(checkNotNull(transaction))
    }

    override fun schedulePing(pong: android.os.RemoteCallback?) { pong?.sendResult(null) }
    override fun scheduleLowMemory() {}
    override fun scheduleTrimMemory(level: Int) {}
    override fun processInBackground() {}
    override fun updateTimeZone() {}
    override fun setProcessState(state: Int) {}
    override fun scheduleTimeoutService(token: IBinder?, startId: Int) {}
    override fun scheduleTimeoutServiceForType(token: IBinder?, startId: Int, fgsType: Int) {}
}
