@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every IShortcutService method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedShortcutService : android.content.pm.IShortcutService.Stub() {
    override fun setDynamicShortcuts(packageName: String?, shortcutInfoList: android.content.pm.ParceledListSlice<*>?, userId: Int): Boolean = throw UnsupportedOperationException("IShortcutService.setDynamicShortcuts is not hosted")
    override fun addDynamicShortcuts(packageName: String?, shortcutInfoList: android.content.pm.ParceledListSlice<*>?, userId: Int): Boolean = throw UnsupportedOperationException("IShortcutService.addDynamicShortcuts is not hosted")
    override fun removeDynamicShortcuts(packageName: String?, shortcutIds: MutableList<String?>?, userId: Int) { throw UnsupportedOperationException("IShortcutService.removeDynamicShortcuts is not hosted") }
    override fun removeAllDynamicShortcuts(packageName: String?, userId: Int) { throw UnsupportedOperationException("IShortcutService.removeAllDynamicShortcuts is not hosted") }
    override fun updateShortcuts(packageName: String?, shortcuts: android.content.pm.ParceledListSlice<*>?, userId: Int): Boolean = throw UnsupportedOperationException("IShortcutService.updateShortcuts is not hosted")
    override fun requestPinShortcut(packageName: String?, shortcut: android.content.pm.ShortcutInfo?, resultIntent: android.content.IntentSender?, userId: Int): Boolean = throw UnsupportedOperationException("IShortcutService.requestPinShortcut is not hosted")
    override fun createShortcutResultIntent(packageName: String?, shortcut: android.content.pm.ShortcutInfo?, userId: Int): android.content.Intent? = throw UnsupportedOperationException("IShortcutService.createShortcutResultIntent is not hosted")
    override fun disableShortcuts(packageName: String?, shortcutIds: MutableList<String?>?, disabledMessage: CharSequence?, disabledMessageResId: Int, userId: Int) { throw UnsupportedOperationException("IShortcutService.disableShortcuts is not hosted") }
    override fun enableShortcuts(packageName: String?, shortcutIds: MutableList<String?>?, userId: Int) { throw UnsupportedOperationException("IShortcutService.enableShortcuts is not hosted") }
    override fun getMaxShortcutCountPerActivity(packageName: String?, userId: Int): Int = throw UnsupportedOperationException("IShortcutService.getMaxShortcutCountPerActivity is not hosted")
    override fun getRemainingCallCount(packageName: String?, userId: Int): Int = throw UnsupportedOperationException("IShortcutService.getRemainingCallCount is not hosted")
    override fun getRateLimitResetTime(packageName: String?, userId: Int): Long = throw UnsupportedOperationException("IShortcutService.getRateLimitResetTime is not hosted")
    override fun getIconMaxDimensions(packageName: String?, userId: Int): Int = throw UnsupportedOperationException("IShortcutService.getIconMaxDimensions is not hosted")
    override fun reportShortcutUsed(packageName: String?, shortcutId: String?, userId: Int) { throw UnsupportedOperationException("IShortcutService.reportShortcutUsed is not hosted") }
    override fun resetThrottling() { throw UnsupportedOperationException("IShortcutService.resetThrottling is not hosted") }
    override fun onApplicationActive(packageName: String?, userId: Int) { throw UnsupportedOperationException("IShortcutService.onApplicationActive is not hosted") }
    override fun getBackupPayload(user: Int): ByteArray? = throw UnsupportedOperationException("IShortcutService.getBackupPayload is not hosted")
    override fun applyRestore(payload: ByteArray?, user: Int) { throw UnsupportedOperationException("IShortcutService.applyRestore is not hosted") }
    override fun isRequestPinItemSupported(user: Int, requestType: Int): Boolean = throw UnsupportedOperationException("IShortcutService.isRequestPinItemSupported is not hosted")
    override fun getShareTargets(packageName: String?, filter: android.content.IntentFilter?, userId: Int): android.content.pm.ParceledListSlice<*>? = throw UnsupportedOperationException("IShortcutService.getShareTargets is not hosted")
    override fun hasShareTargets(packageName: String?, packageToCheck: String?, userId: Int): Boolean = throw UnsupportedOperationException("IShortcutService.hasShareTargets is not hosted")
    override fun removeLongLivedShortcuts(packageName: String?, shortcutIds: MutableList<String?>?, userId: Int) { throw UnsupportedOperationException("IShortcutService.removeLongLivedShortcuts is not hosted") }
    override fun getShortcuts(packageName: String?, matchFlags: Int, userId: Int): android.content.pm.ParceledListSlice<*>? = throw UnsupportedOperationException("IShortcutService.getShortcuts is not hosted")
    override fun pushDynamicShortcut(packageName: String?, shortcut: android.content.pm.ShortcutInfo?, userId: Int) { throw UnsupportedOperationException("IShortcutService.pushDynamicShortcut is not hosted") }
}
