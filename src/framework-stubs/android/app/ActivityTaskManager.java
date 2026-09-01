/* artzig stub (auto-generated placeholder; members added on demand). */
package android.app;
public class ActivityTaskManager {
    public static final int INVALID_TASK_ID = -1;
    /** No activity task manager service on this host (StrictMode null-checks it). */
    public static IActivityTaskManager getService() { return null; }
    public static final int INVALID_STACK_ID = -1;
    public static int getDefaultAppRecentsLimitStatic() { return 16; }
    public static boolean supportsMultiWindow(android.content.Context context) { return false; }
    public static boolean supportsSplitScreenMultiWindow(android.content.Context context) { return false; }
}
