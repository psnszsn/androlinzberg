/* artzig shim: android.app.Activity — ContextWrapper + real PhoneWindow host.
 * Grows toward real-Activity semantics as androidx demands (M4..M6). */
package android.app;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import com.android.internal.policy.PhoneWindow;
public class Activity extends ContextWrapper implements Window.Callback,
        KeyEvent.Callback, android.view.LayoutInflater.Factory2 {
    public static final int RESULT_OK = -1, RESULT_CANCELED = 0, RESULT_FIRST_USER = 1;
    static final String FRAGMENTS_TAG = "android:fragments";
    private static final String REQUEST_PERMISSIONS_WHO_PREFIX = "@android:requestPermissions:";
    private Window mWindow;
    private ActivityInfo mActivityInfo = new ActivityInfo();
    private android.os.IBinder mToken;
    private android.os.IBinder mAssistToken = new android.os.Binder();
    private android.os.IBinder mShareableActivityToken;
    private Activity mParent;
    private String mEmbeddedID;
    private int mRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private boolean mChangingConfigurations;
    // AOSP Activity.mFinished is package-private: ActivityThread and the legacy
    // LocalActivityManager lifecycle both own transitions of this flag.
    boolean mFinished;
    private int mResultCode = RESULT_CANCELED;
    private Intent mResultData;
    private String mResultWho;
    private int mResultRequestCode = -1;
    static final class NonConfigurationInstances {
        Object activity;
        java.util.HashMap<String, Object> children;
        FragmentManagerNonConfig fragments;
        android.util.ArrayMap<String, LoaderManager> loaders;
    }
    NonConfigurationInstances mLastNonConfigurationInstances;
    public Activity() { super(null); }
    public void attach(Context base) {
        attach(base, null, mIntent);
    }
    public void attach(Context base, ActivityInfo activityInfo) {
        attach(base, activityInfo, mIntent);
    }
    /** Top-level hosted attach with AOSP's intent-before-attachBaseContext ordering. */
    final void attach(Context base, ActivityInfo activityInfo, Intent intent) {
        attach(base, activityInfo, intent, null, null, tokenFromContext(base), null, null);
    }
    private static android.os.IBinder tokenFromContext(Context base) {
        android.os.IBinder token = base instanceof ContextImpl
                ? ((ContextImpl) base).getActivityToken() : null;
        return token != null ? token : new android.os.Binder();
    }
    /** Hosted subset of AOSP Activity.attach used by ActivityThread's local-activity path. */
    final void attach(Context base, ActivityInfo activityInfo, Intent intent, Activity parent,
            String id, android.os.IBinder token, android.os.IBinder assistToken,
            android.os.IBinder shareableActivityToken) {
        // Pinned AOSP sets mIntent before attachBaseContext because Activity overrides may inspect
        // their launch identity while attaching.
        mIntent = intent != null ? intent : new Intent();
        attachBaseContext(base);
        if (base instanceof ContextImpl) ((ContextImpl) base).setOuterContext(this);
        mFragments.attachHost(null);
        mActivityInfo = activityInfo != null ? new ActivityInfo(activityInfo) : new ActivityInfo();
        mRequestedOrientation = mActivityInfo.screenOrientation;
        mParent = parent;
        mEmbeddedID = id;
        mToken = token;
        if (assistToken != null) mAssistToken = assistToken;
        mShareableActivityToken = shareableActivityToken;
        mWindow = new PhoneWindow(this);
        mWindow.setCallback(this);
        // Real Activity.attach makes the Activity the inflater's private
        // factory. FragmentActivity overrides onCreateView to inflate
        // AndroidX <fragment> tags through its FragmentController.
        mWindow.getLayoutInflater().setPrivateFactory(this);
        mWindow.setWindowManager((WindowManager) base.getSystemService(Context.WINDOW_SERVICE),
                mToken, getClass().getName(), false);
        if (mParent != null) mWindow.setContainer(mParent.getWindow());
    }
    public Application getApplication() {
        return ActivityThread.currentApplication();
    }
    public void setIntent(Intent newIntent) { mIntent = newIntent; }
    public android.view.LayoutInflater getLayoutInflater() { return mWindow.getLayoutInflater(); }
    public Object getLastNonConfigurationInstance() {
        return mLastNonConfigurationInstances != null
                ? mLastNonConfigurationInstances.activity : null;
    }
    public Object onRetainNonConfigurationInstance() { return null; }
    java.util.HashMap<String, Object> onRetainNonConfigurationChildInstances() { return null; }
    java.util.HashMap<String, Object> getLastNonConfigurationChildInstances() {
        return mLastNonConfigurationInstances != null
                ? mLastNonConfigurationInstances.children : null;
    }
    NonConfigurationInstances retainNonConfigurationInstances() {
        Object activity = onRetainNonConfigurationInstance();
        java.util.HashMap<String, Object> children = onRetainNonConfigurationChildInstances();
        FragmentManagerNonConfig fragments = mFragments.retainNestedNonConfig();
        mFragments.doLoaderStart();
        mFragments.doLoaderStop(true);
        android.util.ArrayMap<String, LoaderManager> loaders = mFragments.retainLoaderNonConfig();
        if (activity == null && children == null && fragments == null && loaders == null) return null;
        NonConfigurationInstances result = new NonConfigurationInstances();
        result.activity = activity;
        result.children = children;
        result.fragments = fragments;
        result.loaders = loaders;
        return result;
    }
    void setLastNonConfigurationInstances$artzig(NonConfigurationInstances instances) {
        mLastNonConfigurationInstances = instances;
    }
    public boolean isChangingConfigurations() { return mChangingConfigurations; }
    public void setChangingConfigurations$artzig(boolean changing) {
        mChangingConfigurations = changing;
    }
    public boolean isInMultiWindowMode() { return false; }
    public boolean isInPictureInPictureMode() { return false; }
    public boolean enterPictureInPictureMode(PictureInPictureParams params) {
        // AOSP Activity.enterPictureInPictureMode returns false before validating params when the
        // device has no FEATURE_PICTURE_IN_PICTURE. The desktop host does not advertise that
        // feature, so there is no ActivityTaskManager interaction to adapt here.
        return false;
    }
    @Deprecated public void enterPictureInPictureMode() {
        enterPictureInPictureMode(new PictureInPictureParams.Builder().build());
    }
    public void setPictureInPictureParams(PictureInPictureParams params) {
        // AOSP Activity.setPictureInPictureParams is likewise observationally irrelevant when the
        // device lacks picture-in-picture support.
    }
    public int getMaxNumPictureInPictureActions() { return 3; }
    public boolean onPictureInPictureRequested() { return false; }
    public boolean isTaskRoot() { return true; }
    public void applyOverrideConfiguration(Configuration overrideConfiguration) {}
    public void overridePendingTransition(int enterAnim, int exitAnim) {}
    public void setRequestedOrientation(int requestedOrientation) {
        mRequestedOrientation = requestedOrientation;
    }
    public int getRequestedOrientation() { return mRequestedOrientation; }
    public void setVolumeControlStream(int streamType) {}
    public int getVolumeControlStream() { return 3; }
    public void invalidateOptionsMenu() {}
    public void closeOptionsMenu() {}
    public void openOptionsMenu() {}
    public void recreate() { ActivityThread.recreateActivityInProcess(this); }
    public void finishAffinity() {}
    public boolean shouldShowRequestPermissionRationale(String permission) { return false; }
    public void requestPermissions(String[] permissions, int requestCode) {
        requestPermissions(null, permissions, requestCode);
    }
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {}
    /** AOSP Activity.startActivity -> startActivityForResult -> Instrumentation.execStartActivity
     * (an Activity context launches without FLAG_ACTIVITY_NEW_TASK, unlike ContextImpl). */
    @Override public void startActivity(Intent intent) { startActivityForResult(intent, -1); }
    @Override public void startActivity(Intent intent, Bundle options) { startActivityForResult(intent, -1, options); }
    @Override public void startActivities(Intent[] intents) { for (Intent i : intents) startActivity(i); }
    @Override public void startActivities(Intent[] intents, Bundle options) { startActivities(intents); }
    /** AOSP Activity.mHandler: the main-thread handler package-private helpers post to (KeyguardManager). */
    final android.os.Handler mHandler = new android.os.Handler();
    /** AOSP Activity.mFragments: stock legacy fragments hosted behind the Activity seam. */
    final FragmentController mFragments = FragmentController.createController(new HostCallbacks());
    /** AOSP Activity.getLaunchedFromPackage: the activity task manager knows the launcher; this host has no record. */
    public String getLaunchedFromPackage() { return null; }
    /** AOSP Activity.requestWindowFeature: a feature request on the activity window. */
    public final boolean requestWindowFeature(int featureId) { return getWindow().requestFeature(featureId); }
    /** AOSP Activity.onActivityResult: results reach the activity through dispatchActivityResult. */
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {}
    public void startActivityForResult(Intent intent, int requestCode) { startActivityForResult(intent, requestCode, null); }
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        if (mParent != null) {
            mParent.startActivityFromChild(this, intent, requestCode, options);
            return;
        }
        startActivityForResult((String) null, intent, requestCode, options);
    }
    /** AOSP child launch routing; the embedded id is the result recipient in the parent. */
    @Deprecated public void startActivityFromChild(Activity child, Intent intent, int requestCode) {
        startActivityFromChild(child, intent, requestCode, null);
    }
    @Deprecated public void startActivityFromChild(Activity child, Intent intent, int requestCode,
            Bundle options) {
        startActivityForResult(child != null ? child.mEmbeddedID : null, intent, requestCode,
                options);
    }
    public void startActivityForResult(String who, Intent intent, int requestCode, Bundle options) {
        Instrumentation instrumentation = ActivityThread.currentActivityThread().getInstrumentation();
        if (who == null) {
            instrumentation.execStartActivity(this,
                    ActivityThread.currentActivityThread().getApplicationThread().asBinder(),
                    getActivityToken(), this, intent, requestCode, options);
        } else {
            instrumentation.execStartActivity(this,
                    ActivityThread.currentActivityThread().getApplicationThread().asBinder(),
                    getActivityToken(), who, intent, requestCode, options);
        }
    }
    /** Activity IntentSender dispatch requires the still-unhosted activity task manager seam. */
    public void startIntentSenderForResult(android.content.IntentSender intent, int requestCode,
            Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags)
            throws android.content.IntentSender.SendIntentException {
        startIntentSenderForResult(intent, requestCode, fillInIntent, flagsMask, flagsValues, extraFlags, null);
    }
    public void startIntentSenderForResult(android.content.IntentSender intent, int requestCode,
            Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags, Bundle options)
            throws android.content.IntentSender.SendIntentException {
        startIntentSenderForResult(null, intent, requestCode, fillInIntent,
                flagsMask, flagsValues, extraFlags, options);
    }
    /** AOSP convertToTranslucent: no translucent conversion without the activity task manager. */
    public interface TranslucentConversionListener { void onTranslucentConversionComplete(boolean drawComplete); }
    public boolean convertToTranslucent(TranslucentConversionListener callback, ActivityOptions options) {
        if (callback != null) callback.onTranslucentConversionComplete(false);
        return false;
    }
    public void convertFromTranslucent() {}
    private void startIntentSenderForResult(String who, android.content.IntentSender intent,
            int requestCode, Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags,
            Bundle options) throws android.content.IntentSender.SendIntentException {
        throw new android.content.IntentSender.SendIntentException(
                "Activity IntentSender launches are not hosted without ActivityTaskManager");
    }
    public void setResult(int resultCode) { setResult(resultCode, null); }
    public void setResult(int resultCode, Intent data) {
        mResultCode = resultCode;
        mResultData = data;
    }
    void setResultTarget$artzig(String who, int requestCode) {
        mResultWho = who;
        mResultRequestCode = requestCode;
    }
    String getResultWho$artzig() { return mResultWho; }
    int getResultRequestCode$artzig() { return mResultRequestCode; }
    int getResultCode$artzig() { return mResultCode; }
    Intent getResultData$artzig() { return mResultData; }
    void dispatchActivityResult(String who, int requestCode, int resultCode, Intent data) {
        mFragments.noteStateNotSaved();
        if (who == null) {
            onActivityResult(requestCode, resultCode, data);
            return;
        }
        Fragment fragment = mFragments.findFragmentByWho(who);
        if (fragment != null) fragment.onActivityResult(requestCode, resultCode, data);
    }
    private void requestPermissions(String who, String[] permissions, int requestCode) {
        String[] requested = permissions.clone();
        int[] results = new int[requested.length];
        for (int i = 0; i < requested.length; i++) {
            results[i] = checkSelfPermission(requested[i]);
        }
        mHandler.post(() -> {
            mFragments.noteStateNotSaved();
            if (who == null) {
                onRequestPermissionsResult(requestCode, requested, results);
            } else {
                String fragmentWho = who.startsWith(REQUEST_PERMISSIONS_WHO_PREFIX)
                        ? who.substring(REQUEST_PERMISSIONS_WHO_PREFIX.length()) : who;
                Fragment fragment = mFragments.findFragmentByWho(fragmentWho);
                if (fragment != null) {
                    fragment.onRequestPermissionsResult(requestCode, requested, results);
                }
            }
        });
    }
    public boolean isActivityTransitionRunning() { return false; }
    public void reportFullyDrawn() {}
    public void postponeEnterTransition() {}
    public void startPostponedEnterTransition() {}
    public android.net.Uri getReferrer() { return null; }
    public int getLaunchedFromUid() { return android.os.Process.myUid(); }
    public String getCallingPackage() { return null; }
    public android.content.ComponentName getCallingActivity() { return null; }
    public void setShowWhenLocked(boolean showWhenLocked) {}
    public void setTurnScreenOn(boolean turnScreenOn) {}
    public boolean hasWindowFocus() { return true; }
    public final void setDefaultKeyMode(int mode) {}
    public void setTaskDescription(Object taskDescription) {}
    public boolean isLaunchedFromBubble() { return false; }
    public boolean navigateUpTo(Intent upIntent) { return false; }
    public Intent getParentActivityIntent() { return null; }
    public boolean shouldUpRecreateTask(Intent targetIntent) { return false; }
    public boolean onNavigateUp() { return false; }
    public ActionBar getActionBar() { return null; }
    public android.app.FragmentManager getFragmentManager() {
        return mFragments.getFragmentManager();
    }
    public android.app.LoaderManager getLoaderManager() {
        return mFragments.getLoaderManager();
    }
    public boolean releaseInstance() { return false; }
    private Intent mIntent = new Intent();
    private android.window.OnBackInvokedDispatcher mBackDispatcher;
    public android.window.OnBackInvokedDispatcher getOnBackInvokedDispatcher() {
        if (mBackDispatcher == null) {
            mBackDispatcher = new android.window.OnBackInvokedDispatcher() {
                @Override public void registerOnBackInvokedCallback(int priority,
                        android.window.OnBackInvokedCallback callback) {}
                @Override public void unregisterOnBackInvokedCallback(
                        android.window.OnBackInvokedCallback callback) {}
            };
        }
        return mBackDispatcher;
    }
    private final java.util.ArrayList<Application.ActivityLifecycleCallbacks> mLifecycleCallbacks =
            new java.util.ArrayList<>();
    public void registerActivityLifecycleCallbacks(Application.ActivityLifecycleCallbacks callback) {
        synchronized (mLifecycleCallbacks) { mLifecycleCallbacks.add(callback); }
    }
    public void unregisterActivityLifecycleCallbacks(Application.ActivityLifecycleCallbacks callback) {
        synchronized (mLifecycleCallbacks) { mLifecycleCallbacks.remove(callback); }
    }
    /** The activity-level callbacks (AOSP Activity.mActivityLifecycleCallbacks); the
     * Application's are dispatched through Application.dispatchActivity*. */
    private Application.ActivityLifecycleCallbacks[] collectLifecycleCallbacks() {
        synchronized (mLifecycleCallbacks) {
            return mLifecycleCallbacks.toArray(new Application.ActivityLifecycleCallbacks[0]);
        }
    }
    private Application appOrNull() { return getApplication(); }
    public void registerComponentCallbacks(android.content.ComponentCallbacks callback) {}
    public void unregisterComponentCallbacks(android.content.ComponentCallbacks callback) {}
    private int mThemeRes;
    private android.content.res.Resources.Theme mTheme;
    @Override public void setTheme(int resid) {
        if (mThemeRes != resid) { mThemeRes = resid; mTheme = null; }
        // AOSP Activity.setTheme() also updates PhoneWindow so decor features such as
        // windowNoTitle and windowActionBar are resolved from the Activity theme.
        if (mWindow != null) mWindow.setTheme(resid);
    }
    @Override public int getThemeResId() { return mThemeRes; }
    @Override public android.content.res.Resources.Theme getTheme() {
        if (mThemeRes == 0) return super.getTheme();
        if (mTheme == null) {
            mTheme = getResources().newTheme();
            android.content.res.Resources.Theme base = super.getTheme();
            if (base != null) mTheme.setTo(base);
            mTheme.applyStyle(mThemeRes, true);
        }
        return mTheme;
    }
    private android.view.LayoutInflater mInflater;
    @Override public Object getSystemService(String name) {
        if (Context.LAYOUT_INFLATER_SERVICE.equals(name)) {
            if (mInflater == null) {
                mInflater = android.view.LayoutInflater.from(getBaseContext()).cloneInContext(this);
            }
            return mInflater;
        }
        if (Context.WINDOW_SERVICE.equals(name)) return getWindowManager();
        return super.getSystemService(name);
    }
    private android.view.MenuInflater mMenuInflater;
    public android.view.MenuInflater getMenuInflater() {
        if (mMenuInflater == null) mMenuInflater = new android.view.MenuInflater(this);
        return mMenuInflater;
    }
    public android.os.IBinder getActivityToken() {
        return mParent != null ? mParent.getActivityToken() : mToken;
    }
    public android.os.IBinder getShareableActivityToken() {
        return mParent != null ? mParent.getShareableActivityToken() : mShareableActivityToken;
    }
    public final Activity getParent() { return mParent; }
    public final boolean isChild() { return mParent != null; }
    public Window getWindow() { return mWindow; }
    public WindowManager getWindowManager() { return mWindow != null ? mWindow.getWindowManager() : null; }
    public boolean isResumed() { return true; }
    public boolean isFinishing() { return mFinished; }
    public boolean isDestroyed() { return false; }
    public void finish() {
        if (mParent != null) {
            mParent.finishFromChild(this);
        } else {
            mFinished = true;
            ActivityThread.finishActivityInProcess(this);
        }
    }
    /** AOSP's default finishes the parent; ActivityGroup subclasses may route to their manager. */
    @Deprecated public void finishFromChild(Activity child) { finish(); }
    public void finishAndRemoveTask() {}
    public boolean moveTaskToBack(boolean nonRoot) { return false; }
    public ActivityInfo getActivityInfo() { return mActivityInfo; }
    public void runOnUiThread(Runnable action) { action.run(); }
    public int getTaskId() { return 0; }
    /** AOSP: the assist token the activity manager issued; a process-local binder here. */
    public final android.os.IBinder getAssistToken() {
        return mParent != null ? mParent.getAssistToken() : mAssistToken;
    }
    private com.android.internal.app.IVoiceInteractor mVoiceInteractor;
    public void setVoiceInteractor(com.android.internal.app.IVoiceInteractor voiceInteractor) { mVoiceInteractor = voiceInteractor; }
    public VoiceInteractor getVoiceInteractor() { return null; }
    public android.content.ComponentName getComponentName() {
        return new android.content.ComponentName("dev.artzig", getClass().getName());
    }
    public void addDumpable(Object dumpable) {}
    @Override public View onCreateView(String name, Context context,
            android.util.AttributeSet attrs) { return null; }
    @Override public View onCreateView(View parent, String name, Context context,
            android.util.AttributeSet attrs) {
        if (!"fragment".equals(name)) return onCreateView(name, context, attrs);
        return mFragments.onCreateView(parent, name, context, attrs);
    }
    public View getCurrentFocus() { return mWindow != null ? mWindow.getCurrentFocus() : null; }
    public Intent getIntent() { return mIntent; }
    public View getDecorView() { return mWindow.getDecorView(); }
    public void setContentView(View view) {
        mWindow.setContentView(view);
    }
    public void setContentView(int layoutResID) { mWindow.setContentView(layoutResID); }
    public void setContentView(View view, ViewGroup.LayoutParams params) { mWindow.setContentView(view, params); }
    public <T extends View> T findViewById(int id) { return mWindow.findViewById(id); }
    public final <T extends View> T requireViewById(int id) {
        T view = findViewById(id);
        if (view == null) throw new IllegalArgumentException("ID does not reference a View");
        return view;
    }
    private CharSequence mTitle = "";
    public CharSequence getTitle() { return mTitle; }
    public String getLocalClassName() {
        String className = getClass().getName();
        String packageName = getPackageName();
        return className.startsWith(packageName + ".")
                ? className.substring(packageName.length() + 1) : className;
    }
    public android.window.SplashScreen getSplashScreen() {
        return new android.window.SplashScreen() {
            @Override public void setSplashScreenTheme(int themeId) {}
            @Override public void setOnExitAnimationListener(
                    android.window.SplashScreen.OnExitAnimationListener listener) {}
        };
    }
    public int getTitleColor() { return 0; }
    public void setTitleColor(int textColor) {}
    public void setTitle(CharSequence title) { mTitle = title; if (mWindow != null) mWindow.setTitle(title); }
    public void setTitle(int titleId) {}
    protected void onCreate(Bundle savedInstanceState) {
        if (mLastNonConfigurationInstances != null) {
            mFragments.restoreLoaderNonConfig(mLastNonConfigurationInstances.loaders);
        }
        if (savedInstanceState != null) {
            mFragments.restoreAllState(savedInstanceState.getParcelable(FRAGMENTS_TAG),
                    mLastNonConfigurationInstances != null
                            ? mLastNonConfigurationInstances.fragments
                            : (FragmentManagerNonConfig) null);
        }
        mFragments.dispatchCreate();
    }
    protected void onPostCreate(Bundle savedInstanceState) {}
    /** AOSP Activity.onRestart; dispatched before a stopped activity starts again. */
    protected void onRestart() {}
    protected void onStart() { mFragments.doLoaderStart(); }
    protected void onResume() {}
    protected void onPostResume() {}
    protected void onPause() {}
    protected void onStop() {}
    protected void onDestroy() {}
    /** AOSP Activity.onNewIntent; used when LocalActivityManager reuses a child activity. */
    protected void onNewIntent(Intent intent) {}
    protected void onSaveInstanceState(Bundle outState) {
        android.os.Parcelable fragmentState = mFragments.saveAllState();
        if (fragmentState != null) outState.putParcelable(FRAGMENTS_TAG, fragmentState);
    }
    protected void onRestoreInstanceState(Bundle savedInstanceState) {}
    public final void performCreate(Bundle state) {
        Application app = appOrNull();
        if (app != null) app.dispatchActivityPreCreated(this, state);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPreCreated(this, state);
        onCreate(state);
        if (app != null) app.dispatchActivityCreated(this, state);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityCreated(this, state);
        mFragments.dispatchActivityCreated();
        if (app != null) app.dispatchActivityPostCreated(this, state);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPostCreated(this, state);
    }
    /** AOSP invokes onPostCreate after start and saved-state restoration. */
    public final void performPostCreate(Bundle state) { onPostCreate(state); }
    public final void performStart() {
        Application app = appOrNull();
        if (app != null) app.dispatchActivityPreStarted(this);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPreStarted(this);
        mFragments.noteStateNotSaved();
        mFragments.execPendingActions();
        onStart();
        if (app != null) app.dispatchActivityStarted(this);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityStarted(this);
        mFragments.dispatchStart();
        mFragments.reportLoaderStart();
        if (app != null) app.dispatchActivityPostStarted(this);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPostStarted(this);
    }
    /** AOSP Activity.performRestart, limited to the lifecycle state owned by ActivityThread. */
    final void performRestart(boolean start) {
        mFragments.noteStateNotSaved();
        onRestart();
        if (start) performStart();
    }
    public final void performResume() {
        Application app = appOrNull();
        if (app != null) app.dispatchActivityPreResumed(this);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPreResumed(this);
        mFragments.execPendingActions();
        mLastNonConfigurationInstances = null;
        onResume();
        if (app != null) app.dispatchActivityResumed(this);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityResumed(this);
        mFragments.dispatchResume();
        mFragments.execPendingActions();
        onPostResume();
        if (app != null) app.dispatchActivityPostResumed(this);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPostResumed(this);
    }
    public final void performPause() {
        Application app = appOrNull();
        if (app != null) app.dispatchActivityPrePaused(this);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPrePaused(this);
        mFragments.dispatchPause();
        onPause();
        if (app != null) app.dispatchActivityPaused(this);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPaused(this);
        if (app != null) app.dispatchActivityPostPaused(this);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPostPaused(this);
    }
    public final void performStop() {
        Application app = appOrNull();
        mFragments.doLoaderStop(mChangingConfigurations);
        if (app != null) app.dispatchActivityPreStopped(this);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPreStopped(this);
        mFragments.dispatchStop();
        onStop();
        if (app != null) app.dispatchActivityStopped(this);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityStopped(this);
        if (app != null) app.dispatchActivityPostStopped(this);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPostStopped(this);
    }
    public final void performDestroy() {
        Application app = appOrNull();
        if (app != null) app.dispatchActivityPreDestroyed(this);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPreDestroyed(this);
        mFragments.dispatchDestroy();
        onDestroy();
        mFragments.doLoaderDestroy();
        if (app != null) app.dispatchActivityDestroyed(this);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityDestroyed(this);
        if (app != null) app.dispatchActivityPostDestroyed(this);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPostDestroyed(this);
    }
    /** AOSP Activity.performNewIntent dispatches without replacing the Activity's current Intent. */
    final void performNewIntent(Intent intent) { onNewIntent(intent); }
    public final void performSaveInstanceState(Bundle outState) {
        Application app = appOrNull();
        if (app != null) app.dispatchActivityPreSaveInstanceState(this, outState);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPreSaveInstanceState(this, outState);
        onSaveInstanceState(outState);
        if (app != null) app.dispatchActivitySaveInstanceState(this, outState);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivitySaveInstanceState(this, outState);
        if (app != null) app.dispatchActivityPostSaveInstanceState(this, outState);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPostSaveInstanceState(this, outState);
    }
    public final void performRestoreInstanceState(Bundle savedInstanceState) {
        onRestoreInstanceState(savedInstanceState);
    }
    // ---- Window.Callback ----
    @Override public boolean dispatchKeyEvent(KeyEvent event) { return mWindow.superDispatchKeyEvent(event); }
    @Override public boolean dispatchKeyShortcutEvent(KeyEvent event) { return mWindow.superDispatchKeyShortcutEvent(event); }
    @Override public boolean dispatchTouchEvent(MotionEvent ev) { return mWindow.superDispatchTouchEvent(ev); }
    @Override public boolean dispatchTrackballEvent(MotionEvent ev) { return mWindow.superDispatchTrackballEvent(ev); }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent ev) { return mWindow.superDispatchGenericMotionEvent(ev); }
    @Override public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) { return false; }
    @Override public View onCreatePanelView(int featureId) { return null; }
    @Override public boolean onCreatePanelMenu(int featureId, Menu menu) {
        if (featureId != Window.FEATURE_OPTIONS_PANEL) return false;
        boolean show = onCreateOptionsMenu(menu);
        return mFragments.dispatchCreateOptionsMenu(menu, getMenuInflater()) || show;
    }
    @Override public boolean onPreparePanel(int featureId, View view, Menu menu) {
        if (featureId != Window.FEATURE_OPTIONS_PANEL) return true;
        boolean show = onPrepareOptionsMenu(menu);
        return mFragments.dispatchPrepareOptionsMenu(menu) || show;
    }
    @Override public boolean onMenuOpened(int featureId, Menu menu) { return true; }
    @Override public boolean onMenuItemSelected(int featureId, MenuItem item) {
        if (featureId == Window.FEATURE_OPTIONS_PANEL) {
            return onOptionsItemSelected(item) || mFragments.dispatchOptionsItemSelected(item);
        }
        if (featureId == Window.FEATURE_CONTEXT_MENU) {
            return onContextItemSelected(item) || mFragments.dispatchContextItemSelected(item);
        }
        return false;
    }
    public boolean onOptionsItemSelected(MenuItem item) { return false; }
    public boolean onContextItemSelected(MenuItem item) { return false; }
    public boolean onCreateOptionsMenu(Menu menu) { return true; }
    public boolean onPrepareOptionsMenu(Menu menu) { return true; }
    public void onOptionsMenuClosed(Menu menu) {}
    public void onContextMenuClosed(Menu menu) {}
    @Override public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {}
    @Override public void onContentChanged() {}
    @Override public void onWindowFocusChanged(boolean hasFocus) {}
    @Override public void onAttachedToWindow() {}
    @Override public void onDetachedFromWindow() {}
    @Override public void onPanelClosed(int featureId, Menu menu) {
        if (featureId == Window.FEATURE_OPTIONS_PANEL) {
            mFragments.dispatchOptionsMenuClosed(menu);
            onOptionsMenuClosed(menu);
        } else if (featureId == Window.FEATURE_CONTEXT_MENU) {
            onContextMenuClosed(menu);
        }
    }
    @Override public boolean onSearchRequested() { return false; }
    @Override public boolean onSearchRequested(android.view.SearchEvent searchEvent) { return false; }
    @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) { return null; }
    @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) { return null; }
    @Override public void onActionModeStarted(ActionMode mode) {}
    @Override public void onActionModeFinished(ActionMode mode) {}
    // ---- KeyEvent.Callback ----
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) { return false; }
    @Override public boolean onKeyLongPress(int keyCode, KeyEvent event) { return false; }
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) { return false; }
    @Override public boolean onKeyMultiple(int keyCode, int count, KeyEvent event) { return false; }
    public void onBackPressed() {
        FragmentManager manager = mFragments.getFragmentManager();
        if (!manager.isStateSaved() && manager.popBackStackImmediate()) return;
        finish();
    }
    public void onConfigurationChanged(Configuration newConfig) {
        mFragments.dispatchConfigurationChanged(newConfig);
        if (mWindow != null) {
            mWindow.onConfigurationChanged(newConfig);
        }
    }
    public void onLowMemory() { mFragments.dispatchLowMemory(); }
    public void onTrimMemory(int level) { mFragments.dispatchTrimMemory(level); }
    public void onCreateContextMenu(android.view.ContextMenu menu, View view,
            android.view.ContextMenu.ContextMenuInfo menuInfo) {}
    public void onAttachFragment(Fragment fragment) {}

    /** Adapted from AOSP Activity.HostCallbacks; the desktop Activity owns the real
     * FragmentManager but replaces task-manager launches with its in-process host paths. */
    class HostCallbacks extends FragmentHostCallback<Activity> {
        HostCallbacks() { super(Activity.this); }

        @Override public boolean onShouldSaveFragmentState(Fragment fragment) {
            return !isFinishing();
        }

        @Override public android.view.LayoutInflater onGetLayoutInflater() {
            android.view.LayoutInflater inflater = Activity.this.getLayoutInflater();
            return onUseFragmentManagerInflaterFactory()
                    ? inflater.cloneInContext(Activity.this) : inflater;
        }

        @Override public boolean onUseFragmentManagerInflaterFactory() {
            return getApplicationInfo().targetSdkVersion >= android.os.Build.VERSION_CODES.LOLLIPOP;
        }

        @Override public Activity onGetHost() { return Activity.this; }
        @Override public void onInvalidateOptionsMenu() { Activity.this.invalidateOptionsMenu(); }

        @Override public void onStartActivityFromFragment(Fragment fragment, Intent intent,
                int requestCode, Bundle options) {
            Activity.this.startActivityForResult(fragment.mWho, intent, requestCode, options);
        }

        @Override public void onStartActivityAsUserFromFragment(Fragment fragment, Intent intent,
                int requestCode, Bundle options, android.os.UserHandle user) {
            Activity.this.startActivityForResult(fragment.mWho, intent, requestCode, options);
        }

        @Override public void onStartIntentSenderFromFragment(Fragment fragment,
                android.content.IntentSender intent, int requestCode, Intent fillInIntent,
                int flagsMask, int flagsValues, int extraFlags, Bundle options)
                throws android.content.IntentSender.SendIntentException {
            Activity.this.startIntentSenderForResult(fragment.mWho, intent, requestCode,
                    fillInIntent, flagsMask, flagsValues, extraFlags, options);
        }

        @Override public void onRequestPermissionsFromFragment(Fragment fragment,
                String[] permissions, int requestCode) {
            Activity.this.requestPermissions(REQUEST_PERMISSIONS_WHO_PREFIX + fragment.mWho,
                    permissions, requestCode);
        }

        @Override public boolean onHasWindowAnimations() { return getWindow() != null; }
        @Override public int onGetWindowAnimations() {
            Window window = getWindow();
            return window == null ? 0 : window.getAttributes().windowAnimations;
        }
        @Override public void onAttachFragment(Fragment fragment) {
            Activity.this.onAttachFragment(fragment);
        }
        @Override public <T extends View> T onFindViewById(int id) {
            return Activity.this.findViewById(id);
        }
        @Override public boolean onHasView() {
            Window window = getWindow();
            return window != null && window.peekDecorView() != null;
        }
    }
    public Object getSystemService$override(String name) { return null; }
}
