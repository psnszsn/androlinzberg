@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every IThermalService method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedThermalService : android.os.IThermalService.Stub() {
    override fun registerThermalEventListener(listener: android.os.IThermalEventListener?): Boolean = throw UnsupportedOperationException("IThermalService.registerThermalEventListener is not hosted")
    override fun registerThermalEventListenerWithType(listener: android.os.IThermalEventListener?, type: Int): Boolean = throw UnsupportedOperationException("IThermalService.registerThermalEventListenerWithType is not hosted")
    override fun unregisterThermalEventListener(listener: android.os.IThermalEventListener?): Boolean = throw UnsupportedOperationException("IThermalService.unregisterThermalEventListener is not hosted")
    override fun getCurrentTemperatures(): Array<android.os.Temperature?>? = throw UnsupportedOperationException("IThermalService.getCurrentTemperatures is not hosted")
    override fun getCurrentTemperaturesWithType(type: Int): Array<android.os.Temperature?>? = throw UnsupportedOperationException("IThermalService.getCurrentTemperaturesWithType is not hosted")
    override fun registerThermalStatusListener(listener: android.os.IThermalStatusListener?): Boolean = throw UnsupportedOperationException("IThermalService.registerThermalStatusListener is not hosted")
    override fun unregisterThermalStatusListener(listener: android.os.IThermalStatusListener?): Boolean = throw UnsupportedOperationException("IThermalService.unregisterThermalStatusListener is not hosted")
    override fun getCurrentThermalStatus(): Int = throw UnsupportedOperationException("IThermalService.getCurrentThermalStatus is not hosted")
    override fun getCurrentCoolingDevices(): Array<android.os.CoolingDevice?>? = throw UnsupportedOperationException("IThermalService.getCurrentCoolingDevices is not hosted")
    override fun getCurrentCoolingDevicesWithType(type: Int): Array<android.os.CoolingDevice?>? = throw UnsupportedOperationException("IThermalService.getCurrentCoolingDevicesWithType is not hosted")
    override fun getThermalHeadroom(forecastSeconds: Int): Float = throw UnsupportedOperationException("IThermalService.getThermalHeadroom is not hosted")
    override fun getThermalHeadroomThresholds(): FloatArray? = throw UnsupportedOperationException("IThermalService.getThermalHeadroomThresholds is not hosted")
    override fun registerThermalHeadroomListener(listener: android.os.IThermalHeadroomListener?): Boolean = throw UnsupportedOperationException("IThermalService.registerThermalHeadroomListener is not hosted")
    override fun unregisterThermalHeadroomListener(listener: android.os.IThermalHeadroomListener?): Boolean = throw UnsupportedOperationException("IThermalService.unregisterThermalHeadroomListener is not hosted")
}
