/* artzig shim: android.content.pm.PackageManager — placeholder type. */
package android.content.pm;
public abstract class PackageManager {
    private static String sHostedPackageName;
    private static int sHostedVersionCode;
    private static String sHostedVersionName;
    private static String sHostedSourceDir;
    private static String sHostedDataDir;
    private static int sHostedApplicationTheme;
    private static String sHostedLauncherActivity;
    private static java.util.Map<String, ActivityInfo> sHostedActivities =
            new java.util.HashMap<>();
    private static java.util.List<ProviderInfo> sHostedProviders = new java.util.ArrayList<>();
    private static java.util.List<ServiceInfo> sHostedServices = new java.util.ArrayList<>();
    private static java.util.Map<String, java.util.Set<String>> sHostedServiceActions =
            new java.util.HashMap<>();
    private static java.util.Set<String> sHostedRequestedPermissions =
            java.util.Collections.emptySet();
    private static java.util.Set<String> sHostedGrantedPermissions =
            java.util.Collections.emptySet();
    public static void configureHostedPackage(String packageName, int versionCode,
            String versionName, String sourceDir, String dataDir) {
        sHostedPackageName = packageName;
        sHostedVersionCode = versionCode;
        sHostedVersionName = versionName;
        sHostedSourceDir = sourceDir;
        sHostedDataDir = dataDir;
        sHostedApplicationTheme = 0;
        sHostedRequestedPermissions = java.util.Collections.emptySet();
        sHostedGrantedPermissions = java.util.Collections.emptySet();
    }
    public static void configureHostedLauncher(String activityName) {
        sHostedLauncherActivity = activityName;
    }
    public static void configureHostedPackage(HostedPackage hostedPackage,
            String sourceDir, String dataDir) {
        configureHostedPackage(hostedPackage, sourceDir, dataDir,
                java.util.Collections.emptyMap());
    }
    /**
     * Hosted adaptation of PackageManagerService's install-time permission grant.
     * The full service cannot run without package installation state and the
     * permission controller, so the host grants only requested permissions whose
     * declaring manifest marks them with AOSP's normal protection base.
     */
    public static void configureHostedPackage(HostedPackage hostedPackage,
            String sourceDir, String dataDir,
            java.util.Map<String, Integer> platformPermissionProtectionLevels) {
        configureHostedPackage(hostedPackage.getPackageName(), hostedPackage.getVersionCode(),
                hostedPackage.getVersionName(), sourceDir, dataDir);
        sHostedApplicationTheme = hostedPackage.getApplicationTheme();
        configureHostedLauncher(hostedPackage.getLauncherActivityName());
        configureHostedProviders(hostedPackage.getProviders());
        configureHostedServices(hostedPackage.getServices(), hostedPackage.getServiceActions());
        java.util.HashMap<String, ActivityInfo> activityCopy = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, ActivityInfo> entry
                : hostedPackage.getActivities().entrySet()) {
            activityCopy.put(entry.getKey(), new ActivityInfo(entry.getValue()));
        }
        sHostedActivities = activityCopy;
        configureHostedPermissions(hostedPackage.getRequestedPermissions(),
                hostedPackage.getDeclaredPermissionProtectionLevels(),
                platformPermissionProtectionLevels);
    }
    public static void configureHostedPermissions(java.util.Set<String> requestedPermissions,
            java.util.Map<String, Integer> packagePermissionProtectionLevels,
            java.util.Map<String, Integer> platformPermissionProtectionLevels) {
        java.util.LinkedHashSet<String> requested = new java.util.LinkedHashSet<>();
        if (requestedPermissions != null) requested.addAll(requestedPermissions);
        java.util.HashMap<String, Integer> definitions = new java.util.HashMap<>();
        if (platformPermissionProtectionLevels != null) {
            definitions.putAll(platformPermissionProtectionLevels);
        }
        if (packagePermissionProtectionLevels != null) {
            definitions.putAll(packagePermissionProtectionLevels);
        }
        java.util.LinkedHashSet<String> granted = new java.util.LinkedHashSet<>();
        for (String permission : requested) {
            Integer protectionLevel = definitions.get(permission);
            if (protectionLevel != null && (protectionLevel.intValue() & 0x0f) == 0) {
                granted.add(permission);
            }
        }
        sHostedRequestedPermissions = java.util.Collections.unmodifiableSet(requested);
        sHostedGrantedPermissions = java.util.Collections.unmodifiableSet(granted);
    }
    public static void configureHostedProviders(java.util.List<ProviderInfo> providers) {
        java.util.ArrayList<ProviderInfo> copy = new java.util.ArrayList<>();
        if (providers != null) {
            for (ProviderInfo provider : providers) copy.add(new ProviderInfo(provider));
        }
        sHostedProviders = copy;
    }
    public static void configureHostedServices(java.util.List<ServiceInfo> services,
            java.util.Map<String, java.util.Set<String>> actionsByClassName) {
        java.util.ArrayList<ServiceInfo> serviceCopy = new java.util.ArrayList<>();
        if (services != null) {
            for (ServiceInfo service : services) serviceCopy.add(new ServiceInfo(service));
        }
        java.util.HashMap<String, java.util.Set<String>> actionCopy = new java.util.HashMap<>();
        if (actionsByClassName != null) {
            for (java.util.Map.Entry<String, java.util.Set<String>> entry
                    : actionsByClassName.entrySet()) {
                actionCopy.put(entry.getKey(), new java.util.HashSet<>(entry.getValue()));
            }
        }
        sHostedServices = serviceCopy;
        sHostedServiceActions = actionCopy;
    }
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
    public static final int GET_PERMISSIONS = 0x00001000;
    public static final class ResolveInfoFlags {
        private final long mValue;
        private ResolveInfoFlags(long value) { mValue = value; }
        public static ResolveInfoFlags of(long value) { return new ResolveInfoFlags(value); }
        public long getValue() { return mValue; }
    }
    public static final class PackageInfoFlags {
        private final long mValue;
        private PackageInfoFlags(long value) { mValue = value; }
        public static PackageInfoFlags of(long value) { return new PackageInfoFlags(value); }
        public long getValue() { return mValue; }
    }
    public java.util.List<ResolveInfo> queryIntentActivities(android.content.Intent intent, ResolveInfoFlags flags) {
        return queryIntentActivities(intent, (int) flags.getValue());
    }
    public java.util.List<ResolveInfo> queryIntentServices(android.content.Intent intent, ResolveInfoFlags flags) {
        return queryIntentServices(intent, (int) flags.getValue());
    }
    public java.util.List<ResolveInfo> queryIntentServicesAsUser(android.content.Intent intent,
            ResolveInfoFlags flags, int userId) {
        return queryIntentServices(intent, (int) flags.getValue());
    }
    public static final String FEATURE_WATCH = "android.hardware.type.watch";
    public static final String FEATURE_LEANBACK = "android.software.leanback";
    public static final String FEATURE_TELEVISION = "android.hardware.type.television";
    public static final String FEATURE_PICTURE_IN_PICTURE = "android.software.picture_in_picture";
    public static final String FEATURE_EXPANDED_PICTURE_IN_PICTURE =
            "android.software.expanded_picture_in_picture";
    public boolean hasSystemFeature(String featureName) { return false; }
    public boolean hasSystemFeature(String featureName, int version) { return false; }
    public FeatureInfo[] getSystemAvailableFeatures() { return new FeatureInfo[0]; }
    public boolean isInstantApp() { return false; }
    public android.graphics.drawable.Drawable getDefaultActivityIcon() {
        return new android.graphics.drawable.ColorDrawable(0);
    }
    public CharSequence getText(String packageName, int resid, ApplicationInfo appInfo) { return null; }
    public ResolveInfo resolveService(android.content.Intent intent, int flags) {
        java.util.List<ResolveInfo> matches = queryIntentServices(intent, flags);
        return matches.isEmpty() ? null : matches.get(0);
    }
    /**
     * Hosted adaptation of PackageManagerService intent resolution. Manifest activities are
     * resolved locally; implicit desktop operations cross the ActivityThread host seam, whose
     * production adapter and in-memory test adapter provide the two resolver implementations.
     */
    public ResolveInfo resolveActivity(android.content.Intent intent, int flags) {
        if (intent == null) return null;
        android.content.ComponentName component = intent.getComponent();
        if (component == null) {
            component = android.app.ActivityThread.resolveActivityInProcess(intent);
        }
        if (component == null) return null;

        ActivityInfo activity = sHostedActivities.get(component.getClassName());
        if (activity != null) {
            if (!component.getPackageName().equals(activity.packageName)
                    || (!activity.enabled && (flags & MATCH_DISABLED_COMPONENTS) == 0)) return null;
            activity = new ActivityInfo(activity);
        } else {
            activity = new ActivityInfo();
            activity.name = component.getClassName();
            activity.packageName = component.getPackageName();
            activity.applicationInfo = hostedApplicationInfo(component.getPackageName());
        }
        ResolveInfo result = new ResolveInfo();
        result.activityInfo = activity;
        return result;
    }
    public ActivityInfo getReceiverInfo(android.content.ComponentName component, int flags)
            throws NameNotFoundException {
        throw new NameNotFoundException(String.valueOf(component));
    }
    public java.util.List<ResolveInfo> queryIntentActivitiesAsUser(android.content.Intent intent,
            int flags, int userId) {
        return queryIntentActivities(intent, flags);
    }
    public java.util.List<ResolveInfo> queryIntentActivityOptions(android.content.ComponentName caller,
            android.content.Intent[] specifics, android.content.Intent intent, int flags) {
        return new java.util.ArrayList<>();
    }
    public java.util.List<ResolveInfo> queryIntentActivities(android.content.Intent intent, int flags) {
        java.util.ArrayList<ResolveInfo> result = new java.util.ArrayList<>();
        ResolveInfo resolved = resolveActivity(intent, flags);
        if (resolved != null) result.add(resolved);
        return result;
    }
    public java.util.List<ResolveInfo> queryIntentServices(android.content.Intent intent, int flags) {
        java.util.ArrayList<ResolveInfo> result = new java.util.ArrayList<>();
        if (intent == null) return result;
        android.content.ComponentName requested = intent.getComponent();
        for (ServiceInfo service : sHostedServices) {
            if (!service.enabled && (flags & MATCH_DISABLED_COMPONENTS) == 0) continue;
            if (requested != null) {
                if (!requested.getPackageName().equals(service.packageName)
                        || !requested.getClassName().equals(service.name)) continue;
            } else {
                if (intent.getPackage() != null
                        && !intent.getPackage().equals(service.packageName)) continue;
                java.util.Set<String> actions = sHostedServiceActions.get(service.name);
                if (intent.getAction() == null || actions == null
                        || !actions.contains(intent.getAction())) continue;
            }
            ResolveInfo resolveInfo = new ResolveInfo();
            resolveInfo.serviceInfo = new ServiceInfo(service);
            result.add(resolveInfo);
        }
        return result;
    }
    public ServiceInfo getServiceInfo(android.content.ComponentName component, int flags)
            throws NameNotFoundException {
        for (ServiceInfo service : sHostedServices) {
            if (component.getPackageName().equals(service.packageName)
                    && component.getClassName().equals(service.name)
                    && (service.enabled || (flags & MATCH_DISABLED_COMPONENTS) != 0)) {
                return new ServiceInfo(service);
            }
        }
        throw new NameNotFoundException(component.flattenToString());
    }
    public android.content.Intent getLaunchIntentForPackage(String packageName) {
        if (packageName == null || !packageName.equals(sHostedPackageName)
                || sHostedLauncherActivity == null) return null;
        return new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .setPackage(packageName)
                .setComponent(new android.content.ComponentName(
                        packageName, sHostedLauncherActivity))
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }
    public String[] getPackagesForUid(int uid) {
        if (uid == android.os.Process.myUid() && sHostedPackageName != null) {
            return new String[] { sHostedPackageName };
        }
        return new String[0];
    }
    public ActivityInfo getActivityInfo(android.content.ComponentName component, int flags)
            throws NameNotFoundException {
        ActivityInfo activity = sHostedActivities.get(component.getClassName());
        if (activity != null && component.getPackageName().equals(activity.packageName)
                && (activity.enabled || (flags & MATCH_DISABLED_COMPONENTS) != 0)) {
            return new ActivityInfo(activity);
        }
        throw new NameNotFoundException(component.flattenToString());
    }
    private static ApplicationInfo hostedApplicationInfo(String packageName) {
        ApplicationInfo result = new ApplicationInfo();
        result.packageName = packageName;
        result.theme = sHostedApplicationTheme;
        result.sourceDir = sHostedSourceDir;
        result.publicSourceDir = sHostedSourceDir;
        result.dataDir = sHostedDataDir;
        return result;
    }
    public ProviderInfo getProviderInfo(android.content.ComponentName component, int flags)
            throws NameNotFoundException {
        for (ProviderInfo provider : sHostedProviders) {
            if (component.getPackageName().equals(provider.packageName)
                    && component.getClassName().equals(provider.name)) {
                return new ProviderInfo(provider);
            }
        }
        throw new NameNotFoundException(component.flattenToString());
    }
    public PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException {
        PackageInfo pi = new PackageInfo();
        pi.packageName = packageName;
        if (packageName != null && packageName.equals(sHostedPackageName)) {
            pi.versionCode = sHostedVersionCode;
            pi.versionName = sHostedVersionName;
        } else {
            pi.versionName = "1.0";
        }
        if (packageName != null && packageName.equals(sHostedPackageName)
                && (flags & GET_PERMISSIONS) != 0) {
            pi.requestedPermissions = sHostedRequestedPermissions.toArray(new String[0]);
            pi.requestedPermissionsFlags = new int[pi.requestedPermissions.length];
            for (int i = 0; i < pi.requestedPermissions.length; i++) {
                pi.requestedPermissionsFlags[i] = PackageInfo.REQUESTED_PERMISSION_REQUIRED;
                if (sHostedGrantedPermissions.contains(pi.requestedPermissions[i])) {
                    pi.requestedPermissionsFlags[i] |= PackageInfo.REQUESTED_PERMISSION_GRANTED;
                }
            }
        }
        pi.signingInfo = new SigningInfo();
        pi.signatures = new Signature[] { new Signature(new byte[] { 4, 2 }) };
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.packageName = packageName;
        pi.applicationInfo.processName = packageName;
        if (packageName != null && packageName.equals(sHostedPackageName)) {
            pi.applicationInfo.theme = sHostedApplicationTheme;
        }
        return pi;
    }
    public PackageInfo getPackageInfo(String packageName, PackageInfoFlags flags)
            throws NameNotFoundException {
        return getPackageInfo(packageName, (int) flags.getValue());
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
    public int checkPermission(String permName, String packageName) {
        if (permName == null) throw new IllegalArgumentException("permission is null");
        return packageName != null && packageName.equals(sHostedPackageName)
                && sHostedGrantedPermissions.contains(permName)
                ? PERMISSION_GRANTED : PERMISSION_DENIED;
    }
    public java.util.List<ResolveInfo> queryBroadcastReceivers(android.content.Intent intent, int flags) {
        return new java.util.ArrayList<>();
    }
    public java.util.List<ProviderInfo> queryContentProviders(String processName, int uid, int flags) {
        java.util.ArrayList<ProviderInfo> result = new java.util.ArrayList<>();
        for (ProviderInfo provider : sHostedProviders) {
            if (processName == null || processName.equals(provider.processName)) {
                result.add(new ProviderInfo(provider));
            }
        }
        return result;
    }
    public ProviderInfo resolveContentProvider(String authority, int flags) {
        if (authority == null) return null;
        for (ProviderInfo provider : sHostedProviders) {
            if (authority.equals(provider.authority)) return new ProviderInfo(provider);
        }
        return null;
    }
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
        info.processName = packageName;
        info.enabled = true;
        info.metaData = new android.os.Bundle();
        if (packageName != null && packageName.equals(sHostedPackageName)) {
            info.sourceDir = sHostedSourceDir;
            info.publicSourceDir = sHostedSourceDir;
            info.dataDir = sHostedDataDir;
            info.theme = sHostedApplicationTheme;
        }
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
