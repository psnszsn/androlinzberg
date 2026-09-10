package dev.artzig.system

import android.content.pm.UserInfo
import android.os.UserHandle
import com.android.server.pm.UserManagerInternal

/** Single hosted user; `SystemServiceManager` asks for it on user start. */
internal open class HostedUserManagerInternal(
    private val lifecycle: HostedUserLifecycle,
) : UserManagerInternal() {
    private fun users(): MutableList<UserInfo> = mutableListOf(checkNotNull(lifecycle.userInfo(UserHandle.USER_SYSTEM)))

    override fun getUserInfo(userId: Int): UserInfo? =
        users().firstOrNull { it.id == userId }

    override fun setDevicePolicyUserRestrictions(
        originatingUserId: Int,
        global: android.os.Bundle?,
        local: com.android.server.pm.RestrictionsSet?,
        isDeviceOwner: Boolean,
    ) {}
    override fun setUserRestriction(userId: Int, key: String, value: Boolean) {}
    override fun getUserRestriction(userId: Int, key: String): Boolean = false
    override fun addUserRestrictionsListener(listener: UserRestrictionsListener) {}
    override fun removeUserRestrictionsListener(listener: UserRestrictionsListener) {}
    override fun addUserLifecycleListener(listener: UserLifecycleListener) {}
    override fun removeUserLifecycleListener(listener: UserLifecycleListener) {}
    override fun setDeviceManaged(isManaged: Boolean) {}
    override fun isDeviceManaged(): Boolean = false
    override fun setUserManaged(userId: Int, isManaged: Boolean) {}
    override fun isUserManaged(userId: Int): Boolean = false
    override fun setUserIcon(userId: Int, bitmap: android.graphics.Bitmap) {}
    override fun setForceEphemeralUsers(forceEphemeralUsers: Boolean) {}
    override fun removeAllUsers() {}
    override fun onEphemeralUserStop(userId: Int) {}
    override fun createUserEvenWhenDisallowed(
        name: String?,
        userType: String,
        flags: Int,
        disallowedPackages: Array<out String>?,
        token: Any?,
    ): UserInfo = throw UnsupportedOperationException("the hosted runtime has one fixed user")
    override fun removeUserEvenWhenDisallowed(userId: Int): Boolean = false
    override fun isUserUnlockingOrUnlocked(userId: Int): Boolean = lifecycle.isUnlockingOrUnlocked(userId)
    override fun isUserUnlocked(userId: Int): Boolean = lifecycle.isUnlocked(userId)
    override fun isUserRunning(userId: Int): Boolean = lifecycle.isRunning(userId)
    override fun isUserInitialized(userId: Int): Boolean = userId == UserHandle.USER_SYSTEM
    override fun exists(userId: Int): Boolean = userId == UserHandle.USER_SYSTEM
    override fun setUserState(userId: Int, userState: Int) {}
    override fun removeUserState(userId: Int) {}
    override fun getUserIds(): IntArray = intArrayOf(UserHandle.USER_SYSTEM)
    override fun getUsers(excludeDying: Boolean): MutableList<UserInfo> = users()
    override fun getUsers(
        excludePartial: Boolean,
        excludeDying: Boolean,
        excludePreCreated: Boolean,
    ): MutableList<UserInfo> = users()
    override fun getProfileIds(userId: Int, enabledOnly: Boolean): IntArray =
        if (userId == UserHandle.USER_SYSTEM) intArrayOf(userId) else intArrayOf()
    override fun getProfileIdsExcludingHidden(userId: Int, enabledOnly: Boolean): IntArray =
        getProfileIds(userId, enabledOnly)
    override fun isProfileAccessible(
        callingUserId: Int,
        targetUserId: Int,
        debugMsg: String,
        throwSecurityException: Boolean,
    ): Boolean = callingUserId == UserHandle.USER_SYSTEM && targetUserId == UserHandle.USER_SYSTEM
    override fun getProfileParentId(userId: Int): Int = UserHandle.USER_SYSTEM
    override fun isSettingRestrictedForUser(
        setting: String,
        userId: Int,
        value: String?,
        callingUid: Int,
    ): Boolean = false
    override fun hasUserRestriction(restriction: String, userId: Int): Boolean = false
    override fun getUserInfos(): Array<UserInfo> = users().toTypedArray()
    override fun getLauncherUserInfo(userId: Int): android.content.pm.LauncherUserInfo? = null
    override fun setDefaultCrossProfileIntentFilters(parentUserId: Int, profileUserId: Int) {}
    override fun shouldIgnorePrepareStorageErrors(userId: Int): Boolean = true
    override fun getUserProperties(userId: Int): android.content.pm.UserProperties? = null
    override fun assignUserToDisplayOnStart(
        userId: Int,
        profileGroupId: Int,
        userStartMode: Int,
        displayId: Int,
    ): Int = USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE
    override fun assignUserToExtraDisplay(userId: Int, displayId: Int): Boolean =
        userId == UserHandle.USER_SYSTEM
    override fun unassignUserFromDisplayOnStop(userId: Int) {}
    override fun unassignUserFromExtraDisplay(userId: Int, displayId: Int): Boolean = false
    override fun isUserVisible(userId: Int): Boolean = lifecycle.isRunning(userId)
    override fun isUserVisible(userId: Int, displayId: Int): Boolean =
        displayId == 0 && lifecycle.isRunning(userId)
    override fun isVisibleBackgroundFullUser(userId: Int): Boolean = false
    override fun getMainDisplayAssignedToUser(userId: Int): Int = 0
    override fun getDisplaysAssignedToUser(userId: Int): IntArray? =
        if (userId == UserHandle.USER_SYSTEM) intArrayOf(0) else null
    override fun getUserAssignedToDisplay(displayId: Int): Int = UserHandle.USER_SYSTEM
    override fun addUserVisibilityListener(listener: UserVisibilityListener) {}
    override fun removeUserVisibilityListener(listener: UserVisibilityListener) {}
    override fun onSystemUserVisibilityChanged(visible: Boolean) {}
    override fun getUserTypesForStatsd(userIds: IntArray): IntArray = IntArray(userIds.size)
    override fun getMainUserId(): Int = UserHandle.USER_SYSTEM
    override fun getBootUser(waitUntilSet: Boolean): Int = UserHandle.USER_SYSTEM
    override fun getCommunalProfileId(): Int = UserHandle.USER_NULL
    override fun getSupervisingProfileId(): Int = UserHandle.USER_NULL
}
