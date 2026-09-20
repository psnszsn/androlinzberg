package com.android.server.pm

import android.app.ActivityManager
import android.os.Binder
import android.os.UserHandle
import android.os.UserManager

/**
 * The one entry point of AOSP `UserManagerService` that the stock accessibility
 * service calls statically. `HostedUserManagerService` is the hosted
 * IUserManager; the real class cannot be compiled because its closure reaches
 * the package manager, storage and lock-settings services.
 *
 * Follows `UserManagerService.enforceCurrentUserIfVisibleBackgroundEnabled`:
 * without visible background users it permits every caller; with them, only the
 * system user and the current user. The host has no profile groups.
 */
object UserManagerService {
    @JvmStatic
    fun enforceCurrentUserIfVisibleBackgroundEnabled() {
        if (!UserManager.isVisibleBackgroundUsersEnabled()) return
        val callingUserId = UserHandle.getCallingUserId()
        val identity = Binder.clearCallingIdentity()
        try {
            val currentUserId = ActivityManager.getCurrentUser()
            if (callingUserId != UserHandle.USER_SYSTEM && callingUserId != currentUserId) {
                throw SecurityException(
                    "Invalid calling user on devices that enable visible background users. " +
                        "callingUserId=$callingUserId currentUserId=$currentUserId")
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }
}
