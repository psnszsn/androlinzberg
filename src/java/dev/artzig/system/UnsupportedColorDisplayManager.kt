@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every IColorDisplayManager method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedColorDisplayManager : android.hardware.display.IColorDisplayManager.Stub() {
    override fun isDeviceColorManaged(): Boolean = throw UnsupportedOperationException("IColorDisplayManager.isDeviceColorManaged is not hosted")
    override fun setSaturationLevel(saturationLevel: Int): Boolean = throw UnsupportedOperationException("IColorDisplayManager.setSaturationLevel is not hosted")
    override fun setAppSaturationLevel(packageName: String?, saturationLevel: Int): Boolean = throw UnsupportedOperationException("IColorDisplayManager.setAppSaturationLevel is not hosted")
    override fun isSaturationActivated(): Boolean = throw UnsupportedOperationException("IColorDisplayManager.isSaturationActivated is not hosted")
    override fun getTransformCapabilities(): Int = throw UnsupportedOperationException("IColorDisplayManager.getTransformCapabilities is not hosted")
    override fun isNightDisplayActivated(): Boolean = throw UnsupportedOperationException("IColorDisplayManager.isNightDisplayActivated is not hosted")
    override fun setNightDisplayActivated(activated: Boolean): Boolean = throw UnsupportedOperationException("IColorDisplayManager.setNightDisplayActivated is not hosted")
    override fun getNightDisplayColorTemperature(): Int = throw UnsupportedOperationException("IColorDisplayManager.getNightDisplayColorTemperature is not hosted")
    override fun setNightDisplayColorTemperature(temperature: Int): Boolean = throw UnsupportedOperationException("IColorDisplayManager.setNightDisplayColorTemperature is not hosted")
    override fun getNightDisplayAutoMode(): Int = throw UnsupportedOperationException("IColorDisplayManager.getNightDisplayAutoMode is not hosted")
    override fun getNightDisplayAutoModeRaw(): Int = throw UnsupportedOperationException("IColorDisplayManager.getNightDisplayAutoModeRaw is not hosted")
    override fun setNightDisplayAutoMode(autoMode: Int): Boolean = throw UnsupportedOperationException("IColorDisplayManager.setNightDisplayAutoMode is not hosted")
    override fun getNightDisplayCustomStartTime(): android.hardware.display.Time? = throw UnsupportedOperationException("IColorDisplayManager.getNightDisplayCustomStartTime is not hosted")
    override fun setNightDisplayCustomStartTime(time: android.hardware.display.Time?): Boolean = throw UnsupportedOperationException("IColorDisplayManager.setNightDisplayCustomStartTime is not hosted")
    override fun getNightDisplayCustomEndTime(): android.hardware.display.Time? = throw UnsupportedOperationException("IColorDisplayManager.getNightDisplayCustomEndTime is not hosted")
    override fun setNightDisplayCustomEndTime(time: android.hardware.display.Time?): Boolean = throw UnsupportedOperationException("IColorDisplayManager.setNightDisplayCustomEndTime is not hosted")
    override fun getColorMode(): Int = throw UnsupportedOperationException("IColorDisplayManager.getColorMode is not hosted")
    override fun setColorMode(colorMode: Int) { throw UnsupportedOperationException("IColorDisplayManager.setColorMode is not hosted") }
    override fun isDisplayWhiteBalanceEnabled(): Boolean = throw UnsupportedOperationException("IColorDisplayManager.isDisplayWhiteBalanceEnabled is not hosted")
    override fun setDisplayWhiteBalanceEnabled(enabled: Boolean): Boolean = throw UnsupportedOperationException("IColorDisplayManager.setDisplayWhiteBalanceEnabled is not hosted")
    override fun isReduceBrightColorsActivated(): Boolean = throw UnsupportedOperationException("IColorDisplayManager.isReduceBrightColorsActivated is not hosted")
    override fun setReduceBrightColorsActivated(activated: Boolean): Boolean = throw UnsupportedOperationException("IColorDisplayManager.setReduceBrightColorsActivated is not hosted")
    override fun getReduceBrightColorsStrength(): Int = throw UnsupportedOperationException("IColorDisplayManager.getReduceBrightColorsStrength is not hosted")
    override fun setReduceBrightColorsStrength(strength: Int): Boolean = throw UnsupportedOperationException("IColorDisplayManager.setReduceBrightColorsStrength is not hosted")
    override fun getReduceBrightColorsOffsetFactor(): Float = throw UnsupportedOperationException("IColorDisplayManager.getReduceBrightColorsOffsetFactor is not hosted")
}
