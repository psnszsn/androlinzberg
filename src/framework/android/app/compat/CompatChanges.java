/* artzig shim: android.app.compat.CompatChanges — all changes enabled (modern behavior). */
package android.app.compat;
import android.os.UserHandle;
public final class CompatChanges {
    private CompatChanges() {}
    public static boolean isChangeEnabled(long changeId) { return true; }
    public static boolean isChangeEnabled(long changeId, String packageName, UserHandle user) { return true; }
    public static boolean isChangeEnabled(long changeId, int uid) { return true; }
}
