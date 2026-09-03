/* artzig stub of AOSP android.app.role.OnRoleHoldersChangedListener: the Permission
 * module tree (role service) is not vendored. Listener shape only; no role changes
 * are ever dispatched on this host. */
package android.app.role;

import android.os.UserHandle;

public interface OnRoleHoldersChangedListener {
    void onRoleHoldersChanged(String roleName, UserHandle user);
}
