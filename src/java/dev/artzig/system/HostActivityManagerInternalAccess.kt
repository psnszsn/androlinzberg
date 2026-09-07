package dev.artzig.system

import android.app.ActivityManager
import android.app.ActivityManagerInternal
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ActivityManagerService.LocalService and UserController.handleIncomingUser.
 * AMS's zygote/process graph is absent; the generated stock local-service adapter
 * calls this session authority. Unserved operations remain structured failures.
 */
class HostActivityManagerInternalAccess internal constructor(
    private val activities: HostedActivityManagerService,
    private val packages: HostedPackageManagerService,
    private val users: HostedUserLifecycle,
) {
    private val ready = AtomicBoolean(false)

    fun registerProcessObserver(observer: android.app.IProcessObserver) =
        activities.registerProcessObserver(observer)
    fun unregisterProcessObserver(observer: android.app.IProcessObserver) =
        activities.unregisterProcessObserver(observer)

    fun setBooting(value: Boolean) { activities.booting = value }
    fun isBooting(): Boolean = activities.booting
    fun setBooted(value: Boolean) { activities.booted = value }
    fun isBooted(): Boolean = activities.booted
    fun finishBooting() = activities.finishBooting()
    fun setSystemReady() { ready.set(true) }
    fun isSystemReady(): Boolean = ready.get()
    fun isPendingTopUid(uid: Int): Boolean = false
    fun isTempAllowlistedForFgsWhileInUse(uid: Int): Boolean = false
    fun getInstrumentationSourceUid(uid: Int): Int = Process.INVALID_UID
    // ActivityManagerConstants: read the same hosted Settings/DeviceConfig keys.
    fun isActivityStartsLoggingEnabled(): Boolean = android.provider.Settings.Global.getInt(
        (android.app.ActivityThread.currentActivityThread().systemContext as android.content.Context).contentResolver,
        android.provider.Settings.Global.ACTIVITY_STARTS_LOGGING_ENABLED, 1) == 1
    fun isBackgroundActivityStartsEnabled(): Boolean = android.provider.DeviceConfig.getBoolean(
        android.provider.DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
        "default_background_activity_starts_enabled", false)
    fun broadcastGlobalConfigurationChanged(changes: Int, initLocale: Boolean) =
        activities.broadcastGlobalConfigurationChanged(changes, initLocale, ready.get())
    fun killAllBackgroundProcessesExcept(minTargetSdk: Int, maxProcState: Int) =
        activities.killAllBackgroundProcessesExcept(minTargetSdk, maxProcState)
    fun updateOomLevelsForDisplay(displayId: Int) {
        // AMS delegates this advisory notification to ProcessList.applyDisplaySize:
        // it tunes Android LMKD targets, cached-process restore thresholds and init's
        // global kernel watermark property. This host has no LMKD/cached-process
        // reclamation controller; desktop Linux retains its existing memory policy.
        // No hosted process or allocator consumes those Android-only thresholds.
    }
    fun getCurrentUserId(): Int = UserHandle.USER_SYSTEM
    fun getCurrentAndTargetUserIds(): android.util.Pair<Int, Int> =
        android.util.Pair(UserHandle.USER_SYSTEM, UserHandle.USER_NULL)
    fun getCurrentProfileIds(): IntArray = intArrayOf(UserHandle.USER_SYSTEM)
    fun getStartedUserIds(): IntArray = intArrayOf(UserHandle.USER_SYSTEM)
    fun getCurrentUser(): android.content.pm.UserInfo? = activities.getCurrentUser()
    fun isCurrentProfile(userId: Int): Boolean = userId == UserHandle.USER_SYSTEM
    fun hasStartedUserState(userId: Int): Boolean = userId == UserHandle.USER_SYSTEM
    fun isUserRunning(userId: Int, flags: Int): Boolean = users.isRunning(userId, flags)
    // UserController.shouldConfirmCredentials returns false for absent user state
    // or a user without credentials shared with a parent. The host has only its
    // full system user, and no credential-sharing profiles.
    fun shouldConfirmCredentials(userId: Int): Boolean = false
    fun addCreatorToken(intent: Intent?, creatorPackage: String?) =
        activities.addCreatorToken(intent, creatorPackage)
    fun enforceCallingPermission(permission: String, operation: String) =
        HostedPermissions(packages).enforce(permission, operation)
    fun checkContentProviderUriPermission(uri: Uri, userId: Int, callingUid: Int, modeFlags: Int): Int =
        activities.checkProviderUriPermissionFromHost(uri, userId, callingUid, modeFlags)

    fun getUidProcessState(uid: Int): Int = activities.uidProcessState(uid)

    /** AMS.getActivityInfoForUser: preserve the same-user identity, otherwise
     * copy the package values and let stock ApplicationInfo remap the user paths. */
    fun getActivityInfoForUser(info: android.content.pm.ActivityInfo?, userId: Int): android.content.pm.ActivityInfo? {
        if (info == null || (userId < 1 && info.applicationInfo.uid < UserHandle.PER_USER_RANGE)) return info
        return android.content.pm.ActivityInfo(info).apply {
            applicationInfo = android.content.pm.ApplicationInfo(info.applicationInfo).apply { initForUser(userId) }
        }
    }

    // User resolution does not create users. Services that need an existing user
    // consult UserManagerInternal, whose lifecycle owns the configured system user.
    fun handleIncomingUser(callingPid: Int, callingUid: Int, userId: Int, allowAll: Boolean,
        allowMode: Int, name: String?, callerPackage: String?): Int {
        val callingUser = UserHandle.getUserId(callingUid)
        if (callingUser == userId) return userId
        var target = when (userId) {
            UserHandle.USER_CURRENT, UserHandle.USER_CURRENT_OR_SELF -> UserHandle.USER_SYSTEM
            else -> userId
        }
        if (callingUid != Process.SYSTEM_UID && callingUid != 0) {
            fun granted(permission: String): Boolean = HostActivityManagerAccess.checkComponentPermission(
                permission, callingPid, callingUid, -1, true) == PackageManager.PERMISSION_GRANTED
            val sameProfile = callingUser == target // No additional hosted profiles.
            val full = granted(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
            val allowed = full || when {
                allowMode == ActivityManagerInternal.ALLOW_FULL_ONLY -> false
                allowMode == ActivityManagerInternal.ALLOW_PROFILES_OR_NON_FULL && sameProfile &&
                    granted(android.Manifest.permission.INTERACT_ACROSS_PROFILES) -> true
                !granted(android.Manifest.permission.INTERACT_ACROSS_USERS) -> false
                allowMode == ActivityManagerInternal.ALLOW_NON_FULL ||
                    allowMode == ActivityManagerInternal.ALLOW_PROFILES_OR_NON_FULL -> true
                allowMode == ActivityManagerInternal.ALLOW_NON_FULL_IN_PROFILE -> sameProfile
                else -> throw IllegalArgumentException("Unknown mode: $allowMode")
            }
            if (!allowed) {
                if (userId == UserHandle.USER_CURRENT_OR_SELF) target = callingUser
                else throw SecurityException("Permission Denial: $name from $callerPackage asks to run as user " +
                    "$userId but is calling from uid $callingUid; requires cross-user permission")
            }
        }
        if (!allowAll) ensureNotSpecialUser(target)
        return target
    }

    fun ensureNotSpecialUser(userId: Int) {
        require(userId >= 0) { "Call does not support special user #$userId" }
    }
}
