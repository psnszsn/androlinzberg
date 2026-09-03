@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every IUiModeManager method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedUiModeManager : android.app.IUiModeManager.Stub() {
    override fun addCallback(callback: android.app.IUiModeManagerCallback?) { throw UnsupportedOperationException("IUiModeManager.addCallback is not hosted") }
    override fun enableCarMode(flags: Int, priority: Int, callingPackage: String?) { throw UnsupportedOperationException("IUiModeManager.enableCarMode is not hosted") }
    override fun disableCarMode(flags: Int) { throw UnsupportedOperationException("IUiModeManager.disableCarMode is not hosted") }
    override fun disableCarModeByCallingPackage(flags: Int, callingPackage: String?) { throw UnsupportedOperationException("IUiModeManager.disableCarModeByCallingPackage is not hosted") }
    override fun getCurrentModeType(): Int = throw UnsupportedOperationException("IUiModeManager.getCurrentModeType is not hosted")
    override fun setNightMode(mode: Int) { throw UnsupportedOperationException("IUiModeManager.setNightMode is not hosted") }
    override fun getNightMode(): Int = throw UnsupportedOperationException("IUiModeManager.getNightMode is not hosted")
    override fun setNightModeCustomType(nightModeCustomType: Int) { throw UnsupportedOperationException("IUiModeManager.setNightModeCustomType is not hosted") }
    override fun getNightModeCustomType(): Int = throw UnsupportedOperationException("IUiModeManager.getNightModeCustomType is not hosted")
    override fun setAttentionModeThemeOverlay(attentionModeThemeOverlayType: Int) { throw UnsupportedOperationException("IUiModeManager.setAttentionModeThemeOverlay is not hosted") }
    override fun getAttentionModeThemeOverlay(): Int = throw UnsupportedOperationException("IUiModeManager.getAttentionModeThemeOverlay is not hosted")
    override fun setApplicationNightMode(mode: Int) { throw UnsupportedOperationException("IUiModeManager.setApplicationNightMode is not hosted") }
    override fun isUiModeLocked(): Boolean = throw UnsupportedOperationException("IUiModeManager.isUiModeLocked is not hosted")
    override fun isNightModeLocked(): Boolean = throw UnsupportedOperationException("IUiModeManager.isNightModeLocked is not hosted")
    override fun setNightModeActivatedForCustomMode(nightModeCustom: Int, active: Boolean): Boolean = throw UnsupportedOperationException("IUiModeManager.setNightModeActivatedForCustomMode is not hosted")
    override fun setNightModeActivated(active: Boolean): Boolean = throw UnsupportedOperationException("IUiModeManager.setNightModeActivated is not hosted")
    override fun getCustomNightModeStart(): Long = throw UnsupportedOperationException("IUiModeManager.getCustomNightModeStart is not hosted")
    override fun setCustomNightModeStart(time: Long) { throw UnsupportedOperationException("IUiModeManager.setCustomNightModeStart is not hosted") }
    override fun getCustomNightModeEnd(): Long = throw UnsupportedOperationException("IUiModeManager.getCustomNightModeEnd is not hosted")
    override fun setCustomNightModeEnd(time: Long) { throw UnsupportedOperationException("IUiModeManager.setCustomNightModeEnd is not hosted") }
    override fun requestProjection(binder: android.os.IBinder?, projectionType: Int, callingPackage: String?): Boolean = throw UnsupportedOperationException("IUiModeManager.requestProjection is not hosted")
    override fun releaseProjection(projectionType: Int, callingPackage: String?): Boolean = throw UnsupportedOperationException("IUiModeManager.releaseProjection is not hosted")
    override fun addOnProjectionStateChangedListener(listener: android.app.IOnProjectionStateChangedListener?, projectionType: Int) { throw UnsupportedOperationException("IUiModeManager.addOnProjectionStateChangedListener is not hosted") }
    override fun removeOnProjectionStateChangedListener(listener: android.app.IOnProjectionStateChangedListener?) { throw UnsupportedOperationException("IUiModeManager.removeOnProjectionStateChangedListener is not hosted") }
    override fun getProjectingPackages(projectionType: Int): MutableList<String?>? = throw UnsupportedOperationException("IUiModeManager.getProjectingPackages is not hosted")
    override fun getActiveProjectionTypes(): Int = throw UnsupportedOperationException("IUiModeManager.getActiveProjectionTypes is not hosted")
    override fun getContrast(): Float = throw UnsupportedOperationException("IUiModeManager.getContrast is not hosted")
    override fun getForceInvertState(): Int = throw UnsupportedOperationException("IUiModeManager.getForceInvertState is not hosted")
}
