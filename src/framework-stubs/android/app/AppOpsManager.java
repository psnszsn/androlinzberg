/* artzig stub: app-ops are not enforced on this host — every op is allowed.
 * The reused ClipboardService gates clipboard access on OP_READ/WRITE_CLIPBOARD;
 * hosted access control is the uid/permission model, not app-ops. */
package android.app;
public class AppOpsManager {
    public static final int MODE_ALLOWED = 0;
    public static final int MODE_IGNORED = 1;
    public static final int MODE_ERRORED = 2;
    public static final int MODE_DEFAULT = 3;
    public static final int OP_READ_CLIPBOARD = 29;
    public static final int OP_WRITE_CLIPBOARD = 30;
    public void checkPackage(int uid, String packageName) {}
    public int noteOp(int op, int uid, String packageName, String attributionTag, String message) {
        return MODE_ALLOWED;
    }
    public int checkOp(int op, int uid, String packageName) { return MODE_ALLOWED; }
    public int unsafeCheckOpNoThrow(String op, int uid, String packageName) { return MODE_ALLOWED; }
}
