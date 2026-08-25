/* artzig stub. */
package android.os;
public class PermissionEnforcer {
    public static PermissionEnforcer fromContext(android.content.Context context) { return new PermissionEnforcer(); }
    public PermissionEnforcer() {}
    public void enforcePermission(String permission, android.content.AttributionSource source) {}
    public void enforcePermission(String permission, int pid, int uid) {}
    public void enforcePermissionAllOf(String[] permissions, int pid, int uid) {}
    public void enforcePermissionAllOf(String[] permissions, android.content.AttributionSource source) {}
    public void enforcePermissionAnyOf(String[] permissions, android.content.AttributionSource source) {}
}
