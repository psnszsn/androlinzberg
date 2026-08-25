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
    @Override public boolean canLoadUnsafeResources() { return mBase.canLoadUnsafeResources(); }
    public void setTheme(int resid) { mBase.setTheme(resid); }
    public int getThemeResId() { return 0; }
}
