package com.android.server.power

import android.content.Context
import dev.artzig.system.HostedDevicePower

/**
 * Hosted adaptation of AOSP `com.android.server.power.ShutdownThread`'s static
 * entry points. The stock thread drives radios, recovery and the kernel
 * reboot path, none of which this session owns; requests report the
 * structured unsupported result of [HostedDevicePower].
 */
object ShutdownThread {
    const val SHUTDOWN_ACTION_PROPERTY = "sys.shutdown.requested"
    const val REBOOT_SAFEMODE_PROPERTY = HostedDevicePower.REBOOT_SAFEMODE_PROPERTY
    const val RO_SAFEMODE_PROPERTY = HostedDevicePower.RO_SAFEMODE_PROPERTY

    @JvmStatic fun shutdown(context: Context, reason: String?, confirm: Boolean) { HostedDevicePower.shutdown(context, reason, confirm) }
    @JvmStatic fun reboot(context: Context, reason: String?, confirm: Boolean) { HostedDevicePower.reboot(context, reason, confirm) }
    @JvmStatic fun rebootSafeMode(context: Context, confirm: Boolean) = HostedDevicePower.rebootSafeMode(context, confirm)
}
