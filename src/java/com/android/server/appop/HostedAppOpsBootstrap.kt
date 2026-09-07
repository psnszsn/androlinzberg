package com.android.server.appop

import android.app.AppOpsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.SparseArray
import com.android.server.LocalServices
import java.io.File

/**
 * SystemServer's AppOps startup with the session's explicit-mode store. The pinned
 * runtime-permission/app-op mapping flag is disabled; the modern permission service
 * is not hosted. Package-private access keeps the original checking implementation
 * intact. All Binder operations, accounting, restrictions and callbacks are AOSP.
 */
object HostedAppOpsBootstrap {
    @JvmStatic
    fun create(context: Context): AppOpsService {
        check(!android.permission.flags.Flags.runtimePermissionAppopsMappingEnabled()) {
            "Permission-derived AppOps requires AccessCheckingService"
        }
        val session = File(checkNotNull(System.getenv("AZK_BINDER_SESSION_DIR"))).canonicalFile
        // The original history implementations choose Environment's system directory.
        // Check the actual framework path, including the packaged Environment patch.
        check(android.os.Environment.getDataSystemDirectory().canonicalFile.toPath().startsWith(session.toPath())) {
            "AppOps history must be isolated to the broker session"
        }
        val directory = File(session, "appops")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create $directory" }
        val handler = Handler(Looper.getMainLooper())
        val switches = SparseArray<IntArray>()
        for (op in 0 until AppOpsManager._NUM_OP) {
            val switch = AppOpsManager.opToSwitch(op)
            switches.put(switch, (switches[switch] ?: intArrayOf()) + op)
        }
        val modes = File(directory, "modes.xml")
        LocalServices.addService(AppOpsCheckingServiceInterface::class.java,
            AppOpsCheckingServiceImpl(modes, Any(), handler, context, switches))
        return AppOpsService(File(directory, "accesses.xml"), modes, handler, context).also {
            it.publish()
            Runtime.getRuntime().addShutdownHook(Thread { it.shutdown() })
        }
    }
}
