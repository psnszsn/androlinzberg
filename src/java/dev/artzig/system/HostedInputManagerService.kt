@file:Suppress("DEPRECATION")

package dev.artzig.system

import android.hardware.input.IInputDevicesChangedListener
import android.hardware.input.IInputManager
import android.view.HostedInputDispatcher
import android.view.InputDevice
import android.view.KeyCharacterMap

/**
 * Per-process input-device service backing the AOSP `InputManagerGlobal`
 * client (replaces the reflective default-answering proxy `ApkHost` used to
 * install through `createTestSession`).
 *
 * Source note: follows `com.android.server.input.InputManagerService` for the
 * device queries the framework makes (`InputDevice.getDevice`,
 * `KeyCharacterMap.load`, `VelocityTracker`). The host exposes one virtual
 * keyboard; pointer input arrives through the Wayland dispatcher, not through
 * an input device. Unserved methods fail through the shared unsupported Binder
 * delegate.
 */
internal class HostedInputManagerService :
    IInputManager.Stub(HostedBootstrapPermissionEnforcer()),
    IInputManager by unsupported<IInputManager>() {
    // Interface delegation must not shadow the Binder identity.
    override fun asBinder(): android.os.IBinder = this

    private val virtualKeyboard: InputDevice =
        InputDevice
            .Builder()
            .setId(KeyCharacterMap.VIRTUAL_KEYBOARD)
            .setName("artzig virtual keyboard")
            .setDescriptor("artzig-virtual-keyboard")
            .setSources(InputDevice.SOURCE_KEYBOARD)
            .setKeyboardType(InputDevice.KEYBOARD_TYPE_ALPHABETIC)
            .setKeyCharacterMap(HostedInputDispatcher.createHostKeyCharacterMap())
            .build()

    override fun getInputDeviceIds(): IntArray = intArrayOf(KeyCharacterMap.VIRTUAL_KEYBOARD)

    override fun getInputDevice(deviceId: Int): InputDevice? =
        if (deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) virtualKeyboard else null

    override fun getKeyCharacterMap(layoutDescriptor: String?): KeyCharacterMap =
        HostedInputDispatcher.createHostKeyCharacterMap()

    override fun hasKeys(deviceId: Int, sourceMask: Int, keyCodes: IntArray, keyExists: BooleanArray): Boolean {
        for (i in keyCodes.indices) keyExists[i] = true
        return true
    }

    override fun getVelocityTrackerStrategy(): String? = null

    override fun registerInputDevicesChangedListener(listener: IInputDevicesChangedListener?) {
        // The device set never changes on this host.
    }
}
