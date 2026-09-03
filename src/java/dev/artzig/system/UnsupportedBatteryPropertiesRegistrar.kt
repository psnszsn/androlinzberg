@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every IBatteryPropertiesRegistrar method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedBatteryPropertiesRegistrar : android.os.IBatteryPropertiesRegistrar.Stub() {
    override fun getProperty(id: Int, prop: android.os.BatteryProperty?): Int = throw UnsupportedOperationException("IBatteryPropertiesRegistrar.getProperty is not hosted")
    override fun scheduleUpdate() { throw UnsupportedOperationException("IBatteryPropertiesRegistrar.scheduleUpdate is not hosted") }
}
