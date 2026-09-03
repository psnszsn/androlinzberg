package dev.artzig.system

import android.hardware.display.ColorDisplayManager
import android.hardware.display.Time
import android.os.Binder
import android.os.Process
import java.time.LocalTime

/**
 * The session's color display service over the real `IColorDisplayManager`
 * surface, published as `Context.COLOR_DISPLAY_SERVICE`.
 *
 * Source note: `com.android.server.display.color.ColorDisplayService.BinderService`
 * for a display without color transforms: no capabilities, night display off
 * with the AOSP default temperature and schedule, natural color mode, no white
 * balance, reduce-bright-colors off. Setters keep the
 * CONTROL_DISPLAY_COLOR_TRANSFORMS check and, for the system, record the value
 * so the getters agree with what was set (the AOSP settings round trip).
 */
class HostedColorDisplayService : android.hardware.display.IColorDisplayManager.Stub(), android.hardware.display.IColorDisplayManager by unsupported<android.hardware.display.IColorDisplayManager>() {
    override fun asBinder(): android.os.IBinder = this

    private val lock = Any()
    private var nightDisplayActivated = false
    private var nightDisplayTemperature = DEFAULT_NIGHT_DISPLAY_TEMPERATURE
    private var nightDisplayAutoMode = ColorDisplayManager.AUTO_MODE_DISABLED
    private var nightDisplayStart = Time(LocalTime.of(22, 0))
    private var nightDisplayEnd = Time(LocalTime.of(6, 0))
    private var colorMode = ColorDisplayManager.COLOR_MODE_NATURAL
    private var reduceBrightColorsActivated = false
    private var reduceBrightColorsStrength = 50

    private fun enforcePermission() {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw SecurityException("Requires android.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS")
        }
    }

    override fun isDeviceColorManaged(): Boolean = false
    override fun setSaturationLevel(saturationLevel: Int): Boolean {
        enforcePermission()
        return false
    }
    override fun setAppSaturationLevel(packageName: String?, saturationLevel: Int): Boolean {
        enforcePermission()
        return false
    }
    override fun isSaturationActivated(): Boolean = false
    override fun getTransformCapabilities(): Int {
        enforcePermission()
        return ColorDisplayManager.CAPABILITY_NONE
    }
    override fun isNightDisplayActivated(): Boolean = synchronized(lock) { nightDisplayActivated }
    override fun setNightDisplayActivated(activated: Boolean): Boolean {
        enforcePermission()
        synchronized(lock) { nightDisplayActivated = activated }
        return true
    }
    override fun getNightDisplayColorTemperature(): Int = synchronized(lock) { nightDisplayTemperature }
    override fun setNightDisplayColorTemperature(temperature: Int): Boolean {
        enforcePermission()
        synchronized(lock) { nightDisplayTemperature = temperature }
        return true
    }
    override fun getNightDisplayAutoMode(): Int {
        enforcePermission()
        return synchronized(lock) { nightDisplayAutoMode }
    }
    override fun getNightDisplayAutoModeRaw(): Int = synchronized(lock) { nightDisplayAutoMode }
    override fun setNightDisplayAutoMode(autoMode: Int): Boolean {
        enforcePermission()
        synchronized(lock) { nightDisplayAutoMode = autoMode }
        return true
    }
    override fun getNightDisplayCustomStartTime(): Time? = synchronized(lock) { nightDisplayStart }
    override fun setNightDisplayCustomStartTime(time: Time?): Boolean {
        enforcePermission()
        synchronized(lock) { nightDisplayStart = time ?: return false }
        return true
    }
    override fun getNightDisplayCustomEndTime(): Time? = synchronized(lock) { nightDisplayEnd }
    override fun setNightDisplayCustomEndTime(time: Time?): Boolean {
        enforcePermission()
        synchronized(lock) { nightDisplayEnd = time ?: return false }
        return true
    }
    override fun getColorMode(): Int = synchronized(lock) { colorMode }
    override fun setColorMode(colorMode: Int) {
        enforcePermission()
        synchronized(lock) { this.colorMode = colorMode }
    }
    override fun isDisplayWhiteBalanceEnabled(): Boolean = false
    override fun setDisplayWhiteBalanceEnabled(enabled: Boolean): Boolean {
        enforcePermission()
        return false
    }
    override fun isReduceBrightColorsActivated(): Boolean = synchronized(lock) { reduceBrightColorsActivated }
    override fun setReduceBrightColorsActivated(activated: Boolean): Boolean {
        enforcePermission()
        synchronized(lock) { reduceBrightColorsActivated = activated }
        return true
    }
    override fun getReduceBrightColorsStrength(): Int = synchronized(lock) { reduceBrightColorsStrength }
    override fun setReduceBrightColorsStrength(strength: Int): Boolean {
        enforcePermission()
        synchronized(lock) { reduceBrightColorsStrength = strength }
        return true
    }
    override fun getReduceBrightColorsOffsetFactor(): Float = 0f

    private companion object {
        /** config_nightDisplayColorTemperatureDefault. */
        const val DEFAULT_NIGHT_DISPLAY_TEMPERATURE = 2850
    }
}
