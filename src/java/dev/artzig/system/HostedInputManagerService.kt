@file:Suppress("DEPRECATION")

package dev.artzig.system

import android.hardware.input.IInputDevicesChangedListener
import android.hardware.input.IInputManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.view.HostedInputDispatcher
import android.view.InputDevice
import android.view.KeyCharacterMap

/**
 * Hosted input-device service backing the AOSP `InputManagerGlobal`
 * client (replaces the reflective default-answering proxy `ApkHost` used to
 * install through `createTestSession`).
 *
 * Source note: follows `com.android.server.input.InputManagerService` for the
 * device queries the framework makes (`InputDevice.getDevice`,
 * `KeyCharacterMap.load`, `VelocityTracker`). The host exposes one virtual
 * keyboard whose character map follows the Wayland seat. Unserved methods fail through the shared unsupported Binder
 * delegate.
 */
internal class HostedInputManagerService(private val handler: Handler = Handler(Looper.getMainLooper()),
    private val captureRequest: ((IBinder, Boolean) -> Unit)? = null,
    private val pointerIconRequest: ((android.view.PointerIcon, Int, Int, Int, IBinder?) -> Boolean)? = null) :
    IInputManager.Stub(HostedBootstrapPermissionEnforcer()),
    IInputManager by unsupported<IInputManager>() {
    // Interface delegation must not shadow the Binder identity.
    override fun asBinder(): android.os.IBinder = this

    private val deviceLock = Any()
    private val listeners = mutableMapOf<Int, Listener>()
    private var generation = 0
    private var notificationPending = false
    private var keyboardMap = HostedInputDispatcher.createHostKeyCharacterMap()
    private var virtualKeyboard = keyboard(keyboardMap)

    private fun keyboard(map: KeyCharacterMap): InputDevice = InputDevice.Builder()
        .setId(KeyCharacterMap.VIRTUAL_KEYBOARD)
        .setGeneration(generation)
        .setName("artzig virtual keyboard")
        .setDescriptor("artzig-virtual-keyboard")
        .setSources(InputDevice.SOURCE_KEYBOARD)
        .setKeyboardType(InputDevice.KEYBOARD_TYPE_ALPHABETIC)
        .setKeyCharacterMap(map)
        .build()

    /** InputManagerService.notifyInputDevicesChanged/deliverInputDevicesChanged:
     * publish the immutable device first, then notify id/generation pairs on the
     * handler outside the device lock. InputManagerGlobal drops its cached map.
     * InputReader is absent; the compositor supplies this device's layout.
     */
    fun publishKeyboardMap(map: KeyCharacterMap) = synchronized(deviceLock) {
        check(generation < Int.MAX_VALUE) { "Keyboard generation exhausted" }
        generation++
        keyboardMap = map
        virtualKeyboard = keyboard(map)
        if (!notificationPending) {
            notificationPending = true
            check(handler.post { deliverDevicesChanged() })
        }
    }

    private fun deliverDevicesChanged() {
        val targets: List<Listener>
        val info: IntArray
        synchronized(deviceLock) {
            notificationPending = false
            targets = listeners.values.toList()
            info = intArrayOf(KeyCharacterMap.VIRTUAL_KEYBOARD, generation)
        }
        for (target in targets) {
            try { target.remote.onInputDevicesChanged(info) }
            catch (_: RemoteException) { target.binderDied() }
        }
    }

    /** Pinned InputManagerService.setPointerIcon and NativeInputManager validate
     * the current pointer/window before PointerChoreographer applies the icon.
     * Wayland owns cursor presentation here, so the stock dispatcher validation
     * stays in the host input boundary and cursor rendering goes to the shell. */
    override fun setPointerIcon(icon: android.view.PointerIcon?, displayId: Int, deviceId: Int,
        pointerId: Int, inputToken: IBinder?): Boolean {
        val value = icon ?: throw NullPointerException()
        return pointerIconRequest?.invoke(value, displayId, deviceId, pointerId, inputToken)
            ?: false // No pointer controller in a windowless/legacy service.
    }

    override fun getInputDeviceIds(): IntArray = intArrayOf(KeyCharacterMap.VIRTUAL_KEYBOARD)

    override fun getInputDevice(deviceId: Int): InputDevice? = synchronized(deviceLock) {
        if (deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) virtualKeyboard else null
    }

    override fun getKeyCharacterMap(layoutDescriptor: String?): KeyCharacterMap =
        synchronized(deviceLock) { keyboardMap }

    override fun hasKeys(deviceId: Int, sourceMask: Int, keyCodes: IntArray, keyExists: BooleanArray): Boolean {
        for (i in keyCodes.indices) keyExists[i] = true
        return true
    }

    /** Pinned InputManagerService.requestPointerCapture: reject null and defer
     * focus/token authorization to native InputDispatcher. */
    override fun requestPointerCapture(inputChannelToken: IBinder?, enabled: Boolean) {
        val token = java.util.Objects.requireNonNull(inputChannelToken, "inputChannelToken must not be null")!!
        val request = captureRequest ?: throw dev.artzig.ArtzigTestDiagnostics.unsupported(
            "InputManagerService", "requestPointerCapture", "No stock input dispatcher is attached")
        request(token, enabled)
    }

    override fun getVelocityTrackerStrategy(): String? = null

    override fun registerInputDevicesChangedListener(listener: IInputDevicesChangedListener?) {
        val remote = java.util.Objects.requireNonNull(listener, "listener must not be null")!!
        synchronized(deviceLock) {
            val pid = Binder.getCallingPid()
            if (listeners.containsKey(pid)) throw SecurityException(
                "The calling process has already registered an InputDevicesChangedListener.")
            val record = Listener(pid, remote)
            try { remote.asBinder().linkToDeath(record, 0) }
            catch (failure: RemoteException) { throw RuntimeException(failure) }
            listeners[pid] = record
        }
    }

    private inner class Listener(val pid: Int, val remote: IInputDevicesChangedListener) : IBinder.DeathRecipient {
        override fun binderDied() = synchronized(deviceLock) {
            if (listeners[pid] === this) listeners.remove(pid)
            Unit
        }
    }
}
