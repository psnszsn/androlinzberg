package dev.artzig.system

import android.Manifest
import android.app.ActivityManagerInternal
import android.os.Process
import android.os.UserHandle

/**
 * The hosted operations behind stock ActivityManagerInternal. The generated Java
 * adapter supplies its exhaustive signatures, while this Kotlin module owns behavior.
 * Source: AMS.LocalService and UserController.handleIncomingUser. Server WM/AM state
 * is not available; this session has one current user and no cross-user profile groups.
 */
class HostedActivityManagerInternalService(
    private val activities: HostedActivityManagerService,
    private val permissions: HostedPermissions,
) {
    @Volatile var systemReady: Boolean = false
        private set

    fun enforceCallingPermission(permission: String, operation: String?) {
        permissions.enforce(permission, operation ?: "ActivityManager")
    }
    fun checkContentProviderUriPermission(uri: android.net.Uri, userId: Int, callingUid: Int, modeFlags: Int): Int =
        activities.checkProviderUriPermission(uri, userId, callingUid, modeFlags)

    fun markSystemReady() { systemReady = true }
    fun getUidProcessState(uid: Int): Int = activities.uidProcessState(uid)

    fun handleIncomingUser(
        callingPid: Int, callingUid: Int, userId: Int, allowAll: Boolean,
        allowMode: Int, name: String?, callerPackage: String?,
    ): Int {
        val callingUser = UserHandle.getUserId(callingUid)
        if (callingUser == userId) return userId
        var target = if (userId == UserHandle.USER_CURRENT || userId == UserHandle.USER_CURRENT_OR_SELF)
            UserHandle.USER_SYSTEM else userId
        if (callingUid != Process.ROOT_UID && callingUid != Process.SYSTEM_UID) {
            val sameProfile = callingUser == target
            val allowed = when {
                permissions.has(Manifest.permission.INTERACT_ACROSS_USERS_FULL, callingUid) -> true
                allowMode == ActivityManagerInternal.ALLOW_FULL_ONLY -> false
                allowMode == ActivityManagerInternal.ALLOW_PROFILES_OR_NON_FULL && sameProfile &&
                    callerPackage != null && permissions.isCallerPackage(callerPackage, callingUid) &&
                    permissions.has(Manifest.permission.INTERACT_ACROSS_PROFILES, callingUid) -> true
                !permissions.has(Manifest.permission.INTERACT_ACROSS_USERS, callingUid) -> false
                allowMode == ActivityManagerInternal.ALLOW_NON_FULL ||
                    allowMode == ActivityManagerInternal.ALLOW_PROFILES_OR_NON_FULL -> true
                allowMode == ActivityManagerInternal.ALLOW_NON_FULL_IN_PROFILE -> sameProfile
                else -> throw IllegalArgumentException("Unknown mode: $allowMode")
            }
            if (!allowed) {
                if (userId == UserHandle.USER_CURRENT_OR_SELF) target = callingUser
                else throw SecurityException("Permission Denial: $name from $callerPackage asks to run as user $userId but is calling from uid $callingUid")
            }
        }
        require(allowAll || target >= 0) { "Call does not support special user #$target" }
        return target
    }
}
