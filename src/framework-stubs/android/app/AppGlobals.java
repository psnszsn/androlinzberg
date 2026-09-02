/* artzig stub. */
package android.app;
public class AppGlobals {
    public static int getIntCoreSetting(String key, int defaultValue) { return defaultValue; }
    public static float getFloatCoreSetting(String key, float defaultValue) { return defaultValue; }
    public static String getInitialPackage() { return null; }
    /** The process's Application (AOSP: ActivityThread.currentApplication). */
    public static Application getInitialApplication() { return ActivityThread.currentApplication(); }
    /** AOSP: the session's IPackageManager (ActivityThread.getPackageManager). */
    public static android.content.pm.IPackageManager getPackageManager() { return ActivityThread.getPackageManager(); }
}
