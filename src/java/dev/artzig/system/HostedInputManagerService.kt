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
 * fallback keyboard whose character map follows the Wayland seat, plus its
 * currently available logical keyboard/pointer/touch devices. Unserved methods fail through the shared unsupported Binder
 * delegate.
 */
internal class HostedInputManagerService(private val handler: Handler = Handler(Looper.getMainLooper()),
    private val devicesChanged: () -> Unit = {},
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
    private var keyboardKeys: Set<Int> = emptySet()
    private var seatKeyboardMap = HostedInputDispatcher.createHostKeyCharacterMap(
        HostedInputDispatcher.HOST_KEYBOARD_DEVICE_ID, "type FULL\n")
    private var virtualKeyboard = keyboard(keyboardMap)
    private var keyboardDevice: InputDevice? = null
    private var pointerDevice: InputDevice? = null
    private var touchDevice: InputDevice? = null
    private var seatGeneration = 0
    private var seatCapabilities = 0

    /** wl_seat exposes aggregate logical devices, not the physical hardware
     * behind them. IDs match WaylandTaskInput's event stream. The Android
     * virtual keyboard remains available as the fallback KCM across seat loss.
     * Bit 8 additionally advertises the negotiated relative-pointer path. */
    fun publishSeatCapabilities(sourceGeneration: Int, capabilities: Int) = synchronized(deviceLock) {
        require(sourceGeneration > 0 && capabilities and 15 == capabilities)
        if (sourceGeneration < seatGeneration) return@synchronized
        if (sourceGeneration == seatGeneration) {
            require(capabilities == seatCapabilities) { "Seat capability generation changed contents" }
            return@synchronized
        }
        seatGeneration = sourceGeneration
        seatCapabilities = capabilities
        advanceGeneration()
    }

    private fun pointingDevice(id: Int, name: String, sources: Int): InputDevice = InputDevice.Builder()
        .setId(id).setGeneration(generation).setName("artzig Wayland $name")
        .setDescriptor("artzig-wayland-$name").setSources(sources)
        .setKeyboardType(InputDevice.KEYBOARD_TYPE_NONE)
        .setKeyCharacterMap(KeyCharacterMap.obtainEmptyMap(id))
        .setAssociatedDisplayId(android.view.Display.DEFAULT_DISPLAY).build()

    private fun currentDevices(): List<InputDevice> = listOfNotNull(virtualKeyboard, pointerDevice, keyboardDevice, touchDevice)

    private fun advanceGeneration() {
        check(generation < Int.MAX_VALUE) { "Input device generation exhausted" }
        generation++
        virtualKeyboard = keyboard(keyboardMap)
        keyboardDevice = if (seatCapabilities and 2 == 0) null else InputDevice.Builder()
            .setId(HostedInputDispatcher.HOST_KEYBOARD_DEVICE_ID).setGeneration(generation)
            .setName("artzig Wayland keyboard").setDescriptor("artzig-wayland-keyboard")
            .setExternal(true).setAssociatedDisplayId(android.view.Display.DEFAULT_DISPLAY)
            .setSources(InputDevice.SOURCE_KEYBOARD)
            // Pinned EventHub.openDeviceLocked classifies ALPHAKEY from Q support.
            .setKeyboardType(if (android.view.KeyEvent.KEYCODE_Q in keyboardKeys)
                InputDevice.KEYBOARD_TYPE_ALPHABETIC else InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC)
            .setKeyCharacterMap(seatKeyboardMap).build()
        pointerDevice = if (seatCapabilities and 1 == 0) null else pointingDevice(0, "pointer",
            InputDevice.SOURCE_MOUSE or if (seatCapabilities and 8 != 0) InputDevice.SOURCE_MOUSE_RELATIVE else 0)
        touchDevice = if (seatCapabilities and 4 == 0) null else pointingDevice(2, "touch", InputDevice.SOURCE_TOUCHSCREEN)
        if (!notificationPending) {
            notificationPending = true
            check(handler.post { deliverDevicesChanged() })
        }
    }

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
    fun publishKeyboardMap(map: KeyCharacterMap, supportedKeyCodes: IntArray, keyboard: KeyCharacterMap) = synchronized(deviceLock) {
        require(supportedKeyCodes.size <= 768 && supportedKeyCodes.all { it in 1..android.view.KeyEvent.getMaxKeyCode() })
        keyboardMap = map
        seatKeyboardMap = keyboard
        keyboardKeys = supportedKeyCodes.toSet()
        advanceGeneration()
    }

    private fun deliverDevicesChanged() {
        val targets: List<Listener>
        val info: IntArray
        synchronized(deviceLock) {
            notificationPending = false
            targets = listeners.values.toList()
            info = currentDevices().flatMap { listOf(it.id, it.generation) }.toIntArray()
        }
        devicesChanged()
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

    override fun getInputDeviceIds(): IntArray = synchronized(deviceLock) {
        currentDevices().map { it.id }.toIntArray()
    }

    override fun getInputDevice(deviceId: Int): InputDevice? = synchronized(deviceLock) {
        currentDevices().firstOrNull { it.id == deviceId }
    }

    override fun getKeyCharacterMap(layoutDescriptor: String?): KeyCharacterMap =
        synchronized(deviceLock) { keyboardMap }

    override fun hasKeys(deviceId: Int, sourceMask: Int, keyCodes: IntArray, keyExists: BooleanArray): Boolean {
        // Pinned IMS.hasKeys validates the output size; InputReader.hasKeys
        // clears the requested flags before filtering device and non-class
        // source bits. Our fallback keyboard's XKB snapshot replaces EventHub's
        // key-layout/capability lookup. Pointer/touch provide no keyboard mapper.
        require(keyExists.size >= keyCodes.size) { "keyExists must be at least as large as keyCodes" }
        for (i in keyCodes.indices) keyExists[i] = false
        return synchronized(deviceLock) {
            if ((deviceId >= 0 && (deviceId != HostedInputDispatcher.HOST_KEYBOARD_DEVICE_ID || keyboardDevice == null)) ||
                InputDevice.SOURCE_KEYBOARD and sourceMask and InputDevice.SOURCE_CLASS_MASK.inv() == 0) {
                false
            } else {
                for (i in keyCodes.indices) keyExists[i] = keyCodes[i] in keyboardKeys
                true
            }
        }
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
