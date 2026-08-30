package android.app

import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.artzig.system.IHostedApplicationThread

/**
 * This process's handle given to the activity manager.
 *
 * Source note: follows `ActivityThread.ApplicationThread` — every schedule*
 * posts to the main looper and runs the matching `handle*` on
 * [HostedServiceRuntime] (AOSP: `H.CREATE_SERVICE` -> `handleCreateService`,
 * etc.), so lifecycle ordering matches Android's main-thread execution.
 */
internal class HostedApplicationThread : IHostedApplicationThread.Stub() {
    private val handler = Handler(Looper.getMainLooper())

    override fun scheduleCreateService(token: IBinder, name: ComponentName) {
        handler.post { HostedServiceRuntime.handleCreateService(token, name) }
    }

    override fun scheduleBindService(token: IBinder, intent: Intent, rebind: Boolean) {
        handler.post { HostedServiceRuntime.handleBindService(token, intent, rebind) }
    }

    override fun scheduleUnbindService(token: IBinder, intent: Intent) {
        handler.post { HostedServiceRuntime.handleUnbindService(token, intent) }
    }

    override fun scheduleServiceArgs(token: IBinder, startId: Int, flags: Int, args: Intent) {
        handler.post { HostedServiceRuntime.handleServiceArgs(token, startId, flags, args) }
    }

    override fun scheduleStopService(token: IBinder) {
        handler.post { HostedServiceRuntime.handleStopService(token) }
    }

    override fun scheduleExit() {
        handler.post {
            HostedServiceRuntime.shutdown()
            Runtime.getRuntime().exit(0)
        }
    }
}
