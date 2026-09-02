package android.app

import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * This process's IApplicationThread given to the activity manager.
 *
 * Source note: follows `ActivityThread.ApplicationThread` — every schedule*
 * posts to the main looper and runs the matching `handle*` on
 * [HostedServiceRuntime] (AOSP: `H.CREATE_SERVICE` -> `handleCreateService`,
 * etc.), so lifecycle ordering matches Android's main-thread execution.
 * Activity transactions go to the hosted ActivityThread's TransactionExecutor.
 * Everything else the activity manager could schedule is not hosted.
 */
internal class HostedApplicationThread : UnsupportedApplicationThread() {
    private val handler = Handler(Looper.getMainLooper())

    override fun scheduleCreateService(
        token: IBinder?,
        info: android.content.pm.ServiceInfo?,
        compatInfo: android.content.res.CompatibilityInfo?,
        processState: Int,
    ) {
        val name = ComponentName(checkNotNull(info).packageName, info.name)
        handler.post { HostedServiceRuntime.handleCreateService(checkNotNull(token), name) }
    }

    override fun scheduleBindService(token: IBinder?, intent: Intent?, rebind: Boolean, processState: Int, bindSeq: Long) {
        handler.post { HostedServiceRuntime.handleBindService(checkNotNull(token), checkNotNull(intent), rebind) }
    }

    override fun scheduleUnbindService(token: IBinder?, intent: Intent?) {
        handler.post { HostedServiceRuntime.handleUnbindService(checkNotNull(token), checkNotNull(intent)) }
    }

    override fun scheduleServiceArgs(token: IBinder?, args: android.content.pm.ParceledListSlice<*>?) {
        val list = args?.list ?: return
        handler.post {
            for (item in list) {
                val start = item as ServiceStartArgs
                HostedServiceRuntime.handleServiceArgs(checkNotNull(token), start.startId, start.flags, start.args)
            }
        }
    }

    override fun scheduleStopService(token: IBinder?) {
        handler.post { HostedServiceRuntime.handleStopService(checkNotNull(token)) }
    }

    override fun scheduleExit() {
        handler.post {
            HostedServiceRuntime.shutdown()
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
