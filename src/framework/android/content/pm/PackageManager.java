/* artzig shim: android.content.pm.PackageManager — placeholder type. */
package android.content.pm;
public abstract class PackageManager {
    public static final int MATCH_UNINSTALLED_PACKAGES = 0x00002000;
    public static final int GET_SHARED_LIBRARY_FILES = 0x00000400;
    public static final int MATCH_DEFAULT_ONLY = 0x00010000;
    public static final int PERMISSION_GRANTED = 0, PERMISSION_DENIED = -1;
    public static final int MATCH_DIRECT_BOOT_AWARE = 0x00080000, MATCH_DIRECT_BOOT_UNAWARE = 0x00040000;
    public static final int GET_META_DATA = 0x00000080, MATCH_SYSTEM_ONLY = 0x00100000;
    public static final String FEATURE_AUTOFILL = "android.software.autofill";
    public static final String FEATURE_INPUT_METHODS = "android.software.input_methods";
    public static final String FEATURE_TOUCHSCREEN = "android.hardware.touchscreen";
    public static final String FEATURE_SENSOR_ACCELEROMETER = "android.hardware.sensor.accelerometer";
    public static final String FEATURE_SCREEN_PORTRAIT = "android.hardware.screen.portrait";
    public static final String FEATURE_SCREEN_LANDSCAPE = "android.hardware.screen.landscape";
    public static final String FEATURE_FINGERPRINT = "android.hardware.fingerprint";
    public static final String FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT = "android.hardware.touchscreen.multitouch.distinct";
    public static final int MATCH_DISABLED_COMPONENTS = 0x00000200;
    public static final int MATCH_DISABLED_UNTIL_USED_COMPONENTS = 0x00008000;
    public static final int GET_ACTIVITIES = 0x00000001, GET_SERVICES = 0x00000004;
    public static final class ResolveInfoFlags {
        private final long mValue;
        private ResolveInfoFlags(long value) { mValue = value; }
        public static ResolveInfoFlags of(long value) { return new ResolveInfoFlags(value); }
        public long getValue() { return mValue; }
    }
    public java.util.List<ResolveInfo> queryIntentActivities(android.content.Intent intent, ResolveInfoFlags flags) {
        return new java.util.ArrayList<>();
    }
    public java.util.List<ResolveInfo> queryIntentServices(android.content.Intent intent, ResolveInfoFlags flags) {
        return new java.util.ArrayList<>();
    }
    public java.util.List<ResolveInfo> queryIntentServicesAsUser(android.content.Intent intent,
            ResolveInfoFlags flags, int userId) {
        return new java.util.ArrayList<>();
    }
    public static final String FEATURE_WATCH = "android.hardware.type.watch";
    public static final String FEATURE_LEANBACK = "android.software.leanback";
    public static final String FEATURE_TELEVISION = "android.hardware.type.television";
    public static final String FEATURE_PICTURE_IN_PICTURE = "android.software.picture_in_picture";
    public boolean hasSystemFeature(String featureName) { return false; }
    public boolean hasSystemFeature(String featureName, int version) { return false; }
    public FeatureInfo[] getSystemAvailableFeatures() { return new FeatureInfo[0]; }
    public boolean isInstantApp() { return false; }
    public android.graphics.drawable.Drawable getDefaultActivityIcon() {
        return new android.graphics.drawable.ColorDrawable(0);
    }
    public CharSequence getText(String packageName, int resid, ApplicationInfo appInfo) { return null; }
    public ResolveInfo resolveService(android.content.Intent intent, int flags) { return null; }
    public ResolveInfo resolveActivity(android.content.Intent intent, int flags) { return null; }
    public ActivityInfo getReceiverInfo(android.content.ComponentName component, int flags)
            throws NameNotFoundException {
        throw new NameNotFoundException(String.valueOf(component));
    }
    public java.util.List<ResolveInfo> queryIntentActivitiesAsUser(android.content.Intent intent,
            int flags, int userId) {
        return new java.util.ArrayList<>();
    }
    public java.util.List<ResolveInfo> queryIntentActivityOptions(android.content.ComponentName caller,
            android.content.Intent[] specifics, android.content.Intent intent, int flags) {
        return new java.util.ArrayList<>();
    }
    public java.util.List<ResolveInfo> queryIntentActivities(android.content.Intent intent, int flags) {
        return new java.util.ArrayList<>();
    }
    public java.util.List<ResolveInfo> queryIntentServices(android.content.Intent intent, int flags) {
        return new java.util.ArrayList<>();
    }
    public ServiceInfo getServiceInfo(android.content.ComponentName component, int flags)
            throws NameNotFoundException {
        throw new NameNotFoundException(component.flattenToString());
    }
    public ActivityInfo getActivityInfo(android.content.ComponentName component, int flags)
            throws NameNotFoundException {
        ActivityInfo ai = new ActivityInfo();
        ai.packageName = component.getPackageName();
        ai.name = component.getClassName();
        ai.applicationInfo = new ApplicationInfo();
        return ai;
    }
    public ProviderInfo getProviderInfo(android.content.ComponentName component, int flags)
            throws NameNotFoundException {
        throw new NameNotFoundException(component.flattenToString());
    }
    public PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException {
        PackageInfo pi = new PackageInfo();
        pi.packageName = packageName;
        pi.versionName = "1.0";
        pi.signingInfo = new SigningInfo();
        pi.signatures = new Signature[] { new Signature(new byte[] { 4, 2 }) };
        pi.applicationInfo = new ApplicationInfo();
        return pi;
    }
    public CharSequence getApplicationLabel(ApplicationInfo info) {
        return info.packageName != null ? info.packageName : "app";
    }
    public android.graphics.drawable.Drawable getApplicationIcon(ApplicationInfo info) {
        return getDefaultActivityIcon();
    }
    public android.graphics.drawable.Drawable getApplicationIcon(String packageName) {
        return getDefaultActivityIcon();
    }
    public android.graphics.drawable.Drawable getActivityIcon(android.content.ComponentName activityName) {
        return getDefaultActivityIcon();
    }
    public int checkPermission(String permName, String packageName) { return -1; }
    public java.util.List<ResolveInfo> queryBroadcastReceivers(android.content.Intent intent, int flags) {
        return new java.util.ArrayList<>();
    }
    public java.util.List<ResolveInfo> queryContentProviders(String processName, int uid, int flags) {
        return new java.util.ArrayList<>();
    }
    public ProviderInfo resolveContentProvider(String authority, int flags) { return null; }
    public boolean hasSigningCertificate(String packageName, byte[] certificate, int type) { return false; }
    public boolean hasSigningCertificate(int uid, byte[] certificate, int type) { return false; }
    public String[] getSystemSharedLibraryNames() { return new String[0]; }
    public String getInstallerPackageName(String packageName) { return null; }
    public java.util.List<PackageInfo> getInstalledPackages(int flags) { return new java.util.ArrayList<>(); }
    public java.util.List<ApplicationInfo> getInstalledApplications(int flags) { return new java.util.ArrayList<>(); }
    public void setComponentEnabledSetting(android.content.ComponentName componentName, int newState, int flags) {}
    public int getComponentEnabledSetting(android.content.ComponentName componentName) { return 0; }
    public ApplicationInfo getApplicationInfo(String packageName, int flags)
            throws NameNotFoundException {
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = packageName;
        info.name = packageName;
        info.enabled = true;
        info.metaData = new android.os.Bundle();
        return info;
    }
    public android.content.res.Resources getResourcesForApplication(ApplicationInfo app)
            throws NameNotFoundException {
        throw new NameNotFoundException(String.valueOf(app));
    }
    public android.content.res.Resources getResourcesForApplication(String packageName)
            throws NameNotFoundException {
        throw new NameNotFoundException(packageName);
    }
    public static class NameNotFoundException extends android.util.AndroidException {
        public NameNotFoundException() {}
        public NameNotFoundException(String name) { super(name); }
    }
}
