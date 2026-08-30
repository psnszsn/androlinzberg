/* Hosted adaptation of android.content.pm.PackageManager: a client of the
 * session's package manager service (AOSP's ApplicationPackageManager shape).
 * Package state lives in the system process (dev.artzig.system
 * .HostedPackageManagerService); this class registers the packages parsed in
 * this process and answers every query from the service, keeping only the
 * records this process itself registered as a local cache. */
package android.content.pm;
public abstract class PackageManager {
    // ------------------------------------------------------------------ PMS client
    private static volatile dev.artzig.system.IHostedPackageManager sService;
    private static dev.artzig.system.IHostedPackageManager service() {
        dev.artzig.system.IHostedPackageManager s = sService;
        if (s != null) return s;
        android.os.IBinder binder = android.os.ServiceManager.waitForService("package");
        if (binder == null) {
            throw new IllegalStateException("no hosted package manager in this session");
        }
        s = dev.artzig.system.IHostedPackageManager.Stub.asInterface(binder);
        sService = s;
        return s;
    }
    /** Packages this process registered, by name: the local cache (never stale —
     * the registering process is the source of truth for its own packages). */
    private static final java.util.concurrent.ConcurrentHashMap<String, dev.artzig.system.HostedInstalledPackage>
            sRegisteredHere = new java.util.concurrent.ConcurrentHashMap<>();
    private static dev.artzig.system.HostedInstalledPackage installedPackage(String packageName) {
        if (packageName == null) return null;
        dev.artzig.system.HostedInstalledPackage local = sRegisteredHere.get(packageName);
        if (local != null) return local;
        try {
            return service().getPackage(packageName);
        } catch (android.os.RemoteException e) {
            throw new IllegalStateException("hosted package manager unreachable", e);
        }
    }

    // ------------------------------------------------------- registration staging
    /* The configureHosted* entry points stage one package's parse results and
     * (re-)register the staged package with the service after every call, so
     * incremental callers (ApkHost, smokes) keep their call shape. */
    private static final Object sStageLock = new Object();
    private static String sStagedPackageName;
    private static int sStagedVersionCode;
    private static String sStagedVersionName;
    private static String sStagedSourceDir;
    private static String sStagedDataDir;
    private static int sStagedApplicationTheme;
    private static String sStagedApplicationClass;
    private static String sStagedLauncherActivity;
    private static java.util.List<ActivityInfo> sStagedActivities = new java.util.ArrayList<>();
    private static java.util.List<ProviderInfo> sStagedProviders = new java.util.ArrayList<>();
    private static java.util.List<ServiceInfo> sStagedServices = new java.util.ArrayList<>();
    private static java.util.Map<String, java.util.Set<String>> sStagedServiceActions =
            new java.util.HashMap<>();
    private static java.util.Set<String> sStagedRequestedPermissions =
            java.util.Collections.emptySet();
    private static java.util.Set<String> sStagedGrantedPermissions =
            java.util.Collections.emptySet();

    public static void configureHostedPackage(String packageName, int versionCode,
            String versionName, String sourceDir, String dataDir) {
        synchronized (sStageLock) {
            sStagedPackageName = packageName;
            sStagedVersionCode = versionCode;
            sStagedVersionName = versionName;
            sStagedSourceDir = sourceDir;
            sStagedDataDir = dataDir;
            sStagedApplicationTheme = 0;
            sStagedApplicationClass = null;
            sStagedLauncherActivity = null;
            sStagedActivities = new java.util.ArrayList<>();
            sStagedProviders = new java.util.ArrayList<>();
            sStagedServices = new java.util.ArrayList<>();
            sStagedServiceActions = new java.util.HashMap<>();
            sStagedRequestedPermissions = java.util.Collections.emptySet();
            sStagedGrantedPermissions = java.util.Collections.emptySet();
            registerStagedLocked();
        }
    }
    public static void configureHostedLauncher(String activityName) {
        synchronized (sStageLock) {
            sStagedLauncherActivity = activityName;
            registerStagedLocked();
        }
    }
    public static void configureHostedPackage(HostedPackage hostedPackage,
            String sourceDir, String dataDir) {
        configureHostedPackage(hostedPackage, sourceDir, dataDir,
                java.util.Collections.emptyMap());
    }
    public static void configureHostedPackage(HostedPackage hostedPackage,
            String sourceDir, String dataDir,
            java.util.Map<String, Integer> platformPermissionProtectionLevels) {
        synchronized (sStageLock) {
            sStagedPackageName = hostedPackage.getPackageName();
            sStagedVersionCode = hostedPackage.getVersionCode();
            sStagedVersionName = hostedPackage.getVersionName();
            sStagedSourceDir = sourceDir;
            sStagedDataDir = dataDir;
            sStagedApplicationTheme = hostedPackage.getApplicationTheme();
            sStagedApplicationClass = hostedPackage.getApplicationClassName();
            sStagedLauncherActivity = hostedPackage.getLauncherActivityName();
            java.util.ArrayList<ActivityInfo> activities = new java.util.ArrayList<>();
            for (ActivityInfo activity : hostedPackage.getActivities().values()) {
                activities.add(new ActivityInfo(activity));
            }
            sStagedActivities = activities;
            java.util.ArrayList<ProviderInfo> providers = new java.util.ArrayList<>();
            for (ProviderInfo provider : hostedPackage.getProviders()) {
                providers.add(new ProviderInfo(provider));
            }
            sStagedProviders = providers;
            java.util.ArrayList<ServiceInfo> services = new java.util.ArrayList<>();
            for (ServiceInfo service : hostedPackage.getServices()) {
                services.add(new ServiceInfo(service));
            }
            sStagedServices = services;
            java.util.HashMap<String, java.util.Set<String>> actions = new java.util.HashMap<>();
            for (java.util.Map.Entry<String, java.util.Set<String>> entry
                    : hostedPackage.getServiceActions().entrySet()) {
                actions.put(entry.getKey(), new java.util.HashSet<>(entry.getValue()));
            }
            sStagedServiceActions = actions;
            stagePermissionsLocked(hostedPackage.getRequestedPermissions(),
                    hostedPackage.getDeclaredPermissionProtectionLevels(),
                    platformPermissionProtectionLevels);
            registerStagedLocked();
        }
    }
    /**
     * Hosted adaptation of PackageManagerService's install-time permission grant.
     * The full service cannot run without package installation state and the
     * permission controller, so the host grants only requested permissions whose
     * declaring manifest marks them with AOSP's normal protection base.
     */
    public static void configureHostedPermissions(java.util.Set<String> requestedPermissions,
            java.util.Map<String, Integer> packagePermissionProtectionLevels,
            java.util.Map<String, Integer> platformPermissionProtectionLevels) {
        synchronized (sStageLock) {
            stagePermissionsLocked(requestedPermissions, packagePermissionProtectionLevels,
                    platformPermissionProtectionLevels);
            registerStagedLocked();
        }
    }
    private static void stagePermissionsLocked(java.util.Set<String> requestedPermissions,
            java.util.Map<String, Integer> packagePermissionProtectionLevels,
            java.util.Map<String, Integer> platformPermissionProtectionLevels) {
        java.util.LinkedHashSet<String> requested = new java.util.LinkedHashSet<>();
        if (requestedPermissions != null) requested.addAll(requestedPermissions);
        sStagedRequestedPermissions = java.util.Collections.unmodifiableSet(requested);
        sStagedGrantedPermissions = java.util.Collections.unmodifiableSet(computeGrantedPermissions(
                requested, packagePermissionProtectionLevels, platformPermissionProtectionLevels));
    }
    public static java.util.LinkedHashSet<String> computeGrantedPermissions(
            java.util.Set<String> requested,
            java.util.Map<String, Integer> packagePermissionProtectionLevels,
            java.util.Map<String, Integer> platformPermissionProtectionLevels) {
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
        return granted;
    }
    public static void configureHostedProviders(java.util.List<ProviderInfo> providers) {
        synchronized (sStageLock) {
            java.util.ArrayList<ProviderInfo> copy = new java.util.ArrayList<>();
            if (providers != null) {
                for (ProviderInfo provider : providers) copy.add(new ProviderInfo(provider));
            }
            sStagedProviders = copy;
            registerStagedLocked();
        }
    }
    public static void configureHostedServices(java.util.List<ServiceInfo> services,
            java.util.Map<String, java.util.Set<String>> actionsByClassName) {
        synchronized (sStageLock) {
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
            sStagedServices = serviceCopy;
            sStagedServiceActions = actionCopy;
            registerStagedLocked();
        }
    }
    private static void registerStagedLocked() {
        if (sStagedPackageName == null) return;
        PackageInfo info = new PackageInfo();
        info.packageName = sStagedPackageName;
        info.versionCode = sStagedVersionCode;
        info.versionName = sStagedVersionName;
        info.activities = sStagedActivities.toArray(new ActivityInfo[0]);
        info.services = sStagedServices.toArray(new ServiceInfo[0]);
        info.providers = sStagedProviders.toArray(new ProviderInfo[0]);
        info.requestedPermissions = sStagedRequestedPermissions.toArray(new String[0]);
        android.os.Bundle actions = new android.os.Bundle();
        for (java.util.Map.Entry<String, java.util.Set<String>> entry
                : sStagedServiceActions.entrySet()) {
            actions.putStringArrayList(entry.getKey(), new java.util.ArrayList<>(entry.getValue()));
        }
        dev.artzig.system.HostedInstalledPackage installed =
                new dev.artzig.system.HostedInstalledPackage(info, sStagedApplicationClass,
                        sStagedLauncherActivity, sStagedApplicationTheme, sStagedSourceDir,
                        sStagedDataDir, actions, new java.util.ArrayList<>(sStagedGrantedPermissions));
        try {
            service().registerPackage(installed);
        } catch (android.os.RemoteException e) {
            throw new IllegalStateException("hosted package manager unreachable", e);
        }
        sRegisteredHere.put(installed.packageInfo.packageName, installed);
    }
    /** Builds the installed-package record from a parse; used by the service's own installPackage. */
    public static dev.artzig.system.HostedInstalledPackage toInstalledPackage(
            HostedPackage hosted, String sourceDir, String dataDir,
            java.util.Map<String, Integer> platformPermissionProtectionLevels) {
        PackageInfo info = new PackageInfo();
        info.packageName = hosted.getPackageName();
        info.versionCode = hosted.getVersionCode();
        info.versionName = hosted.getVersionName();
        info.activities = hosted.getActivities().values().toArray(new ActivityInfo[0]);
        info.services = hosted.getServices().toArray(new ServiceInfo[0]);
        info.providers = hosted.getProviders().toArray(new ProviderInfo[0]);
        info.requestedPermissions = hosted.getRequestedPermissions().toArray(new String[0]);
        android.os.Bundle actions = new android.os.Bundle();
        for (java.util.Map.Entry<String, java.util.Set<String>> entry
                : hosted.getServiceActions().entrySet()) {
            actions.putStringArrayList(entry.getKey(), new java.util.ArrayList<>(entry.getValue()));
        }
        java.util.LinkedHashSet<String> granted = computeGrantedPermissions(hosted.getRequestedPermissions(),
                hosted.getDeclaredPermissionProtectionLevels(), platformPermissionProtectionLevels);
        return new dev.artzig.system.HostedInstalledPackage(info,
                hosted.getApplicationClassName(), hosted.getLauncherActivityName(),
                hosted.getApplicationTheme(), sourceDir, dataDir, actions,
                new java.util.ArrayList<>(granted));
    }

    // ---------------------------------------------------------------- constants
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
    public static final long MATCH_APEX = 0x40000000;
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

    // ------------------------------------------------------------------ queries
    public ResolveInfo resolveService(android.content.Intent intent, int flags) {
        java.util.List<ResolveInfo> matches = queryIntentServices(intent, flags);
        return matches.isEmpty() ? null : matches.get(0);
    }
    /**
     * Hosted adaptation of PackageManagerService intent resolution. Manifest activities are
     * resolved through the session's package manager; implicit desktop operations cross the
     * ActivityThread host seam, whose production adapter and in-memory test adapter provide
     * the two resolver implementations.
     */
    public ResolveInfo resolveActivity(android.content.Intent intent, int flags) {
        if (intent == null) return null;
        android.content.ComponentName component = intent.getComponent();
        if (component == null) {
            component = android.app.ActivityThread.resolveActivityInProcess(intent);
        }
        if (component == null) return null;

        ActivityInfo activity = findActivity(component);
        if (activity != null) {
            if (!activity.enabled && (flags & MATCH_DISABLED_COMPONENTS) == 0) return null;
            activity = new ActivityInfo(activity);
        } else {
            activity = new ActivityInfo();
            activity.name = component.getClassName();
            activity.packageName = component.getPackageName();
            activity.applicationInfo = installedApplicationInfo(component.getPackageName());
        }
        ResolveInfo result = new ResolveInfo();
        result.activityInfo = activity;
        return result;
    }
    private static ActivityInfo findActivity(android.content.ComponentName component) {
        dev.artzig.system.HostedInstalledPackage pkg = installedPackage(component.getPackageName());
        if (pkg == null || pkg.packageInfo.activities == null) return null;
        for (ActivityInfo activity : pkg.packageInfo.activities) {
            if (component.getClassName().equals(activity.name)) return activity;
        }
        return null;
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
        if (intent == null) return new java.util.ArrayList<>();
        try {
            return service().queryIntentServices(intent, flags);
        } catch (android.os.RemoteException e) {
            throw new IllegalStateException("hosted package manager unreachable", e);
        }
    }
    public ServiceInfo getServiceInfo(android.content.ComponentName component, int flags)
            throws NameNotFoundException {
        dev.artzig.system.HostedInstalledPackage pkg = installedPackage(component.getPackageName());
        if (pkg != null && pkg.packageInfo.services != null) {
            for (ServiceInfo service : pkg.packageInfo.services) {
                if (component.getClassName().equals(service.name)
                        && (service.enabled || (flags & MATCH_DISABLED_COMPONENTS) != 0)) {
                    return new ServiceInfo(service);
                }
            }
        }
        throw new NameNotFoundException(component.flattenToString());
    }
    public android.content.Intent getLaunchIntentForPackage(String packageName) {
        dev.artzig.system.HostedInstalledPackage pkg = installedPackage(packageName);
        if (pkg == null || pkg.launcherActivityName == null) return null;
        return new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .setPackage(packageName)
                .setComponent(new android.content.ComponentName(
                        packageName, pkg.launcherActivityName))
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }
    public String[] getPackagesForUid(int uid) {
        try {
            return service().getPackagesForUid(uid);
        } catch (android.os.RemoteException e) {
            throw new IllegalStateException("hosted package manager unreachable", e);
        }
    }
    public ActivityInfo getActivityInfo(android.content.ComponentName component, int flags)
            throws NameNotFoundException {
        ActivityInfo activity = findActivity(component);
        if (activity != null && (activity.enabled || (flags & MATCH_DISABLED_COMPONENTS) != 0)) {
            return new ActivityInfo(activity);
        }
        throw new NameNotFoundException(component.flattenToString());
    }
    private static ApplicationInfo installedApplicationInfo(String packageName) {
        dev.artzig.system.HostedInstalledPackage pkg = installedPackage(packageName);
        ApplicationInfo result = new ApplicationInfo();
        result.packageName = packageName;
        if (pkg != null) {
            result.theme = pkg.applicationTheme;
            result.sourceDir = pkg.apkPath;
            result.publicSourceDir = pkg.apkPath;
            result.dataDir = pkg.dataDir;
        }
        return result;
    }
    public ProviderInfo getProviderInfo(android.content.ComponentName component, int flags)
            throws NameNotFoundException {
        dev.artzig.system.HostedInstalledPackage pkg = installedPackage(component.getPackageName());
        if (pkg != null && pkg.packageInfo.providers != null) {
            for (ProviderInfo provider : pkg.packageInfo.providers) {
                if (component.getClassName().equals(provider.name)) {
                    return new ProviderInfo(provider);
                }
            }
        }
        throw new NameNotFoundException(component.flattenToString());
    }
    public PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException {
        dev.artzig.system.HostedInstalledPackage pkg = installedPackage(packageName);
        PackageInfo pi = new PackageInfo();
        pi.packageName = packageName;
        if (pkg != null) {
            pi.versionCode = pkg.packageInfo.versionCode;
            pi.versionName = pkg.packageInfo.versionName;
        } else {
            pi.versionName = "1.0";
        }
        if (pkg != null && (flags & GET_PERMISSIONS) != 0
                && pkg.packageInfo.requestedPermissions != null) {
            pi.requestedPermissions = pkg.packageInfo.requestedPermissions.clone();
            pi.requestedPermissionsFlags = new int[pi.requestedPermissions.length];
            for (int i = 0; i < pi.requestedPermissions.length; i++) {
                pi.requestedPermissionsFlags[i] = PackageInfo.REQUESTED_PERMISSION_REQUIRED;
                if (pkg.grantedPermissions.contains(pi.requestedPermissions[i])) {
                    pi.requestedPermissionsFlags[i] |= PackageInfo.REQUESTED_PERMISSION_GRANTED;
                }
            }
        }
        pi.signingInfo = new SigningInfo();
        pi.signatures = new Signature[] { new Signature(new byte[] { 4, 2 }) };
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.packageName = packageName;
        pi.applicationInfo.processName = packageName;
        if (pkg != null) {
            pi.applicationInfo.theme = pkg.applicationTheme;
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
    /**
     * Permission state of another uid; answered by the session's package
     * manager (source note: AOSP asks PermissionManagerService).
     */
    public static int checkPermissionForUid(String permName, int uid) {
        try {
            return service().checkUidPermission(permName, uid);
        } catch (android.os.RemoteException e) {
            android.util.Log.w("PackageManager", "permission " + permName + " for uid " + uid
                    + " denied: package manager unreachable", e);
            return PERMISSION_DENIED;
        }
    }
    public int checkPermission(String permName, String packageName) {
        if (permName == null) throw new IllegalArgumentException("permission is null");
        dev.artzig.system.HostedInstalledPackage local =
                packageName != null ? sRegisteredHere.get(packageName) : null;
        if (local != null) {
            return local.grantedPermissions.contains(permName) ? PERMISSION_GRANTED : PERMISSION_DENIED;
        }
        try {
            return service().checkPermission(permName, packageName);
        } catch (android.os.RemoteException e) {
            throw new IllegalStateException("hosted package manager unreachable", e);
        }
    }
    public java.util.List<ResolveInfo> queryBroadcastReceivers(android.content.Intent intent, int flags) {
        return new java.util.ArrayList<>();
    }
    public java.util.List<ProviderInfo> queryContentProviders(String processName, int uid, int flags) {
        java.util.ArrayList<ProviderInfo> result = new java.util.ArrayList<>();
        for (dev.artzig.system.HostedInstalledPackage pkg : sRegisteredHere.values()) {
            if (pkg.packageInfo.providers == null) continue;
            for (ProviderInfo provider : pkg.packageInfo.providers) {
                if (processName == null || processName.equals(provider.processName)) {
                    result.add(new ProviderInfo(provider));
                }
            }
        }
        return result;
    }
    public ProviderInfo resolveContentProvider(String authority, int flags) {
        if (authority == null) return null;
        for (dev.artzig.system.HostedInstalledPackage pkg : sRegisteredHere.values()) {
            if (pkg.packageInfo.providers == null) continue;
            for (ProviderInfo provider : pkg.packageInfo.providers) {
                if (authority.equals(provider.authority)) return new ProviderInfo(provider);
            }
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
    public ApplicationInfo getApplicationInfoAsUser(String packageName, int flags, int userId)
            throws NameNotFoundException {
        return getApplicationInfo(packageName, flags);
    }
    public ApplicationInfo getApplicationInfo(String packageName, int flags)
            throws NameNotFoundException {
        dev.artzig.system.HostedInstalledPackage pkg = installedPackage(packageName);
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = packageName;
        info.name = packageName;
        info.processName = packageName;
        info.enabled = true;
        info.metaData = new android.os.Bundle();
        if (pkg != null) {
            info.sourceDir = pkg.apkPath;
            info.publicSourceDir = pkg.apkPath;
            info.dataDir = pkg.dataDir;
            info.theme = pkg.applicationTheme;
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
