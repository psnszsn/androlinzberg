package dev.artzig.system

import android.content.pm.UserInfo
import android.os.IUserManager
import android.os.UserHandle
import android.os.UserManager

/**
 * The session's user manager over the real `android.os.IUserManager` surface,
 * published as `Context.USER_SERVICE` (source note:
 * `com.android.server.pm.UserManagerService`, reduced to the single
 * system user 0 — the AOSP class is inseparable from PackageManagerService and
 * per-user storage). Runtime state comes from the same stock UserState as the
 * local service and SystemServiceManager boot callbacks.
 */
internal class HostedUserManagerService(private val lifecycle: HostedUserLifecycle) : IUserManager.Stub() {
    private fun user(): UserInfo = UserInfo(
        UserHandle.USER_SYSTEM,
        "host",
        null,
        UserInfo.FLAG_PRIMARY or UserInfo.FLAG_ADMIN or UserInfo.FLAG_FULL or
            UserInfo.FLAG_SYSTEM or UserInfo.FLAG_MAIN,
        UserManager.USER_TYPE_FULL_SYSTEM,
    )

    override fun getCredentialOwnerProfile(userId: Int): Int = 0
    override fun getProfileParentId(userId: Int): Int = 0
    override fun createUserWithThrow(name: String?, userType: String?, flags: Int): android.content.pm.UserInfo? = null
    override fun preCreateUserWithThrow(userType: String?): android.content.pm.UserInfo? = null
    override fun createProfileForUserWithThrow(name: String?, userType: String?, flags: Int, userId: Int, disallowedPackages: Array<String?>?): android.content.pm.UserInfo? = null
    override fun createRestrictedProfileWithThrow(name: String?, parentUserHandle: Int): android.content.pm.UserInfo? = null
    override fun getPreInstallableSystemPackages(userType: String?): Array<String?>? = null
    override fun setUserEnabled(userId: Int) {}
    override fun setUserAdmin(userId: Int) {}
    override fun revokeUserAdmin(userId: Int) {}
    override fun evictCredentialEncryptionKey(userId: Int) {}
    override fun removeUser(userId: Int): Boolean = false
    override fun removeUserEvenWhenDisallowed(userId: Int): Boolean = false
    override fun setUserName(userId: Int, name: String?) {}
    override fun setUserIcon(userId: Int, icon: android.graphics.Bitmap?) {}
    override fun getUserIcon(userId: Int): android.os.ParcelFileDescriptor? = null
    override fun getPrimaryUser(): android.content.pm.UserInfo? = user()
    override fun getMainUserId(): Int = 0
    override fun getCommunalProfileId(): Int = 0
    override fun getPreviousFullUserToEnterForeground(): Int = 0
    override fun getUsers(excludePartial: Boolean, excludeDying: Boolean, excludePreCreated: Boolean): MutableList<android.content.pm.UserInfo?>? = mutableListOf(user())
    // Pinned getProfilesLU/getProfileIdsLU include the queried user, but an
    // unknown user has no profile group. The single hosted user is enabled.
    override fun getProfiles(userId: Int, enabledOnly: Boolean): MutableList<android.content.pm.UserInfo?> =
        if (userId == UserHandle.USER_SYSTEM) mutableListOf(user()) else mutableListOf()
    override fun getProfileIds(userId: Int, enabledOnly: Boolean): IntArray =
        if (userId == UserHandle.USER_SYSTEM) intArrayOf(UserHandle.USER_SYSTEM) else intArrayOf()
    override fun isUserTypeEnabled(userType: String?): Boolean = false
    override fun canAddMoreUsersOfType(userType: String?): Boolean = false
    override fun getRemainingCreatableUserCount(userType: String?): Int = 0
    override fun getRemainingCreatableProfileCount(userType: String?, userId: Int): Int = 0
    override fun canAddMoreProfilesToUser(userType: String?, userId: Int, allowedToRemoveOne: Boolean): Boolean = false
    override fun canAddMoreManagedProfiles(userId: Int, allowedToRemoveOne: Boolean): Boolean = false
    override fun getProfileParent(userId: Int): android.content.pm.UserInfo? = null
    override fun isSameProfileGroup(userId: Int, otherUserHandle: Int): Boolean = userId == otherUserHandle
    override fun isHeadlessSystemUserMode(): Boolean = false
    override fun isUserOfType(userId: Int, userType: String?): Boolean = false
    override fun getUserInfo(userId: Int): android.content.pm.UserInfo? =
        if (userId == UserHandle.USER_SYSTEM) user() else null
    override fun getUserPropertiesCopy(userId: Int): android.content.pm.UserProperties? = null
    override fun getUserAccount(userId: Int): String? = null
    override fun setUserAccount(userId: Int, accountName: String?) {}
    override fun getUserCreationTime(userId: Int): Long = 0
    override fun getUserSwitchability(userId: Int): Int = 0
    override fun isUserSwitcherEnabled(showEvenIfNotActionable: Boolean, mUserId: Int): Boolean = false
    // android 16: the hosted session runs as the (single) system user, which
    // AOSP itself cannot log out.
    override fun getUserLogoutability(userId: Int): Int =
        1 // UserManager.LOGOUTABILITY_STATUS_CANNOT_LOGOUT_SYSTEM_USER (class not in the curated set)
    override fun isRestricted(userId: Int): Boolean = false
    override fun canHaveRestrictedProfile(userId: Int): Boolean = false
    override fun canAddPrivateProfile(userId: Int): Boolean = false
    override fun getUserSerialNumber(userId: Int): Int = 0
    override fun getUserHandle(userSerialNumber: Int): Int = 0
    override fun getUserRestrictionSource(restrictionKey: String?, userId: Int): Int = 0
    override fun getUserRestrictionSources(restrictionKey: String?, userId: Int): MutableList<android.os.UserManager.EnforcingUser?>? = null
    override fun getUserRestrictions(userId: Int): android.os.Bundle? = android.os.Bundle()
    override fun hasBaseUserRestriction(restrictionKey: String?, userId: Int): Boolean = false
    override fun hasUserRestriction(restrictionKey: String?, userId: Int): Boolean = false
    override fun hasUserRestrictionOnAnyUser(restrictionKey: String?): Boolean = false
    override fun isSettingRestrictedForUser(setting: String?, userId: Int, value: String?, callingUid: Int): Boolean = false
    override fun addUserRestrictionsListener(listener: android.os.IUserRestrictionsListener?) {}
    override fun setUserRestriction(key: String?, value: Boolean, userId: Int) {}
    override fun setApplicationRestrictions(packageName: String?, restrictions: android.os.Bundle?, userId: Int) {}
    override fun getApplicationRestrictions(packageName: String?): android.os.Bundle? = null
    override fun getApplicationRestrictionsForUser(packageName: String?, userId: Int): android.os.Bundle? = null
    override fun setDefaultGuestRestrictions(restrictions: android.os.Bundle?) {}
    override fun getDefaultGuestRestrictions(): android.os.Bundle? = null
    override fun removeUserWhenPossible(userId: Int, overrideDevicePolicy: Boolean): Int = 0
    override fun markGuestForDeletion(userId: Int): Boolean = false
    override fun getGuestUsers(): MutableList<android.content.pm.UserInfo?>? = null
    override fun isQuietModeEnabled(userId: Int): Boolean = false
    override fun createUserWithAttributes(userName: String?, userType: String?, flags: Int, userIcon: android.graphics.Bitmap?, accountName: String?, accountType: String?, accountOptions: android.os.PersistableBundle?): android.os.UserHandle? = null
    override fun setSeedAccountData(userId: Int, accountName: String?, accountType: String?, accountOptions: android.os.PersistableBundle?, persist: Boolean) {}
    override fun getSeedAccountName(userId: Int): String? = null
    override fun getSeedAccountType(userId: Int): String? = null
    override fun getSeedAccountOptions(userId: Int): android.os.PersistableBundle? = null
    override fun clearSeedAccountData(userId: Int) {}
    override fun someUserHasSeedAccount(accountName: String?, accountType: String?): Boolean = false
    override fun someUserHasAccount(accountName: String?, accountType: String?): Boolean = false
    // Pinned getProfileType distinguishes a full user (empty) from an absent one (null).
    override fun getProfileType(userId: Int): String? = if (userId == UserHandle.USER_SYSTEM) "" else null
    override fun isDemoUser(userId: Int): Boolean = false
    override fun isAdminUser(userId: Int): Boolean = userId == UserHandle.USER_SYSTEM
    override fun isPreCreated(userId: Int): Boolean = false
    override fun createProfileForUserEvenWhenDisallowedWithThrow(name: String?, userType: String?, flags: Int, userId: Int, disallowedPackages: Array<String?>?): android.content.pm.UserInfo? = null
    override fun isUserUnlockingOrUnlocked(userId: Int): Boolean = lifecycle.isUnlockingOrUnlocked(userId)
    override fun getUserIconBadgeResId(userId: Int): Int = 0
    override fun getUserBadgeResId(userId: Int): Int = 0
    override fun getUserBadgeNoBackgroundResId(userId: Int): Int = 0
    override fun getUserBadgeLabelResId(userId: Int): Int = 0
    override fun getUserBadgeColorResId(userId: Int): Int = 0
    override fun getUserBadgeDarkColorResId(userId: Int): Int = 0
    override fun getUserStatusBarIconResId(userId: Int): Int = 0
    override fun hasBadge(userId: Int): Boolean = false
    override fun getProfileLabelResId(userId: Int): Int = 0
    override fun getProfileAccessibilityLabelResId(userId: Int): Int = 0
    override fun isUserUnlocked(userId: Int): Boolean = lifecycle.isUnlocked(userId)
    override fun isUserRunning(userId: Int): Boolean = lifecycle.isRunning(userId)
    override fun isUserForeground(userId: Int): Boolean = lifecycle.isRunning(userId)
    override fun isUserVisible(userId: Int): Boolean = lifecycle.isRunning(userId)
    override fun getVisibleUsers(): IntArray = if (lifecycle.isRunning(0)) intArrayOf(0) else intArrayOf()
    override fun getMainDisplayIdAssignedToUser(userId: Int): Int = 0
    override fun isForegroundUserAdmin(): Boolean = lifecycle.isRunning(0)
    override fun isUserNameSet(userId: Int): Boolean = userId == UserHandle.USER_SYSTEM
    override fun hasRestrictedProfiles(userId: Int): Boolean = false
    override fun requestQuietModeEnabled(callingPackage: String?, enableQuietMode: Boolean, userId: Int, target: android.content.IntentSender?, flags: Int): Boolean = false
    override fun getUserName(): String? = "host"
    override fun getUserStartRealtime(): Long = 0
    override fun getUserUnlockRealtime(): Long = 0
    override fun setUserEphemeral(userId: Int, enableEphemeral: Boolean): Boolean = false
    override fun setBootUser(userId: Int) {}
    override fun getBootUser(): Int = 0
    override fun getProfileIdsExcludingHidden(userId: Int, enabledOnly: Boolean): IntArray = getProfileIds(userId, enabledOnly)
}
