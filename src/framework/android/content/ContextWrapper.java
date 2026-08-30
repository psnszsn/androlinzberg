/* artzig shim: android.content.ContextWrapper — delegates the shim-Context surface. */
package android.content;
import android.content.res.AssetManager;
import android.content.res.Resources;
public class ContextWrapper extends Context {
    Context mBase;
    public ContextWrapper(Context base) { mBase = base; }
    protected void attachBaseContext(Context base) {
        if (mBase != null) throw new IllegalStateException("Base context already set");
        mBase = base;
    }
    public Context getBaseContext() { return mBase; }
    @Override public Resources getResources() { return mBase.getResources(); }
    @Override public AssetManager getAssets() { return mBase.getAssets(); }
    @Override public Resources.Theme getTheme() { return mBase.getTheme(); }
    @Override public String getPackageName() { return mBase.getPackageName(); }
    @Override public ClassLoader getClassLoader() { return mBase.getClassLoader(); }
    @Override public Context getApplicationContext() { return mBase.getApplicationContext(); }
    @Override public Object getSystemService(String name) { return mBase.getSystemService(name); }
    @Override public String getSystemServiceName(Class<?> serviceClass) { return mBase.getSystemServiceName(serviceClass); }
    @Override public android.content.pm.ApplicationInfo getApplicationInfo() { return mBase.getApplicationInfo(); }
    @Override public android.content.pm.PackageManager getPackageManager() { return mBase.getPackageManager(); }
    @Override public ContentResolver getContentResolver() { return mBase.getContentResolver(); }
    // AOSP ContextWrapper delegates permission checks to its base context; Activity and
    // Application would otherwise observe Context's default denial instead of package state.
    @Override public int checkPermission(String permission, int pid, int uid) {
        return mBase.checkPermission(permission, pid, uid);
    }
    @Override public int checkPermission(String permission, int pid, int uid, Object callerToken) {
        return mBase.checkPermission(permission, pid, uid, callerToken);
    }
    @Override public int checkCallingPermission(String permission) {
        return mBase.checkCallingPermission(permission);
    }
    @Override public int checkCallingOrSelfPermission(String permission) {
        return mBase.checkCallingOrSelfPermission(permission);
    }
    @Override public int checkSelfPermission(String permission) {
        return mBase.checkSelfPermission(permission);
    }
    @Override public void enforcePermission(String permission, int pid, int uid, String message) {
        mBase.enforcePermission(permission, pid, uid, message);
    }
    @Override public void enforceCallingOrSelfPermission(String permission, String message) {
        mBase.enforceCallingOrSelfPermission(permission, message);
    }
    @Override public boolean canLoadUnsafeResources() { return mBase.canLoadUnsafeResources(); }
    @Override public Context createConfigurationContext(
            android.content.res.Configuration overrideConfiguration) {
        return mBase.createConfigurationContext(overrideConfiguration);
    }
    public void setTheme(int resid) { mBase.setTheme(resid); }
    public int getThemeResId() { return 0; }
}
