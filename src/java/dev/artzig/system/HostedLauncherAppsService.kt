package dev.artzig.system

import android.app.IApplicationThread
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.ApplicationInfo
import android.content.pm.IOnAppsChangedListener
import android.content.pm.IPackageInstallerCallback
import android.content.pm.IShortcutChangeCallback
import android.content.pm.IncrementalStatesInfo
import android.content.pm.LauncherActivityInfoInternal
import android.content.pm.LauncherApps
import android.content.pm.LauncherUserInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.ParceledListSlice
import android.content.pm.ResolveInfo
import android.content.pm.ShortcutQueryWrapper
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.window.IDumpCallback

/**
 * The session's ILauncherApps over the real AIDL surface, published as
 * `Context.LAUNCHER_APPS_SERVICE`.
 *
 * Source note: `com.android.server.pm.LauncherAppsService.LauncherAppsImpl` for a
 * single-user device with no default launcher: launcher activities come from the
 * hosted package manager's MAIN/LAUNCHER query (direct-boot aware and unaware,
 * as `queryIntentLauncherActivities`), activity and package state come from the
 * same package manager, launches go through the system context with AOSP's
 * launch intent, and the shortcut surface keeps `ensureShortcutPermission`, which
 * no caller passes because no package holds the launcher role. Package-change
 * listeners are kept; the hosted package manager does not yet emit the
 * install/uninstall callbacks to them.
 */
class HostedLauncherAppsService(
    private val context: Context,
    private val packages: HostedPackageManagerService,
    private val permissions: HostedPermissions,
) : android.content.pm.ILauncherApps.Stub(), android.content.pm.ILauncherApps by unsupported<android.content.pm.ILauncherApps>() {
    override fun asBinder(): android.os.IBinder = this

    private val lock = Any()
    private val listeners = LinkedHashMap<IBinder, IOnAppsChangedListener>()

    private fun userId(user: UserHandle?): Int = user?.identifier ?: UserHandle.USER_SYSTEM

    private fun ensureShortcutPermission(callingPackage: String?) {
        throw SecurityException("Caller can't access shortcut information: $callingPackage is not the default launcher")
    }

    private fun launcherIntent(packageName: String?, component: ComponentName?): Intent =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).apply {
            if (component != null) setComponent(component) else if (packageName != null) setPackage(packageName)
        }

    @Suppress("UNCHECKED_CAST")
    private fun queryLauncherActivities(packageName: String?, component: ComponentName?, userId: Int): List<ResolveInfo> {
        val flags = (PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE).toLong()
        val slice = packages.queryIntentActivities(launcherIntent(packageName, component), null, flags, userId)
        return (slice?.list as? List<ResolveInfo>) ?: emptyList()
    }

    private fun internalInfo(ri: ResolveInfo, user: UserHandle) =
        LauncherActivityInfoInternal(ri.activityInfo, IncrementalStatesInfo(false, 1f, 0), user, false)

    override fun addOnAppsChangedListener(callingPackage: String?, listener: IOnAppsChangedListener?) {
        val binder = listener?.asBinder() ?: return
        synchronized(lock) { listeners[binder] = listener }
    }
    override fun removeOnAppsChangedListener(listener: IOnAppsChangedListener?) {
        synchronized(lock) { listeners.remove(listener?.asBinder() ?: return) }
    }

    override fun getLauncherActivities(callingPackage: String?, packageName: String?, user: UserHandle?): ParceledListSlice<*>? {
        val handle = user ?: UserHandle.SYSTEM
        return ParceledListSlice(queryLauncherActivities(packageName, null, userId(user)).map { internalInfo(it, handle) })
    }

    override fun resolveLauncherActivityInternal(callingPackage: String?, component: ComponentName?, user: UserHandle?): LauncherActivityInfoInternal? {
        val ri = queryLauncherActivities(null, component ?: return null, userId(user)).firstOrNull() ?: return null
        return internalInfo(ri, user ?: UserHandle.SYSTEM)
    }

    /** LauncherAppsImpl.startActivityAsUser: only an exported launcher activity, with AOSP's launch flags. */
    override fun startActivityAsUser(caller: IApplicationThread?, callingPackage: String?, callingFeatureId: String?, component: ComponentName?, sourceBounds: Rect?, opts: Bundle?, user: UserHandle?) {
        val ri = queryLauncherActivities(null, component, userId(user)).firstOrNull()
            ?: throw SecurityException("Attempt to launch activity without permission to launch it: $component")
        if (!ri.activityInfo.exported) throw SecurityException("Cannot launch non-exported components $component")
        val intent = launcherIntent(null, component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        intent.sourceBounds = sourceBounds
        context.startActivity(intent, opts)
    }

    override fun getActivityLaunchIntent(callingPackage: String?, component: ComponentName?, user: UserHandle?): PendingIntent? {
        queryLauncherActivities(null, component ?: return null, userId(user)).firstOrNull()
            ?: throw SecurityException("Attempt to launch activity without permission to launch it: $component")
        val intent = launcherIntent(null, component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivityAsUser(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, null, user ?: UserHandle.SYSTEM)
    }

    override fun showAppDetailsAsUser(caller: IApplicationThread?, callingPackage: String?, callingFeatureId: String?, component: ComponentName?, sourceBounds: Rect?, opts: Bundle?, user: UserHandle?) {
        val packageName = component?.packageName ?: return
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        intent.sourceBounds = sourceBounds
        try {
            context.startActivity(intent, opts)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "no settings activity for app details of $packageName")
        }
    }

    override fun getLauncherUserInfo(user: UserHandle?): LauncherUserInfo? =
        if (userId(user) == UserHandle.USER_SYSTEM) LauncherUserInfo.Builder(UserManager.USER_TYPE_FULL_SYSTEM, 0).build() else null
    override fun getUserProfiles(): MutableList<UserHandle?>? = mutableListOf(UserHandle.SYSTEM)
    override fun getPreInstalledSystemPackages(user: UserHandle?): MutableList<String?>? = ArrayList()
    override fun getAppMarketActivityIntent(callingPackage: String?, packageName: String?, user: UserHandle?): IntentSender? = null
    override fun getPrivateSpaceSettingsIntent(): IntentSender? = null

    override fun isPackageEnabled(callingPackage: String?, packageName: String?, user: UserHandle?): Boolean {
        return packages.getApplicationInfo(packageName ?: return false, 0L, userId(user))?.enabled == true
    }
    override fun getSuspendedPackageLauncherExtras(packageName: String?, user: UserHandle?): Bundle? = null
    override fun isActivityEnabled(callingPackage: String?, component: ComponentName?, user: UserHandle?): Boolean {
        return packages.getActivityInfo(component ?: return false, 0L, userId(user))?.isEnabled == true
    }
    override fun getApplicationInfo(callingPackage: String?, packageName: String?, flags: Int, user: UserHandle?): ApplicationInfo? {
        return packages.getApplicationInfo(packageName ?: return null, flags.toLong(), userId(user))
    }
    override fun getAppUsageLimit(callingPackage: String?, packageName: String?, user: UserHandle?): LauncherApps.AppUsageLimit? = null
    override fun shouldHideFromSuggestions(packageName: String?, user: UserHandle?): Boolean = false
    override fun getActivityOverrides(callingPackage: String?, userId: Int): MutableMap<String?, LauncherActivityInfoInternal?>? = HashMap()

    override fun hasShortcutHostPermission(callingPackage: String?): Boolean = false
    override fun getShortcuts(callingPackage: String?, query: ShortcutQueryWrapper?, user: UserHandle?): ParceledListSlice<*>? {
        ensureShortcutPermission(callingPackage)
        return null
    }
    override fun pinShortcuts(callingPackage: String?, packageName: String?, shortcutIds: MutableList<String?>?, user: UserHandle?) = ensureShortcutPermission(callingPackage)
    override fun startShortcut(callingPackage: String?, packageName: String?, featureId: String?, id: String?, sourceBounds: Rect?, startActivityOptions: Bundle?, userId: Int): Boolean {
        return startShortcut(android.os.Binder.getCallingUid(), android.os.Binder.getCallingPid(),
            callingPackage, packageName, featureId, id, sourceBounds, startActivityOptions, userId)
    }

    /**
     * LauncherAppsImpl.startShortcutInner / LocalService.startShortcut. WOC has
     * cleared Binder identity, so package and permission checks use the supplied
     * caller. The same service owns Binder and organizer calls. This session has
     * one user, no profiles, no pinned shortcuts and no default launcher role.
     */
    fun startShortcut(callerUid: Int, callerPid: Int, callingPackage: String?,
        packageName: String?, featureId: String?, id: String?, sourceBounds: Rect?,
        startActivityOptions: Bundle?, userId: Int): Boolean {
        val callerUser = UserHandle.getUserId(callerUid)
        val flags = (PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE or
            PackageManager.MATCH_UNINSTALLED_PACKAGES).toLong()
        if (packages.getPackageUid(callingPackage, flags, callerUser) != callerUid) {
            throw SecurityException("Calling package name mismatch")
        }
        if (userId != callerUser &&
            !permissions.has(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, callerUid)) {
            val users = checkNotNull(com.android.server.LocalServices.getService(
                com.android.server.pm.UserManagerInternal::class.java)) { "User authority is not started" }
            if (users.getUserInfo(callerUser)?.isProfile == true) return false
            if (!users.isProfileAccessible(callerUser, userId, "Cannot start activity", true)) return false
        }
        ensureShortcutPermission(callingPackage)
        return false
    }
    override fun getShortcutIconResId(callingPackage: String?, packageName: String?, id: String?, userId: Int): Int {
        ensureShortcutPermission(callingPackage)
        return 0
    }
    override fun getShortcutIconFd(callingPackage: String?, packageName: String?, id: String?, userId: Int): ParcelFileDescriptor? {
        ensureShortcutPermission(callingPackage)
        return null
    }
    override fun getShortcutIconUri(callingPackage: String?, packageName: String?, shortcutId: String?, userId: Int): String? {
        ensureShortcutPermission(callingPackage)
        return null
    }
    override fun getShortcutConfigActivities(callingPackage: String?, packageName: String?, user: UserHandle?): ParceledListSlice<*>? {
        ensureShortcutPermission(callingPackage)
        return null
    }
    override fun getShortcutConfigActivityIntent(callingPackage: String?, component: ComponentName?, user: UserHandle?): IntentSender? {
        ensureShortcutPermission(callingPackage)
        return null
    }
    override fun getShortcutIntent(callingPackage: String?, packageName: String?, shortcutId: String?, opts: Bundle?, user: UserHandle?): PendingIntent? {
        ensureShortcutPermission(callingPackage)
        return null
    }
    override fun registerShortcutChangeCallback(callingPackage: String?, query: ShortcutQueryWrapper?, callback: IShortcutChangeCallback?) = ensureShortcutPermission(callingPackage)
    override fun unregisterShortcutChangeCallback(callingPackage: String?, callback: IShortcutChangeCallback?) = ensureShortcutPermission(callingPackage)
    override fun cacheShortcuts(callingPackage: String?, packageName: String?, shortcutIds: MutableList<String?>?, user: UserHandle?, cacheFlags: Int) = ensureShortcutPermission(callingPackage)
    override fun uncacheShortcuts(callingPackage: String?, packageName: String?, shortcutIds: MutableList<String?>?, user: UserHandle?, cacheFlags: Int) = ensureShortcutPermission(callingPackage)

    override fun registerPackageInstallerCallback(callingPackage: String?, callback: IPackageInstallerCallback?) {}
    override fun getAllSessions(callingPackage: String?): ParceledListSlice<*>? = ParceledListSlice<PackageInstaller.SessionInfo>(ArrayList())
    override fun registerDumpCallback(cb: IDumpCallback?) {}
    override fun unRegisterDumpCallback(cb: IDumpCallback?) {}
    override fun setArchiveCompatibilityOptions(enableIconOverlay: Boolean, enableUnarchivalConfirmation: Boolean) = permissions.enforceSystem("setArchiveCompatibilityOptions")
    override fun saveViewCaptureData() {}

    private companion object {
        const val TAG = "HostedLauncherApps"
    }
}
