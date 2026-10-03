package com.android.server.appop

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.server.SystemServiceManager
import java.io.File

/**
 * The activity manager's AppOpsService startup (ActivityManagerService
 * constructor): access history and legacy modes in the data partition's
 * system directory. App-op modes live in AccessCheckingService's
 * AppOpService, which SystemServer starts first and AppOpsService reads as
 * AppOpsCheckingServiceInterface. All Binder operations, accounting,
 * restrictions and callbacks are AOSP.
 */
object HostedAppOpsBootstrap {
    @JvmStatic
    fun create(context: Context): AppOpsService {
        val systemDir = SystemServiceManager.ensureSystemDir()
        val handler = Handler(Looper.getMainLooper())
        return AppOpsService(File(systemDir, "appops_accesses.xml"), File(systemDir, "appops.xml"), handler, context).also {
            it.publish()
            Runtime.getRuntime().addShutdownHook(Thread { it.shutdown() })
        }
    }
}
