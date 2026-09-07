package dev.artzig.system

import android.companion.virtual.IVirtualDevice
import android.companion.virtual.IVirtualDeviceManager
import android.companion.virtual.VirtualDevice
import android.companion.virtual.VirtualDeviceManager
import android.companion.virtual.VirtualDeviceParams
import android.companion.virtual.sensor.VirtualSensor
import android.content.Context
import android.hardware.display.IVirtualDisplayCallback
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.Process
import android.os.ServiceManager
import android.util.ArraySet
import android.window.DisplayWindowPolicyController
import com.android.server.companion.virtual.VirtualDeviceManagerInternal
import dev.artzig.ArtzigTestDiagnostics
import java.util.Objects
import java.util.function.Consumer

/** Pinned VirtualDeviceManagerService.LocalService over the host's empty live-device set.
 * CDM persistent identities come from the Binder service; listener snapshots retain AOSP's
 * asynchronous delivery and duplicate-registration behavior. Creating displays/input devices
 * remains an explicit unsupported backend operation. */
internal class HostedVirtualDeviceManagerInternal(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : VirtualDeviceManagerInternal() {
    private val lock = Any()
    private val appListeners = ArrayList<AppsOnVirtualDeviceListener>()
    private val persistentListeners = ArrayList<Consumer<String>>()

    override fun registerAppsOnVirtualDeviceListener(listener: AppsOnVirtualDeviceListener) {
        synchronized(lock) { appListeners.add(listener) }
    }
    override fun unregisterAppsOnVirtualDeviceListener(listener: AppsOnVirtualDeviceListener) {
        synchronized(lock) { appListeners.remove(listener) }
    }
    override fun registerPersistentDeviceIdRemovedListener(listener: Consumer<String>) {
        synchronized(lock) { persistentListeners.add(listener) }
    }
    override fun unregisterPersistentDeviceIdRemovedListener(listener: Consumer<String>) {
        synchronized(lock) { persistentListeners.remove(listener) }
    }
    override fun onPersistentDeviceIdsRemoved(ids: Set<String>) {
        val listeners = synchronized(lock) { persistentListeners.toList() }
        handler.post { for (id in ids) for (listener in listeners) listener.accept(id) }
    }
    override fun onAppsOnVirtualDeviceChanged() { /* The UID union stays empty; AOSP suppresses unchanged updates. */ }
    override fun onAuthenticationPrompt(uid: Int) { /* No live device owns an activity to notify. */ }
    override fun getDeviceOwnerUid(deviceId: Int): Int = Process.INVALID_UID
    override fun getVirtualSensor(deviceId: Int, handle: Int): VirtualSensor? = null
    override fun getDeviceIdsForUid(uid: Int): ArraySet<Int> = ArraySet()
    override fun getDisplayIdsForDevice(deviceId: Int): ArraySet<Int> = ArraySet()
    override fun getPreferredLocaleListForUid(uid: Int): LocaleList? = null
    override fun isAppRunningOnAnyVirtualDevice(uid: Int): Boolean = false
    override fun isInputDeviceOwnedByVirtualDevice(inputDeviceId: Int): Boolean = false
    override fun isValidVirtualDeviceId(deviceId: Int): Boolean = false
    override fun getDeviceIdForDisplayId(displayId: Int): Int = Context.DEVICE_ID_DEFAULT
    override fun getDimDurationMillisForDeviceId(deviceId: Int): Long = -1
    override fun getScreenOffTimeoutMillisForDeviceId(deviceId: Int): Long = -1
    override fun getPersistentIdForDevice(deviceId: Int): String? =
        if (deviceId == Context.DEVICE_ID_DEFAULT) VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT else null
    override fun getAllPersistentDeviceIds(): Set<String> =
        java.util.Set.copyOf(IVirtualDeviceManager.Stub.asInterface(ServiceManager.getService(Context.VIRTUAL_DEVICE_SERVICE))
            ?.allPersistentDeviceIds ?: emptyList())
    override fun getVirtualDevice(deviceId: Int): VirtualDevice? = null
    override fun createVirtualDevice(params: VirtualDeviceParams): VirtualDeviceManager.VirtualDevice {
        Objects.requireNonNull(params.name, "virtual device name must not be null")
        throw unavailable("createVirtualDevice")
    }
    override fun getBaseVirtualDisplayFlags(device: IVirtualDevice): Int = throw unavailable("getBaseVirtualDisplayFlags")
    override fun onVirtualDisplayCreated(device: IVirtualDevice, displayId: Int,
        callback: IVirtualDisplayCallback, controller: DisplayWindowPolicyController) {
        throw unavailable("onVirtualDisplayCreated")
    }
    override fun onVirtualDisplayRemoved(device: IVirtualDevice, displayId: Int) {
        throw unavailable("onVirtualDisplayRemoved")
    }
    private fun unavailable(member: String): UnsupportedOperationException = ArtzigTestDiagnostics.unsupported(
        "com.android.server.companion.virtual.VirtualDeviceManagerInternal", member,
        "Virtual display and input device creation is not hosted")
}
