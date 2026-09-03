@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every ILightsManager method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedLightsManager : android.hardware.lights.ILightsManager.Stub() {
    override fun getLights(): MutableList<android.hardware.lights.Light?>? = throw UnsupportedOperationException("ILightsManager.getLights is not hosted")
    override fun getLightState(lightId: Int): android.hardware.lights.LightState? = throw UnsupportedOperationException("ILightsManager.getLightState is not hosted")
    override fun openSession(sessionToken: android.os.IBinder?, priority: Int) { throw UnsupportedOperationException("ILightsManager.openSession is not hosted") }
    override fun closeSession(sessionToken: android.os.IBinder?) { throw UnsupportedOperationException("ILightsManager.closeSession is not hosted") }
    override fun setLightStates(sessionToken: android.os.IBinder?, lightIds: IntArray?, states: Array<android.hardware.lights.LightState?>?) { throw UnsupportedOperationException("ILightsManager.setLightStates is not hosted") }
}
