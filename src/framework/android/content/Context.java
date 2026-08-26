/* artzig shim: android.content.Context — minimal abstract surface for the vendored
 * graphics/text/res packages. Replaced piecemeal by richer glue in M2+ (ContextImpl). */
package android.content;
import android.content.res.AssetManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import java.io.File;
import java.util.concurrent.Executor;
public abstract class Context {
    public static final int MODE_PRIVATE = 0, MODE_WORLD_READABLE = 1, MODE_WORLD_WRITEABLE = 2,
            MODE_APPEND = 0x8000, MODE_MULTI_PROCESS = 4, MODE_ENABLE_WRITE_AHEAD_LOGGING = 8,
            MODE_NO_LOCALIZED_COLLATORS = 0x10;
    public static final String ACTIVITY_SERVICE = "activity", ALARM_SERVICE = "alarm",
            AUDIO_SERVICE = "audio", CLIPBOARD_SERVICE = "clipboard",
            CONNECTIVITY_SERVICE = "connectivity", DISPLAY_SERVICE = "display",
            DOWNLOAD_SERVICE = "download", INPUT_METHOD_SERVICE = "input_method",
            INPUT_SERVICE = "input", JOB_SCHEDULER_SERVICE = "jobscheduler",
            KEYGUARD_SERVICE = "keyguard", LAYOUT_INFLATER_SERVICE = "layout_inflater",
            LOCATION_SERVICE = "location", MEDIA_SESSION_SERVICE = "media_session",
            NOTIFICATION_SERVICE = "notification", POWER_SERVICE = "power",
            SEARCH_SERVICE = "search", SENSOR_SERVICE = "sensor", STORAGE_SERVICE = "storage",
            STORAGE_STATS_SERVICE = "storagestats",
            TELEPHONY_SERVICE = "phone", UI_MODE_SERVICE = "uimode", USER_SERVICE = "user",
            VIBRATOR_SERVICE = "vibrator", WALLPAPER_SERVICE = "wallpaper",
            WIFI_SERVICE = "wifi", WINDOW_SERVICE = "window", ACCESSIBILITY_SERVICE = "accessibility",
            APP_OPS_SERVICE = "appops", BATTERY_SERVICE = "batterymanager",
            CAMERA_SERVICE = "camera", TEXT_CLASSIFICATION_SERVICE = "textclassification",
            TEXT_SERVICES_MANAGER_SERVICE = "textservices",
            CONTENT_CAPTURE_MANAGER_SERVICE = "content_capture",
            TRANSLATION_MANAGER_SERVICE = "translation",
            UI_TRANSLATION_SERVICE = "ui_translation",
            AUTOFILL_MANAGER_SERVICE = "autofill",
            CREDENTIAL_SERVICE = "credential",
            VIRTUAL_DEVICE_SERVICE = "virtualdevice",
            MEDIA_PROJECTION_SERVICE = "media_projection",
            VIBRATOR_MANAGER_SERVICE = "vibrator_manager",
            PERFORMANCE_HINT_SERVICE = "performance_hint";
    public static final int DEVICE_ID_DEFAULT = 0, DEVICE_ID_INVALID = -1;
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface ServiceName {}
    public static final String CAPTIONING_SERVICE = "captioning";
    public static final String DISPLAY_HASH_SERVICE = "display_hash";
    public static final String ROLE_SERVICE = "role";
    public void startIntentSender(IntentSender intent, Intent fillInIntent, int flagsMask,
            int flagsValues, int extraFlags, android.os.Bundle options)
            throws IntentSender.SendIntentException {}
    public void startIntentSender(IntentSender intent, Intent fillInIntent, int flagsMask,
            int flagsValues, int extraFlags) throws IntentSender.SendIntentException {}
    public static final int CONTEXT_INCLUDE_CODE = 0x0001, CONTEXT_IGNORE_SECURITY = 0x0002,
            CONTEXT_RESTRICTED = 0x0004, CONTEXT_DEVICE_PROTECTED_STORAGE = 0x0008;
    public Context createPackageContext(String packageName, int flags) throws android.content.pm.PackageManager.NameNotFoundException {
        throw new android.content.pm.PackageManager.NameNotFoundException(packageName);
    }
    public Context createApplicationContext(android.content.pm.ApplicationInfo application, int flags)
            throws android.content.pm.PackageManager.NameNotFoundException {
        throw new android.content.pm.PackageManager.NameNotFoundException(String.valueOf(application));
    }
    public abstract Resources getResources();
    public abstract AssetManager getAssets();
    public abstract Resources.Theme getTheme();
    public abstract String getPackageName();
    public abstract ClassLoader getClassLoader();
    public abstract Context getApplicationContext();
    public abstract Object getSystemService(String name);
    public <T> T getSystemService(Class<T> serviceClass) {
        String name = getSystemServiceName(serviceClass);
        return name != null ? (T) getSystemService(name) : null;
    }
    public String getSystemServiceName(Class<?> serviceClass) { return null; }
    public android.content.pm.ApplicationInfo getApplicationInfo() { return new android.content.pm.ApplicationInfo(); }
    public android.content.pm.PackageManager getPackageManager() { return null; }
    public ContentResolver getContentResolver() { return null; }
    public Looper getMainLooper() { return Looper.getMainLooper(); }
    public Executor getMainExecutor() { return new Handler(getMainLooper())::post; }
    public File getFilesDir() { return new File(android.os.Environment.getDataDirectory(), "files"); }
    public File getCacheDir() { return new File(android.os.Environment.getDataDirectory(), "cache"); }
    public File getDir(String name, int mode) {
        File f = new File(android.os.Environment.getDataDirectory(), "app_" + name);
        f.mkdirs();
        return f;
    }
    public File getDataDir() { return android.os.Environment.getDataDirectory(); }
    public File getExternalFilesDir(String type) { return getFilesDir(); }
    public File getNoBackupFilesDir() { return getFilesDir(); }
    public File getExternalCacheDir() { return mkdirsOf(new File(android.os.Environment.getExternalStorageDirectory(), "cache")); }
    public File[] getExternalCacheDirs() { return new File[] { getExternalCacheDir() }; }
    public File[] getExternalFilesDirs(String type) { return new File[] { getExternalFilesDir(type) }; }
    public File[] getExternalMediaDirs() { return new File[] { getExternalFilesDir("media") }; }
    public File getCodeCacheDir() { return mkdirsOf(new File(android.os.Environment.getDataDirectory(), "code_cache")); }
    public File getObbDir() { return mkdirsOf(new File(android.os.Environment.getDataDirectory(), "obb")); }
    public File[] getObbDirs() { return new File[] { getObbDir() }; }
    public File getFileStreamPath(String name) { return new File(getFilesDir(), name); }
    public java.io.FileInputStream openFileInput(String name) throws java.io.FileNotFoundException {
        return new java.io.FileInputStream(getFileStreamPath(name));
    }
    public java.io.FileOutputStream openFileOutput(String name, int mode) throws java.io.FileNotFoundException {
        getFilesDir().mkdirs();
        return new java.io.FileOutputStream(getFileStreamPath(name), (mode & MODE_APPEND) != 0);
    }
    public String[] fileList() {
        String[] l = getFilesDir().list();
        return l != null ? l : new String[0];
    }
    public boolean deleteFile(String name) { return getFileStreamPath(name).delete(); }
    public String getPackageCodePath() { return getApplicationInfo().sourceDir; }
    public String getPackageResourcePath() { return getApplicationInfo().sourceDir; }
    public Context createDeviceProtectedStorageContext() { return this; }
    public boolean isDeviceProtectedStorage() { return false; }
    public boolean moveSharedPreferencesFrom(Context sourceContext, String name) { return true; }
    public boolean deleteSharedPreferences(String name) { return true; }
    public int checkCallingPermission(String permission) { return -1; }
    public int checkPermission(String permission, int pid, int uid, Object callerToken) { return -1; }
    public void enforcePermission(String permission, int pid, int uid, String message) {}
    public void grantUriPermission(String toPackage, android.net.Uri uri, int modeFlags) {}
    public void revokeUriPermission(android.net.Uri uri, int modeFlags) {}
    public int checkUriPermission(android.net.Uri uri, int pid, int uid, int modeFlags) { return -1; }
    public android.content.ComponentName startService(Intent service) { return android.app.ActivityThread.startServiceInProcess(service); }
    public boolean stopService(Intent service) { return android.app.ActivityThread.stopServiceInProcess(service); }
    public boolean bindService(Intent service, ServiceConnection conn, int flags) {
        return android.app.ActivityThread.bindServiceInProcess(service, conn, flags);
    }
    public void unbindService(ServiceConnection conn) { android.app.ActivityThread.unbindServiceInProcess(conn); }
    public android.content.ComponentName startForegroundService(Intent service) { return startService(service); }
    public void sendBroadcast(Intent intent, String receiverPermission) {
        ArtzigBroadcastManager.send(this, intent);
    }
    public void sendOrderedBroadcast(Intent intent, String receiverPermission) {
        ArtzigBroadcastManager.send(this, intent);
    }
    public void removeStickyBroadcast(Intent intent) {}
    public void sendStickyBroadcast(Intent intent) {}
    public void startActivity(Intent intent) { android.app.ActivityThread.startActivityInProcess(intent); }
    public void startActivity(Intent intent, android.os.Bundle options) { startActivity(intent); }
    public void startActivities(Intent[] intents) {}
    public String getBasePackageName() { return getPackageName(); }
    private static File mkdirsOf(File f) { f.mkdirs(); return f; }
    public String getString(int resId) { return getResources().getString(resId); }
    public String getString(int resId, Object... formatArgs) { return getResources().getString(resId, formatArgs); }
    public CharSequence getText(int resId) { return getResources().getText(resId); }
    public int getColor(int id) { return getResources().getColor(id, getTheme()); }
    public ColorStateList getColorStateList(int id) { return getResources().getColorStateList(id, getTheme()); }
    public Drawable getDrawable(int id) { return getResources().getDrawable(id, getTheme()); }
    public TypedArray obtainStyledAttributes(int[] attrs) { return getTheme().obtainStyledAttributes(attrs); }
    public TypedArray obtainStyledAttributes(int resid, int[] attrs) { return getTheme().obtainStyledAttributes(resid, attrs); }
    public TypedArray obtainStyledAttributes(AttributeSet set, int[] attrs) { return getTheme().obtainStyledAttributes(set, attrs, 0, 0); }
    public TypedArray obtainStyledAttributes(AttributeSet set, int[] attrs, int defStyleAttr, int defStyleRes) {
        return getTheme().obtainStyledAttributes(set, attrs, defStyleAttr, defStyleRes);
    }
    public boolean isRestricted() { return false; }
    public boolean canLoadUnsafeResources() { return true; }
    public boolean isAutofillCompatibilityEnabled() { return false; }
    public boolean canStartActivityForResult() { return false; }
    public Context createPackageContextAsUser(String packageName, int flags, android.os.UserHandle user)
            throws android.content.pm.PackageManager.NameNotFoundException {
        throw new android.content.pm.PackageManager.NameNotFoundException(packageName);
    }
    public android.os.Handler getMainThreadHandler() {
        return new android.os.Handler(getMainLooper());
    }
    public void startActivityAsUser(Intent intent, android.os.UserHandle user) {}
    public void startActivityForResult(String who, Intent intent, int requestCode, android.os.Bundle options) {
        throw android.util.ArtzigTestDiagnostics.unsupported(
                "android.content.Context", "startActivityForResult", null);
    }
    public Intent registerReceiverAsUser(BroadcastReceiver receiver, android.os.UserHandle user,
            IntentFilter filter, String broadcastPermission, android.os.Handler scheduler) {
        return ArtzigBroadcastManager.register(this, receiver, filter, scheduler);
    }
    public android.content.AutofillOptions getAutofillOptions() { return null; }
    public android.content.ContentCaptureOptions getContentCaptureOptions() { return null; }
    public android.view.autofill.AutofillManager.AutofillClient getAutofillClient() { return null; }
    public void setAutofillClient(android.view.autofill.AutofillManager.AutofillClient client) {}
    public int getDeviceId() { return DEVICE_ID_DEFAULT; }
    public int getNextAutofillId() { return 1; }
    public static android.os.IBinder getToken(Context context) { return null; }
    public android.view.Display getDisplayNoVerify() { return null; }
    public android.view.Display getDisplay() { return null; }
    public int checkPermission(String permission, int pid, int uid) { return -1; }
    public int checkCallingOrSelfPermission(String permission) { return -1; }
    public int checkSelfPermission(String permission) { return -1; }
    public void enforceCallingOrSelfPermission(String permission, String message) {}
    public String getOpPackageName() { return getPackageName(); }
    public String getAttributionTag() { return null; }
    public AttributionSource getAttributionSource() {
        return new AttributionSource(android.os.Process.myUid(), getOpPackageName(),
                getAttributionTag());
    }
    public android.os.UserHandle getUser() { return android.os.UserHandle.SYSTEM; }
    public int getUserId() { return 0; }
    public Context createConfigurationContext(Configuration overrideConfiguration) { return this; }
    public Context createContextAsUser(android.os.UserHandle user, int flags) { return this; }
    public Context createDeviceContext(int deviceId) { return this; }
    public Context createWindowContext(int type, android.os.Bundle options) { return this; }
    public Context createWindowContext(android.view.Display display, int type, android.os.Bundle options) { return this; }
    public Context createDisplayContext(android.view.Display display) { return this; }
    public Context createAttributionContext(String attributionTag) { return this; }
    public Context getApplicationContextOrSelf() { return this; }
    public boolean isUiContext() { return true; }
    public boolean isConfigurationContext() { return false; }
    public void updateDisplay(int displayId) {}
    public void setTheme(int resid) {}
    public int getThemeResId() { return 0; }
    public int getDisplayId() { return 0; }
    public void registerComponentCallbacks(ComponentCallbacks callback) {}
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        return ArtzigBroadcastManager.register(this, receiver, filter, null);
    }
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
        return ArtzigBroadcastManager.register(this, receiver, filter, null);
    }
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, android.os.Handler scheduler) {
        return ArtzigBroadcastManager.register(this, receiver, filter, scheduler);
    }
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, android.os.Handler scheduler, int flags) {
        return ArtzigBroadcastManager.register(this, receiver, filter, scheduler);
    }
    public void unregisterReceiver(BroadcastReceiver receiver) {
        ArtzigBroadcastManager.unregister(receiver);
    }
    public void sendBroadcast(Intent intent) { ArtzigBroadcastManager.send(this, intent); }
    public void unregisterComponentCallbacks(ComponentCallbacks callback) {}
    public SharedPreferences getSharedPreferences(String name, int mode) {
        java.io.File dir = new java.io.File(android.os.Environment.getDataDirectory(), "shared_prefs");
        return android.app.ArtzigSharedPreferences.get(dir, name);
    }
    public SharedPreferences getSharedPreferences(java.io.File file, int mode) {
        return android.app.ArtzigSharedPreferences.get(file.getParentFile(), file.getName());
    }
    public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name, int mode,
            android.database.sqlite.SQLiteDatabase.CursorFactory factory) {
        return android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
                getDatabasePath(name), factory);
    }
    public java.io.File getDatabasePath(String name) {
        java.io.File dir = new java.io.File(android.os.Environment.getDataDirectory(), "databases");
        dir.mkdirs();
        return new java.io.File(dir, name);
    }
    public boolean deleteDatabase(String name) { return getDatabasePath(name).delete(); }
    public String[] databaseList() { return new String[0]; }
}
