package dev.artzig.system

import android.hardware.lights.Light
import android.hardware.lights.LightState
import android.os.Binder
import android.os.IBinder
import android.os.Process

/**
 * The session's lights service over the real `ILightsManager` surface,
 * published as `Context.LIGHTS_SERVICE`, behind the real `SystemLightsManager`.
 *
 * Source note: `com.android.server.lights.LightsService.LightsManagerBinderService`
 * for a device whose lights HAL exposes no lights: an empty list, unknown light
 * ids rejected, sessions accepted and tracked so `closeSession` and
 * `setLightStates` keep their AOSP argument checks, and the
 * CONTROL_DEVICE_LIGHTS permission enforced on every call.
 */
class HostedLightsService : android.hardware.lights.ILightsManager.Stub(), android.hardware.lights.ILightsManager by unsupported<android.hardware.lights.ILightsManager>() {
    override fun asBinder(): android.os.IBinder = this

    private val lock = Any()
    private val sessions = LinkedHashMap<IBinder, Int>()

    private fun enforcePermission() {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw SecurityException("Requires android.permission.CONTROL_DEVICE_LIGHTS")
        }
    }

    override fun getLights(): MutableList<Light?>? {
        enforcePermission()
        return ArrayList()
    }

    override fun getLightState(lightId: Int): LightState? {
        enforcePermission()
        throw IllegalArgumentException("Invalid light: $lightId")
    }

    override fun openSession(sessionToken: IBinder?, priority: Int) {
        enforcePermission()
        requireNotNull(sessionToken) { "sessionToken must not be null" }
        synchronized(lock) {
            require(!sessions.containsKey(sessionToken)) { "Session already open" }
            sessions[sessionToken] = priority
        }
    }

    override fun closeSession(sessionToken: IBinder?) {
        enforcePermission()
        synchronized(lock) { sessions.remove(sessionToken ?: return) }
    }

    override fun setLightStates(sessionToken: IBinder?, lightIds: IntArray?, states: Array<LightState?>?) {
        enforcePermission()
        synchronized(lock) { require(sessions.containsKey(sessionToken)) { "Invalid session token" } }
        require((lightIds?.size ?: 0) == (states?.size ?: 0)) { "Mismatch between lightIds and states" }
        if (lightIds != null && lightIds.isNotEmpty()) throw IllegalArgumentException("Invalid light: ${lightIds[0]}")
    }
}
