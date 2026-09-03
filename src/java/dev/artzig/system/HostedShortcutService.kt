package dev.artzig.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.ParceledListSlice
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.util.Log

/**
 * The session's IShortcutService over the real AIDL surface, published as
 * `Context.SHORTCUT_SERVICE`.
 *
 * Source note: `com.android.server.pm.ShortcutService` and `ShortcutPackage` for a
 * device with no launcher: the per-package shortcut table with AOSP's
 * dynamic/pinned/disabled flag transitions (dynamic shortcuts replaced or added,
 * removal keeps a pinned shortcut with its dynamic flag cleared, disabling drops an
 * unpinned shortcut and marks a pinned one, updates merge non-null fields, a push
 * evicts the lowest-ranked dynamic shortcut at the limit), the incoming-shortcut
 * checks (`Shortcut ID must be provided`, `Shortcut Intent must be provided`,
 * `Max number of dynamic shortcuts exceeded`, calling-package verification), the
 * default limits (15 per activity, 10 calls per interval, 96dp icons), and the
 * pin-request surface answering "unsupported" because no default launcher exists.
 * Shortcuts live for the session; the on-disk shortcut XML is not written.
 */
class HostedShortcutService(private val context: Context, private val permissions: HostedPermissions) : UnsupportedShortcutService() {
    private val lock = Any()
    /** package -> id -> shortcut, in insertion order. */
    private val packages = HashMap<String, LinkedHashMap<String, ShortcutInfo>>()

    private fun verifyCaller(packageName: String?) = permissions.enforceCallerPackage(packageName, "shortcut")

    private fun table(packageName: String): LinkedHashMap<String, ShortcutInfo> = packages.getOrPut(packageName) { LinkedHashMap() }

    @Suppress("UNCHECKED_CAST")
    private fun incoming(list: ParceledListSlice<*>?): List<ShortcutInfo> = (list?.list as? List<ShortcutInfo>) ?: emptyList()

    /** ShortcutService.fixUpIncomingShortcutInfo: id and intent are mandatory; the record is the caller's. */
    private fun fixUp(packageName: String, userId: Int, shortcut: ShortcutInfo, forPinRequest: Boolean): ShortcutInfo {
        if (shortcut.id.isNullOrEmpty()) throw IllegalArgumentException("Shortcut ID must be provided")
        if (!forPinRequest && shortcut.intent == null) throw IllegalArgumentException("Shortcut Intent must be provided")
        if (shortcut.getPackage() != null && shortcut.getPackage() != packageName) {
            throw SecurityException("Shortcut package name mismatch: ${shortcut.getPackage()} vs $packageName")
        }
        shortcut.setUserIdAndPackage(userId, packageName)
        return shortcut
    }

    /** ShortcutPackage.enforceMaxActivityShortcuts: dynamic + manifest shortcuts per activity. */
    private fun enforceMaxActivityShortcuts(table: Map<String, ShortcutInfo>, additions: List<ShortcutInfo>, replacingDynamic: Boolean) {
        val counts = HashMap<Any, Int>()
        if (!replacingDynamic) {
            for (s in table.values) if ((s.isDynamic || s.isDeclaredInManifest) && additions.none { it.id == s.id }) counts.merge(s.activity ?: NO_ACTIVITY, 1, Int::plus)
        } else {
            for (s in table.values) if (s.isDeclaredInManifest) counts.merge(s.activity ?: NO_ACTIVITY, 1, Int::plus)
        }
        for (s in additions) counts.merge(s.activity ?: NO_ACTIVITY, 1, Int::plus)
        if (counts.values.any { it > MAX_SHORTCUTS_PER_ACTIVITY }) throw IllegalArgumentException("Max number of dynamic shortcuts exceeded")
    }

    private fun mergeIntoTable(table: LinkedHashMap<String, ShortcutInfo>, shortcut: ShortcutInfo) {
        val existing = table[shortcut.id]
        shortcut.addFlags(ShortcutInfo.FLAG_DYNAMIC)
        if (existing != null && existing.isPinned) shortcut.addFlags(ShortcutInfo.FLAG_PINNED)
        shortcut.clearFlags(ShortcutInfo.FLAG_DISABLED)
        shortcut.setDisabledReason(ShortcutInfo.DISABLED_REASON_NOT_DISABLED)
        table[shortcut.id] = shortcut
    }

    override fun setDynamicShortcuts(packageName: String?, shortcutInfoList: ParceledListSlice<*>?, userId: Int): Boolean {
        verifyCaller(packageName)
        val list = incoming(shortcutInfoList).map { fixUp(packageName!!, userId, it, false) }
        synchronized(lock) {
            val table = table(packageName!!)
            enforceMaxActivityShortcuts(table, list, replacingDynamic = true)
            // ShortcutPackage.deleteAllDynamicShortcuts: pinned ones survive without the dynamic flag.
            for (id in table.keys.toList()) {
                val s = table.getValue(id)
                if (s.isPinned) s.clearFlags(ShortcutInfo.FLAG_DYNAMIC) else if (s.isDynamic) table.remove(id)
            }
            for (s in list) mergeIntoTable(table, s)
        }
        return true
    }

    override fun addDynamicShortcuts(packageName: String?, shortcutInfoList: ParceledListSlice<*>?, userId: Int): Boolean {
        verifyCaller(packageName)
        val list = incoming(shortcutInfoList).map { fixUp(packageName!!, userId, it, false) }
        synchronized(lock) {
            val table = table(packageName!!)
            enforceMaxActivityShortcuts(table, list, replacingDynamic = false)
            for (s in list) mergeIntoTable(table, s)
        }
        return true
    }

    /** ShortcutService.pushDynamicShortcut: at the limit the lowest-ranked dynamic shortcut of the activity goes. */
    override fun pushDynamicShortcut(packageName: String?, shortcut: ShortcutInfo?, userId: Int) {
        verifyCaller(packageName)
        val incoming = fixUp(packageName!!, userId, shortcut ?: throw IllegalArgumentException("shortcut must not be null"), false)
        synchronized(lock) {
            val table = table(packageName)
            if (table[incoming.id] == null) {
                val sameActivity = table.values.filter { (it.isDynamic || it.isDeclaredInManifest) && (it.activity ?: NO_ACTIVITY) == (incoming.activity ?: NO_ACTIVITY) }
                if (sameActivity.size >= MAX_SHORTCUTS_PER_ACTIVITY) {
                    val victim = sameActivity.filter { it.isDynamic }.maxByOrNull { it.rank } ?: throw IllegalArgumentException("Max number of dynamic shortcuts exceeded")
                    if (victim.isPinned) victim.clearFlags(ShortcutInfo.FLAG_DYNAMIC) else table.remove(victim.id)
                }
            }
            mergeIntoTable(table, incoming)
        }
    }

    private fun removeDynamic(packageName: String, ids: Collection<String>?) {
        synchronized(lock) {
            val table = packages[packageName] ?: return
            for (id in (ids ?: table.keys.toList())) {
                val s = table[id] ?: continue
                if (s.isPinned) s.clearFlags(ShortcutInfo.FLAG_DYNAMIC) else if (!s.isDeclaredInManifest) table.remove(id)
            }
        }
    }

    override fun removeDynamicShortcuts(packageName: String?, shortcutIds: MutableList<String?>?, userId: Int) {
        verifyCaller(packageName)
        removeDynamic(packageName!!, shortcutIds?.filterNotNull() ?: emptyList())
    }
    override fun removeAllDynamicShortcuts(packageName: String?, userId: Int) {
        verifyCaller(packageName)
        removeDynamic(packageName!!, null)
    }
    override fun removeLongLivedShortcuts(packageName: String?, shortcutIds: MutableList<String?>?, userId: Int) {
        verifyCaller(packageName)
        synchronized(lock) {
            val table = packages[packageName!!] ?: return
            for (id in shortcutIds?.filterNotNull() ?: emptyList()) {
                val s = table[id] ?: continue
                s.clearFlags(ShortcutInfo.FLAG_CACHED_ALL or ShortcutInfo.FLAG_DYNAMIC)
                if (!s.isPinned && !s.isDeclaredInManifest) table.remove(id)
            }
        }
    }

    /** ShortcutService.updateShortcuts: fields of an existing shortcut are replaced where the update is non-null. */
    override fun updateShortcuts(packageName: String?, shortcuts: ParceledListSlice<*>?, userId: Int): Boolean {
        verifyCaller(packageName)
        val list = incoming(shortcuts).map { fixUp(packageName!!, userId, it, true) }
        synchronized(lock) {
            val table = packages[packageName!!] ?: return true
            for (update in list) {
                val target = table[update.id] ?: continue
                if (target.isImmutable) throw IllegalArgumentException("Manifest shortcut ID=${update.id} may not be manipulated via APIs")
                target.copyNonNullFieldsFrom(update)
            }
        }
        return true
    }

    override fun disableShortcuts(packageName: String?, shortcutIds: MutableList<String?>?, disabledMessage: CharSequence?, disabledMessageResId: Int, userId: Int) {
        verifyCaller(packageName)
        synchronized(lock) {
            val table = packages[packageName!!] ?: return
            for (id in shortcutIds?.filterNotNull() ?: emptyList()) {
                val s = table[id] ?: continue
                if (s.isImmutable) throw IllegalArgumentException("Manifest shortcut ID=$id may not be manipulated via APIs")
                // ShortcutPackage.disableWithId: only a pinned shortcut stays, disabled.
                if (s.isPinned) {
                    s.addFlags(ShortcutInfo.FLAG_DISABLED)
                    s.clearFlags(ShortcutInfo.FLAG_DYNAMIC)
                    s.setDisabledReason(ShortcutInfo.DISABLED_REASON_BY_APP)
                    if (disabledMessage != null) s.setDisabledMessage(disabledMessage)
                } else {
                    table.remove(id)
                }
            }
        }
    }

    override fun enableShortcuts(packageName: String?, shortcutIds: MutableList<String?>?, userId: Int) {
        verifyCaller(packageName)
        synchronized(lock) {
            val table = packages[packageName!!] ?: return
            for (id in shortcutIds?.filterNotNull() ?: emptyList()) {
                val s = table[id] ?: continue
                if (s.isImmutable) throw IllegalArgumentException("Manifest shortcut ID=$id may not be manipulated via APIs")
                s.clearFlags(ShortcutInfo.FLAG_DISABLED)
                s.setDisabledReason(ShortcutInfo.DISABLED_REASON_NOT_DISABLED)
            }
        }
    }

    override fun getShortcuts(packageName: String?, matchFlags: Int, userId: Int): ParceledListSlice<*>? {
        verifyCaller(packageName)
        val matchDynamic = matchFlags and ShortcutManager.FLAG_MATCH_DYNAMIC != 0
        val matchPinned = matchFlags and ShortcutManager.FLAG_MATCH_PINNED != 0
        val matchManifest = matchFlags and ShortcutManager.FLAG_MATCH_MANIFEST != 0
        val matchCached = matchFlags and ShortcutManager.FLAG_MATCH_CACHED != 0
        val result = synchronized(lock) {
            (packages[packageName!!]?.values ?: emptyList()).filter {
                (matchDynamic && it.isDynamic) || (matchPinned && it.isPinned) || (matchManifest && it.isDeclaredInManifest) || (matchCached && it.isCached)
            }.map { it.clone(ShortcutInfo.CLONE_REMOVE_FOR_CREATOR).also { c -> c.addFlags(ShortcutInfo.FLAG_RETURNED_BY_SERVICE) } }
        }
        return ParceledListSlice(result)
    }

    override fun getMaxShortcutCountPerActivity(packageName: String?, userId: Int): Int {
        verifyCaller(packageName)
        return MAX_SHORTCUTS_PER_ACTIVITY
    }
    override fun getRemainingCallCount(packageName: String?, userId: Int): Int {
        verifyCaller(packageName)
        return MAX_UPDATES_PER_INTERVAL
    }
    override fun getRateLimitResetTime(packageName: String?, userId: Int): Long {
        verifyCaller(packageName)
        return System.currentTimeMillis() + RESET_INTERVAL_MILLIS
    }
    override fun getIconMaxDimensions(packageName: String?, userId: Int): Int {
        verifyCaller(packageName)
        return (MAX_ICON_DIMENSION_DP * context.resources.displayMetrics.density).toInt()
    }
    override fun reportShortcutUsed(packageName: String?, shortcutId: String?, userId: Int) {
        verifyCaller(packageName)
        Log.i(TAG, "shortcut used: $packageName/$shortcutId")
    }
    override fun onApplicationActive(packageName: String?, userId: Int) = verifyCaller(packageName)
    override fun resetThrottling() = permissions.enforceSystem("resetThrottling")

    /** No default launcher can take a pin request. */
    override fun isRequestPinItemSupported(user: Int, requestType: Int): Boolean = false
    override fun requestPinShortcut(packageName: String?, shortcut: ShortcutInfo?, resultIntent: IntentSender?, userId: Int): Boolean {
        verifyCaller(packageName)
        fixUp(packageName!!, userId, shortcut ?: throw IllegalArgumentException("shortcut must not be null"), true)
        return false
    }
    override fun createShortcutResultIntent(packageName: String?, shortcut: ShortcutInfo?, userId: Int): Intent? {
        verifyCaller(packageName)
        return null
    }
    override fun getShareTargets(packageName: String?, filter: IntentFilter?, userId: Int): ParceledListSlice<*>? {
        verifyCaller(packageName)
        return ParceledListSlice(ArrayList<ShortcutManager.ShareShortcutInfo>())
    }
    override fun hasShareTargets(packageName: String?, packageToCheck: String?, userId: Int): Boolean {
        verifyCaller(packageName)
        return false
    }
    override fun getBackupPayload(user: Int): ByteArray? {
        permissions.enforceSystem("getBackupPayload")
        return null
    }
    override fun applyRestore(payload: ByteArray?, user: Int) = permissions.enforceSystem("applyRestore")

    /** For the launcher-apps service: every alive shortcut of a package. */
    internal fun shortcutsOf(packageName: String): List<ShortcutInfo> = synchronized(lock) { packages[packageName]?.values?.toList() ?: emptyList() }

    private companion object {
        const val TAG = "HostedShortcutService"
        const val MAX_SHORTCUTS_PER_ACTIVITY = 15
        const val MAX_UPDATES_PER_INTERVAL = 10
        const val RESET_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
        const val MAX_ICON_DIMENSION_DP = 96
        val NO_ACTIVITY = Any()
    }
}
