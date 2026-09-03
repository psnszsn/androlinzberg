package dev.artzig.system

import android.Manifest
import android.app.IUiModeManagerCallback
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Binder
import android.os.IBinder
import android.provider.Settings
import android.util.Log

/**
 * The session's IUiModeManager over the real AIDL surface, published as
 * `Context.UI_MODE_SERVICE`.
 *
 * Source note: `com.android.server.UiModeManagerService` for a device with no
 * twilight service, dock or car kit: the UI mode type is always NORMAL, the system
 * night mode lives in `Settings.Secure.UI_NIGHT_MODE` (default
 * `config_defaultNightMode`), per-package night modes are kept as the package
 * configuration overrides AOSP stores in ActivityTaskManager, and every change is
 * delivered to the affected processes as a configuration delta (uiMode night
 * bits) through the hosted activity manager, the way `updateConfiguration` /
 * `updatePackageConfiguration` push it. AUTO and CUSTOM resolve to "no" because no
 * twilight or schedule engine runs here. Car mode and projection stay unserved.
 */
class HostedUiModeManagerService(
    private val context: Context,
    private val activityManager: HostedActivityManagerService,
    private val permissions: HostedPermissions,
) : UnsupportedUiModeManager() {
    private val lock = Any()
    /** Per-uid night modes (a package's processes share its uid; AOSP keys the override by package). */
    private val packageNightModes = HashMap<Int, Int>()
    private val callbacks = LinkedHashMap<IBinder, IUiModeManagerCallback>()
    private var customType = UiModeManager.MODE_NIGHT_CUSTOM_TYPE_SCHEDULE
    private var customStart = 22L * 60 * 60 * 1000
    private var customEnd = 6L * 60 * 60 * 1000
    private var attentionOverlay = UiModeManager.MODE_ATTENTION_THEME_OVERLAY_OFF
    private var nightModeActivatedOverride: Boolean? = null

    private fun validateMode(mode: Int) {
        if (mode < UiModeManager.MODE_NIGHT_AUTO || mode > UiModeManager.MODE_NIGHT_CUSTOM) throw IllegalArgumentException("Unknown mode: $mode")
    }

    private fun nightBitsFor(mode: Int): Int = when (mode) {
        UiModeManager.MODE_NIGHT_YES -> Configuration.UI_MODE_NIGHT_YES
        UiModeManager.MODE_NIGHT_NO -> Configuration.UI_MODE_NIGHT_NO
        else -> if (nightModeActivatedOverride == true) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }

    private fun deltaFor(nightBits: Int): Configuration = Configuration().apply { uiMode = nightBits }

    /** Every process follows the system mode unless its package set its own mode. */
    private fun applySystemMode(mode: Int) {
        val systemBits = nightBitsFor(mode)
        val perPackage = synchronized(lock) { HashMap(packageNightModes) }
        activityManager.scheduleConfigurationDelta(deltaFor(systemBits), null)
        for ((uid, appMode) in perPackage) {
            if (appMode == UiModeManager.MODE_NIGHT_YES || appMode == UiModeManager.MODE_NIGHT_NO) {
                activityManager.scheduleConfigurationDeltaForUid(deltaFor(nightBitsFor(appMode)), uid)
            }
        }
    }

    override fun getCurrentModeType(): Int = Configuration.UI_MODE_TYPE_NORMAL

    override fun getNightMode(): Int = Settings.Secure.getInt(
        context.contentResolver, Settings.Secure.UI_NIGHT_MODE,
        context.resources.getInteger(com.android.internal.R.integer.config_defaultNightMode),
    )

    override fun setNightMode(mode: Int) {
        validateMode(mode)
        if (isNightModeLocked && !permissions.has(Manifest.permission.MODIFY_DAY_NIGHT_MODE)) {
            Log.e(TAG, "Night mode locked, requires MODIFY_DAY_NIGHT_MODE permission")
            return
        }
        synchronized(lock) { nightModeActivatedOverride = null }
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.UI_NIGHT_MODE, mode)
        applySystemMode(mode)
    }

    override fun setApplicationNightMode(mode: Int) {
        validateMode(mode)
        val uid = Binder.getCallingUid()
        synchronized(lock) { packageNightModes[uid] = mode }
        val bits = if (mode == UiModeManager.MODE_NIGHT_YES || mode == UiModeManager.MODE_NIGHT_NO) nightBitsFor(mode) else nightBitsFor(nightMode)
        activityManager.scheduleConfigurationDeltaForUid(deltaFor(bits), uid)
    }

    override fun setNightModeActivated(active: Boolean): Boolean = setNightModeActivatedForCustomMode(UiModeManager.MODE_NIGHT_CUSTOM_TYPE_SCHEDULE, active)

    /** UiModeManagerService.setNightModeActivatedForCustomMode: only an AUTO/CUSTOM mode takes an override. */
    override fun setNightModeActivatedForCustomMode(nightModeCustom: Int, active: Boolean): Boolean {
        val mode = nightMode
        if (mode != UiModeManager.MODE_NIGHT_AUTO && mode != UiModeManager.MODE_NIGHT_CUSTOM) return false
        synchronized(lock) { nightModeActivatedOverride = active }
        applySystemMode(mode)
        return true
    }

    override fun setNightModeCustomType(nightModeCustomType: Int) {
        permissions.enforce(Manifest.permission.MODIFY_DAY_NIGHT_MODE, "setNightModeCustomType")
        synchronized(lock) { customType = nightModeCustomType }
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.UI_NIGHT_MODE, UiModeManager.MODE_NIGHT_CUSTOM)
        applySystemMode(UiModeManager.MODE_NIGHT_CUSTOM)
    }
    override fun getNightModeCustomType(): Int {
        permissions.enforce(Manifest.permission.MODIFY_DAY_NIGHT_MODE, "getNightModeCustomType")
        return synchronized(lock) { if (nightMode == UiModeManager.MODE_NIGHT_CUSTOM) customType else UiModeManager.MODE_NIGHT_CUSTOM_TYPE_UNKNOWN }
    }
    override fun getCustomNightModeStart(): Long = synchronized(lock) { customStart }
    override fun setCustomNightModeStart(time: Long) {
        permissions.enforce(Manifest.permission.MODIFY_DAY_NIGHT_MODE, "setCustomNightModeStart")
        synchronized(lock) { customStart = time }
    }
    override fun getCustomNightModeEnd(): Long = synchronized(lock) { customEnd }
    override fun setCustomNightModeEnd(time: Long) {
        permissions.enforce(Manifest.permission.MODIFY_DAY_NIGHT_MODE, "setCustomNightModeEnd")
        synchronized(lock) { customEnd = time }
    }
    override fun setAttentionModeThemeOverlay(attentionModeThemeOverlayType: Int) {
        permissions.enforce(Manifest.permission.MODIFY_DAY_NIGHT_MODE, "setAttentionModeThemeOverlay")
        synchronized(lock) { attentionOverlay = attentionModeThemeOverlayType }
    }
    override fun getAttentionModeThemeOverlay(): Int {
        permissions.enforce(Manifest.permission.MODIFY_DAY_NIGHT_MODE, "getAttentionModeThemeOverlay")
        return synchronized(lock) { attentionOverlay }
    }
    override fun isUiModeLocked(): Boolean = false
    override fun isNightModeLocked(): Boolean = false
    override fun getContrast(): Float = 0f
    override fun getForceInvertState(): Int = UiModeManager.FORCE_INVERT_TYPE_OFF
    override fun addCallback(callback: IUiModeManagerCallback?) {
        val binder = callback?.asBinder() ?: return
        synchronized(lock) { callbacks[binder] = callback }
    }
    override fun getProjectingPackages(projectionType: Int): MutableList<String?>? = ArrayList()
    override fun getActiveProjectionTypes(): Int = 0

    private companion object {
        const val TAG = "HostedUiModeManager"
    }
}
