/* artzig stub: no permission controller app in a hosted session; queries
 * answer empty. */
package android.permission;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
public class PermissionControllerManager {
    public void getPlatformPermissionsForGroup(String permissionGroupName, Executor executor,
            Consumer<List<String>> callback) {
        executor.execute(() -> callback.accept(Collections.emptyList()));
    }
    public void getGroupOfPlatformPermission(String permissionName, Executor executor,
            Consumer<String> callback) {
        executor.execute(() -> callback.accept(null));
    }
    /** AOSP: the permission controller revokes on kill; no controller on this host. */
    public void revokeSelfPermissionsOnKill(String packageName, java.util.List<String> permissions) {}
}
