package dev.artzig.system

import android.util.ArraySet
import com.android.server.companion.virtual.VirtualDeviceManagerInternal

/**
 * Default-device implementation of the system_server virtual-device seam.
 *
 * Source note: AOSP VirtualDeviceManagerService.LocalService exposes these
 * lookups to ClipboardService and other system services. The host has no
 * companion-device association service, virtual displays, or virtual input
 * stack, so its complete and observable state is the empty set. Keeping that
 * state behind the real LocalServices contract avoids teaching each consumer
 * a separate null-service fallback.
 */
internal class HostedVirtualDeviceManagerInternal : VirtualDeviceManagerInternal() {
    override fun getDeviceOwnerUid(deviceId: Int): Int = -1

    override fun getDeviceIdsForUid(uid: Int): ArraySet<Int> = ArraySet()

    override fun getDisplayIdsForDevice(deviceId: Int): ArraySet<Int> = ArraySet()
}
