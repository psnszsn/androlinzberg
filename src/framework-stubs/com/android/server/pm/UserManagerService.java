/* artzig stub: single hosted user 0. The vendored ComponentResolver checks
 * exists() per query and UserNeedsBadgingCache asks for the UserInfo; the
 * session's real user surface is dev.artzig.system.HostedUserManagerService
 * on android.os.IUserManager. */
package com.android.server.pm;

import android.content.pm.UserInfo;

public final class UserManagerService {
    private static final UserManagerService INSTANCE = new UserManagerService();
    private static final UserInfo USER_SYSTEM =
            new UserInfo(0, "hosted", UserInfo.FLAG_SYSTEM | UserInfo.FLAG_FULL | UserInfo.FLAG_PRIMARY);

    private UserManagerService() {}

    public static UserManagerService getInstance() { return INSTANCE; }

    public boolean exists(int userId) { return userId == 0; }

    public int[] getUserIds() { return new int[] {0}; }

    public UserInfo getUserInfo(int userId) { return userId == 0 ? USER_SYSTEM : null; }
}
