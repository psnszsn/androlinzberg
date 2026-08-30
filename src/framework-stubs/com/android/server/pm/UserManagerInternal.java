/* artzig stub: the LocalServices interface SystemServiceManager consults for user info.
 * The hosted system process serves one user (USER_SYSTEM). */
package com.android.server.pm;
import android.content.pm.UserInfo;
public abstract class UserManagerInternal {
    public abstract UserInfo getUserInfo(int userId);
}
