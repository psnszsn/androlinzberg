/* Hosted adaptation of AOSP android.app.Instrumentation: the application-
 * creation half LoadedApk drives (newApplication/callApplicationOnCreate/
 * onException) and the process-facing basics. The activity half (execStart
 * Activity, callActivityOn*, results, monitors, UiAutomation) needs the real
 * Activity/ActivityThread internals and arrives with the activity-core
 * milestone; instrumentation test runners are not hosted. */
package android.app;

import android.content.Context;

public class Instrumentation {
    private ActivityThread mThread;
    private Context mInstrContext;
    private Context mAppContext;

    public Instrumentation() {}

    /*package*/ final void basicInit(ActivityThread thread) { mThread = thread; }

    public final void basicInit(Context instrContext, Context appContext, Object ui) {
        mInstrContext = instrContext;
        mAppContext = appContext;
    }

    /** No instrumentation runner ever drives this process. */
    public boolean isInstrumenting() { return false; }

    public Context getContext() { return mInstrContext; }
    public Context getTargetContext() { return mAppContext; }

    /** AOSP: an uncaught component exception is offered to the runner first; none here. */
    public boolean onException(Object obj, Throwable e) { return false; }

    /** AOSP Instrumentation.newApplication(ClassLoader, String, Context): the package's
     * AppComponentFactory instantiates, then Application.attach. */
    public Application newApplication(ClassLoader cl, String className, Context context)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        Application app = getFactory(context.getPackageName()).instantiateApplication(cl, className);
        app.attach(context);
        return app;
    }

    public static Application newApplication(Class<?> clazz, Context context)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        Application app = (Application) clazz.newInstance();
        app.attach(context);
        return app;
    }

    public void callApplicationOnCreate(Application app) { app.onCreate(); }

    private AppComponentFactory getFactory(String pkg) {
        if (pkg == null) return AppComponentFactory.DEFAULT;
        if (mThread == null) return AppComponentFactory.DEFAULT;
        LoadedApk apk = mThread.peekPackageInfo(pkg, true);
        return apk != null ? apk.getAppFactory() : AppComponentFactory.DEFAULT;
    }
}
