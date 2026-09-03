/* artzig stub of AOSP android.app.role.RoleManager: the Permission module tree (role
 * service, RoleControllerManager) is not vendored. Behaves as a device whose role
 * service holds no roles: nothing is available or held, holder changes are refused
 * through their callbacks, listeners are accepted and never invoked. */
package android.app.role;

import android.content.Intent;
import android.os.UserHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public class RoleManager {
    public static final String ROLE_ASSISTANT = "android.app.role.ASSISTANT";
    public static final String ROLE_BROWSER = "android.app.role.BROWSER";
    public static final String ROLE_DIALER = "android.app.role.DIALER";
    public static final String ROLE_SMS = "android.app.role.SMS";
    public static final String ROLE_EMERGENCY = "android.app.role.EMERGENCY";
    public static final String ROLE_HOME = "android.app.role.HOME";
    public static final String ROLE_CALL_REDIRECTION = "android.app.role.CALL_REDIRECTION";
    public static final String ROLE_CALL_SCREENING = "android.app.role.CALL_SCREENING";
    public static final String ROLE_NOTES = "android.app.role.NOTES";
    public static final String ROLE_WALLET = "android.app.role.WALLET";

    public static final int MANAGE_HOLDERS_FLAG_DONT_KILL_APP = 1;

    public Intent createRequestRoleIntent(String roleName) {
        return new Intent("android.app.role.action.REQUEST_ROLE").putExtra("android.app.role.extra.REQUEST_ROLE_NAME", roleName);
    }
    public boolean isRoleAvailable(String roleName) { return false; }
    public boolean isRoleHeld(String roleName) { return false; }
    public List<String> getRoleHolders(String roleName) { return new ArrayList<>(); }
    public List<String> getRoleHoldersAsUser(String roleName, UserHandle user) { return new ArrayList<>(); }
    public void addRoleHolderAsUser(String roleName, String packageName, int flags, UserHandle user,
            Executor executor, Consumer<Boolean> callback) {
        executor.execute(() -> callback.accept(false));
    }
    public void removeRoleHolderAsUser(String roleName, String packageName, int flags, UserHandle user,
            Executor executor, Consumer<Boolean> callback) {
        executor.execute(() -> callback.accept(false));
    }
    public void clearRoleHoldersAsUser(String roleName, int flags, UserHandle user, Executor executor,
            Consumer<Boolean> callback) {
        executor.execute(() -> callback.accept(false));
    }
    public void addOnRoleHoldersChangedListenerAsUser(Executor executor, OnRoleHoldersChangedListener listener,
            UserHandle user) {}
    public void removeOnRoleHoldersChangedListenerAsUser(OnRoleHoldersChangedListener listener, UserHandle user) {}
    public String getDefaultApplication(String roleName) { return null; }
    public String getSmsRoleHolder(int userId) { return null; }
    public String getEmergencyRoleHolder(int userId) { return null; }
    public String getBrowserRoleHolder(int userId) { return null; }
    public boolean setBrowserRoleHolder(String packageName, int userId) { return false; }
}
