package dev.artzig.system

import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.IBinder
import android.view.KeyEvent
import com.android.internal.statusbar.IAddTileResultCallback

/**
 * Status-bar service for a desktop session, which has no SystemUI process or
 * notification shade. Operations that only mutate or reveal those absent UI
 * surfaces are successful no-ops; unsupported policy callbacks still use the
 * shared diagnostic delegate.
 */
class HostedStatusBarService :
    com.android.internal.statusbar.IStatusBarService.Stub(),
    com.android.internal.statusbar.IStatusBarService by unsupported<com.android.internal.statusbar.IStatusBarService>() {
    override fun asBinder(): IBinder = this

    override fun expandNotificationsPanel() = Unit
    override fun collapsePanels() = Unit
    override fun togglePanel() = Unit
    override fun expandSettingsPanel(subPanel: String?) = Unit
    override fun disable(what: Int, token: IBinder?, pkg: String?) = Unit
    override fun disableForUser(what: Int, token: IBinder?, pkg: String?, userId: Int) = Unit
    override fun disable2(what: Int, token: IBinder?, pkg: String?) = Unit
    override fun disable2ForUser(what: Int, token: IBinder?, pkg: String?, userId: Int) = Unit
    override fun getDisableFlags(token: IBinder?, userId: Int): IntArray = intArrayOf(0, 0)
    override fun setIcon(slot: String?, iconPackage: String?, iconId: Int, iconLevel: Int, contentDescription: String?) = Unit
    override fun setIconVisibility(slot: String?, visible: Boolean) = Unit
    override fun removeIcon(slot: String?) = Unit
    override fun handleSystemKey(key: KeyEvent?) = Unit
    override fun getLastSystemKey(): Int = -1
    override fun requestTileServiceListeningState(componentName: ComponentName?, userId: Int) = Unit
    override fun requestAddTile(componentName: ComponentName?, label: CharSequence?, icon: Icon?, userId: Int, callback: IAddTileResultCallback?) {
        callback?.onTileRequest(android.app.StatusBarManager.TILE_ADD_REQUEST_ERROR_NO_STATUS_BAR_SERVICE)
    }
    override fun cancelRequestAddTile(packageName: String?) = Unit
    override fun setNavBarMode(navBarMode: Int) = Unit
    override fun getNavBarMode(): Int = android.app.StatusBarManager.NAV_BAR_MODE_DEFAULT
}
