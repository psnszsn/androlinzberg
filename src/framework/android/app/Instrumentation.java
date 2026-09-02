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

    /** AOSP Instrumentation.ActivityResult. */
    public static final class ActivityResult {
        private final int mResultCode;
        private final android.content.Intent mResultData;
        public ActivityResult(int resultCode, android.content.Intent resultData) {
            mResultCode = resultCode;
            mResultData = resultData;
        }
        public int getResultCode() { return mResultCode; }
        public android.content.Intent getResultData() { return mResultData; }
    }

    /** AOSP Instrumentation.execStartActivity (ContextImpl/Activity.startActivity): the launch
     * goes to the in-process activity runtime instead of ActivityTaskManager; no monitors. */
    public ActivityResult execStartActivity(Context who, android.os.IBinder contextThread,
            android.os.IBinder token, Activity target, android.content.Intent intent, int requestCode,
            android.os.Bundle options) {
        ActivityThread.startActivityInProcess(intent);
        return null;
    }
    public ActivityResult execStartActivity(Context who, android.os.IBinder contextThread,
            android.os.IBinder token, String target, android.content.Intent intent, int requestCode,
            android.os.Bundle options) {
        ActivityThread.startActivityInProcess(intent);
        return null;
    }

    public void execStartActivities(Context who, android.os.IBinder contextThread, android.os.IBinder token,
            Activity target, android.content.Intent[] intents, android.os.Bundle options) {
        for (android.content.Intent intent : intents) ActivityThread.startActivityInProcess(intent);
    }
    public int execStartActivitiesAsUser(Context who, android.os.IBinder contextThread, android.os.IBinder token,
            Activity target, android.content.Intent[] intents, android.os.Bundle options, int userId) {
        execStartActivities(who, contextThread, token, target, intents, options);
        return ActivityManager.START_SUCCESS;
    }

    /** AOSP Instrumentation.checkStartActivityResult. */
    public static void checkStartActivityResult(int res, Object intent) {
        if (!ActivityManager.isStartResultFatalError(res)) return;
        switch (res) {
            case ActivityManager.START_INTENT_NOT_RESOLVED:
            case ActivityManager.START_CLASS_NOT_FOUND:
                if (intent instanceof android.content.Intent && ((android.content.Intent) intent).getComponent() != null)
                    throw new android.content.ActivityNotFoundException(
                            "Unable to find explicit activity class "
                            + ((android.content.Intent) intent).getComponent().toShortString()
                            + "; have you declared this activity in your AndroidManifest.xml"
                            + ", or does your intent not match its declared <intent-filter>?");
                throw new android.content.ActivityNotFoundException("No Activity found to handle " + intent);
            case ActivityManager.START_PERMISSION_DENIED:
                throw new SecurityException("Not allowed to start activity " + intent);
            case ActivityManager.START_FORWARD_AND_REQUEST_CONFLICT:
                throw new android.util.AndroidRuntimeException("FORWARD_RESULT_FLAG used while also requesting a result");
            case ActivityManager.START_NOT_ACTIVITY:
                throw new IllegalArgumentException("PendingIntent is not an activity");
            case ActivityManager.START_NOT_VOICE_COMPATIBLE:
                throw new SecurityException("Starting under voice control not allowed for: " + intent);
            case ActivityManager.START_VOICE_NOT_ACTIVE_SESSION:
                throw new IllegalStateException("Session calling startVoiceActivity does not match active session");
            case ActivityManager.START_VOICE_HIDDEN_SESSION:
                throw new IllegalStateException("Cannot start voice activity on a hidden session");
            case ActivityManager.START_ASSISTANT_NOT_ACTIVE_SESSION:
                throw new IllegalStateException("Session calling startAssistantActivity does not match active session");
            case ActivityManager.START_ASSISTANT_HIDDEN_SESSION:
                throw new IllegalStateException("Cannot start assistant activity on a hidden session");
            case ActivityManager.START_CANCELED:
                throw new android.util.AndroidRuntimeException("Activity could not be started for " + intent);
            default:
                throw new android.util.AndroidRuntimeException("Unknown error code " + res + " when starting " + intent);
        }
    }

    private AppComponentFactory getFactory(String pkg) {
        if (pkg == null) return AppComponentFactory.DEFAULT;
        if (mThread == null) return AppComponentFactory.DEFAULT;
        LoadedApk apk = mThread.peekPackageInfo(pkg, true);
        return apk != null ? apk.getAppFactory() : AppComponentFactory.DEFAULT;
    }
}
