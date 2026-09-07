/* artzig stub: single hosted user 0. The vendored ComponentResolver checks
 * exists() per query and UserNeedsBadgingCache asks for the UserInfo; the
 * session's real user surface is dev.artzig.system.HostedUserManagerService
 * on android.os.IUserManager. */
package com.android.server.pm;

import android.content.pm.UserInfo;

public final class UserManagerService {
    static final boolean DBG = false;
    private static final UserManagerService INSTANCE = new UserManagerService();
    // The real UserInfo derives a user type from the flags and refuses to guess for the
    // system user, so the type is spelled out as UserManagerService.createUserInternal does.
    private static final UserInfo USER_SYSTEM = new UserInfo(0, "hosted", null,
            UserInfo.FLAG_SYSTEM | UserInfo.FLAG_FULL | UserInfo.FLAG_PRIMARY | UserInfo.FLAG_ADMIN | UserInfo.FLAG_MAIN,
            android.os.UserManager.USER_TYPE_FULL_SYSTEM);

    private UserManagerService() {}

    public static UserManagerService getInstance() { return INSTANCE; }

    public boolean exists(int userId) { return userId == 0; }

    // UserManagerService.getUserIdsIncludingPreCreated: this host has no pre-created users.
    public int[] getUserIdsIncludingPreCreated() { return getUserIds(); }

    public int[] getUserIds() { return new int[] {0}; }

    public UserInfo getUserInfo(int userId) { return userId == 0 ? USER_SYSTEM : null; }
}
