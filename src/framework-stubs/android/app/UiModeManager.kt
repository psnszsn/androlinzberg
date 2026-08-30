package android.app

import android.content.Context
import android.content.res.Configuration

/**
 * Hosted UiModeManager surface.
 *
 * This follows the vendored AOSP UiModeManager.setNightMode(int) and
 * UiModeManager.setApplicationNightMode(int) contracts. The AOSP class cannot
 * be compiled directly because its IUiModeManager Binder service lives in
 * system_server, so HostedUiModeController owns that missing process seam.
 */
class UiModeManager(private val context: Context?) {
    constructor() : this(null)

    fun getCurrentModeType(): Int {
        val modeType = context
            ?.resources
            ?.configuration
            ?.uiMode
            ?.and(Configuration.UI_MODE_TYPE_MASK)
            ?: Configuration.UI_MODE_TYPE_UNDEFINED
        return if (modeType == Configuration.UI_MODE_TYPE_UNDEFINED) {
            Configuration.UI_MODE_TYPE_NORMAL
        } else {
            modeType
        }
    }

    fun getNightMode(): Int = HostedUiModeController.getSystemNightMode(context)

    fun setNightMode(mode: Int) {
        HostedUiModeController.setSystemNightMode(context, mode)
    }

    fun setApplicationNightMode(mode: Int) {
        HostedUiModeController.setApplicationNightMode(context, mode)
    }

    companion object {
        const val MODE_NIGHT_AUTO: Int = 0
        const val MODE_NIGHT_NO: Int = 1
        const val MODE_NIGHT_YES: Int = 2
        const val MODE_NIGHT_CUSTOM: Int = 3
    }
}
