/* artzig shim: android.app.Application — ContextWrapper-based (full lifecycle in M4). */
package android.app;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
public class Application extends android.content.ContextWrapper {
    private final ApplicationInfo mInfo = new ApplicationInfo();
    public Application() { super(null); }
    public void attach(Context base) { attachBaseContext(base); }
    @Override public ApplicationInfo getApplicationInfo() { return mInfo; }
    public void onCreate() {}
    public void onTerminate() {}
    public void onConfigurationChanged(Configuration newConfig) {}
    public void onLowMemory() {}
    public interface ActivityLifecycleCallbacks {
        void onActivityCreated(Activity activity, android.os.Bundle savedInstanceState);
        void onActivityStarted(Activity activity);
        void onActivityResumed(Activity activity);
        void onActivityPaused(Activity activity);
        void onActivityStopped(Activity activity);
        void onActivitySaveInstanceState(Activity activity, android.os.Bundle outState);
        void onActivityDestroyed(Activity activity);
        default void onActivityPreCreated(Activity activity, android.os.Bundle savedInstanceState) {}
        default void onActivityPostCreated(Activity activity, android.os.Bundle savedInstanceState) {}
        default void onActivityPreStarted(Activity activity) {}
        default void onActivityPostStarted(Activity activity) {}
        default void onActivityPreResumed(Activity activity) {}
        default void onActivityPostResumed(Activity activity) {}
        default void onActivityPrePaused(Activity activity) {}
        default void onActivityPostPaused(Activity activity) {}
        default void onActivityPreStopped(Activity activity) {}
        default void onActivityPostStopped(Activity activity) {}
        default void onActivityPreSaveInstanceState(Activity activity, android.os.Bundle outState) {}
        default void onActivityPostSaveInstanceState(Activity activity, android.os.Bundle outState) {}
        default void onActivityPreDestroyed(Activity activity) {}
        default void onActivityPostDestroyed(Activity activity) {}
    }
    public interface OnProvideAssistDataListener {
        void onProvideAssistData(Activity activity, android.os.Bundle data);
    }
    private final java.util.ArrayList<ActivityLifecycleCallbacks> mLifecycleCallbacks = new java.util.ArrayList<>();
    public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
        synchronized (mLifecycleCallbacks) { mLifecycleCallbacks.add(callback); }
    }
    public void unregisterActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
        synchronized (mLifecycleCallbacks) { mLifecycleCallbacks.remove(callback); }
    }
    public ActivityLifecycleCallbacks[] collectActivityLifecycleCallbacks() {
        synchronized (mLifecycleCallbacks) {
            return mLifecycleCallbacks.toArray(new ActivityLifecycleCallbacks[0]);
        }
    }
    public void registerOnProvideAssistDataListener(OnProvideAssistDataListener callback) {}
    public void unregisterOnProvideAssistDataListener(OnProvideAssistDataListener callback) {}
}
