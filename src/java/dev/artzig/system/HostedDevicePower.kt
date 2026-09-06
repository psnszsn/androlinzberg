package dev.artzig.system

import android.content.Context
import android.os.UserManager
import dev.artzig.ArtzigTestDiagnostics

/**
 * Device-power boundary for AOSP WindowManagerService's policy callbacks and
 * ShutdownThread. Android's radio/recovery/kernel shutdown stack does not own
 * the host computer. Task close and session teardown use their own lifecycle;
 * a device reboot/shutdown request has an explicit unsupported result.
 */
object HostedDevicePower {
    // Pinned ShutdownThread properties read by WindowManagerService.detectSafeMode.
    const val REBOOT_SAFEMODE_PROPERTY = "persist.sys.safemode"
    const val RO_SAFEMODE_PROPERTY = "ro.sys.safemode"

    @JvmStatic fun shutdown(context: Context, reason: String?, confirm: Boolean): Nothing = unavailable("shutdown")
    @JvmStatic fun reboot(context: Context, reason: String?, confirm: Boolean): Nothing = unavailable("reboot")

    @JvmStatic fun rebootSafeMode(context: Context, confirm: Boolean) {
        // ShutdownThread.rebootSafeMode checks this restriction before any action.
        if (context.getSystemService(UserManager::class.java).hasUserRestriction(UserManager.DISALLOW_SAFE_BOOT)) return
        unavailable("rebootSafeMode")
    }

    internal fun unavailable(operation: String): Nothing =
        throw ArtzigTestDiagnostics.unsupported("ShutdownThread", operation,
            "Android device $operation is unavailable in a hosted session")
}
