/* Hosted adaptation of pinned AOSP Activity. Direct compilation still requires
 * the voice/audio and window-controller closure. Host-only attach overloads
 * supply the resolved launch identity used by the standard attach entry point. */
package android.app;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.annotation.NonNull;
import android.window.SplashScreen;
import android.app.ActivityOptions.SceneTransitionInfo;
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
public class Activity extends ContextThemeWrapper implements Window.Callback,
        KeyEvent.Callback, android.view.LayoutInflater.Factory2, android.content.ComponentCallbacks2 {
    private static final String TAG = "Activity";
    public static final int RESULT_OK = -1, RESULT_CANCELED = 0, RESULT_FIRST_USER = 1;
    static final String FRAGMENTS_TAG = "android:fragments";
    private static final String REQUEST_PERMISSIONS_WHO_PREFIX = "@android:requestPermissions:";
    private Window mWindow;
    private android.content.ComponentCallbacksController mCallbacksController;
    private ActivityInfo mActivityInfo = new ActivityInfo();
    private android.content.ComponentName mComponent;
    private Thread mUiThread;
    private android.os.IBinder mToken;
    private android.os.IBinder mAssistToken = new android.os.Binder();
    private android.os.IBinder mShareableActivityToken;
    private Activity mParent;
    String mEmbeddedID;
    private int mRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    boolean mChangingConfigurations;
    // AOSP Activity.mFinished is package-private: ActivityThread and the legacy
    // LocalActivityManager lifecycle both own transitions of this flag.
    boolean mFinished;
    boolean mDestroyed;
    boolean mStartedActivity;
    // AOSP Activity's scene and splash state; the containing Activity is still
    // hosted because its startup/window-service dependency closure is unfinished.
    final ActivityTransitionState mActivityTransitionState = new ActivityTransitionState();
    SharedElementCallback mEnterTransitionListener = SharedElementCallback.NULL_CALLBACK;
    SharedElementCallback mExitTransitionListener = SharedElementCallback.NULL_CALLBACK;
    SceneTransitionInfo mSceneTransitionInfo;
    boolean mStopped;
    boolean mResumed;
    private ActivityThread mMainThread;
    private int mIdent;
    private Instrumentation mInstrumentation;
    private Application mApplication;
    private ComponentCaller mInitialCaller, mCaller;
    private SplashScreen mSplashScreen;
    int mResultCode = RESULT_CANCELED;
    Intent mResultData;
    /** AOSP Activity.mCalled: lifecycle dispatchers verify required super calls through this bit. */
    boolean mCalled;
    /** Caller identity is visible only while a caller-aware result/new-intent callback runs. */
    private ComponentCaller mCurrentCaller;
    String mReferrer;
    /** AOSP configuration cached for deciding and reporting handled configuration changes. */
    Configuration mCurrentConfig = new Configuration();
    int mConfigChangeFlags;
    static final class NonConfigurationInstances {
        Object activity;
        java.util.HashMap<String, Object> children;
        FragmentManagerNonConfig fragments;
        android.util.ArrayMap<String, LoaderManager> loaders;
    }
    NonConfigurationInstances mLastNonConfigurationInstances;
    public Activity() { super(); }
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
        // The host's direct-Activity test/entry adapter has no package-resolution step.
        // Supply that launch component here; APK launches already resolve it before attach.
        Intent resolvedIntent = intent != null ? intent : new Intent();
        if (resolvedIntent.getComponent() == null) {
            resolvedIntent.setComponent(new android.content.ComponentName(base.getPackageName(),
                    activityInfo != null && activityInfo.name != null
                            ? activityInfo.name : getClass().getName()));
        }
        ActivityThread thread = ActivityThread.currentActivityThread();
        attach(base, thread, thread != null ? thread.getInstrumentation() : new Instrumentation(),
                token, 0, ActivityThread.currentApplication(), resolvedIntent,
                activityInfo != null ? activityInfo : new ActivityInfo(), "", parent, id,
                null, new Configuration(base.getResources().getConfiguration()), null, null, null,
                null, assistToken, shareableActivityToken, null);
    }
    /** AOSP Activity.attach's Instrumentation entry point. Activity/window lifecycle still uses
     * the hosted core; retain the supplied identity, retained state and PhoneWindow configuration
     * while its WindowManager, autofill and window-controller closure is completed. */
    final void attach(Context base, ActivityThread thread, Instrumentation instrumentation,
            android.os.IBinder token, int ident, Application application, Intent intent,
            ActivityInfo info, CharSequence title, Activity parent, String id,
            NonConfigurationInstances retained, Configuration config, String referrer,
            com.android.internal.app.IVoiceInteractor voiceInteractor, Window window,
            android.view.ViewRootImpl.ActivityConfigCallback activityConfigCallback,
            android.os.IBinder assistToken, android.os.IBinder shareableActivityToken,
            android.os.IBinder initialCallerInfoAccessToken) {
        mIntent = intent;
        attachBaseContext(base);
        if (base instanceof ContextImpl) ((ContextImpl) base).setOuterContext(this);
        mFragments.attachHost(null);
        mActivityInfo = info;
        mWindow = new PhoneWindow(this, window, activityConfigCallback);
        mWindow.setCallback(this);
        mWindow.getLayoutInflater().setPrivateFactory(this);
        if (info.softInputMode != WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED) {
            mWindow.setSoftInputMode(info.softInputMode);
        }
        if (info.uiOptions != 0) mWindow.setUiOptions(info.uiOptions);
        mUiThread = Thread.currentThread();
        mMainThread = thread;
        mIdent = ident;
        mInstrumentation = instrumentation;
        mApplication = application;
        mToken = token;
        mAssistToken = assistToken;
        mShareableActivityToken = shareableActivityToken;
        mReferrer = referrer;
        mComponent = intent.getComponent();
        mTitle = title;
        mParent = parent;
        mEmbeddedID = id;
        mLastNonConfigurationInstances = retained;
        mVoiceInteractor = voiceInteractor;
        mRequestedOrientation = info.screenOrientation;
        mWindow.setWindowManager((WindowManager) base.getSystemService(Context.WINDOW_SERVICE),
                token, mComponent.flattenToString(),
                (info.flags & ActivityInfo.FLAG_HARDWARE_ACCELERATED) != 0);
        if (parent != null) mWindow.setContainer(parent.getWindow());
        mCurrentConfig = config;
        mWindow.setColorMode(info.colorMode);
        mWindow.setPreferMinimalPostProcessing(
                (info.flags & ActivityInfo.FLAG_PREFER_MINIMAL_POST_PROCESSING) != 0);
        if (android.security.Flags.contentUriPermissionApis()) {
            mInitialCaller = new ComponentCaller(getActivityToken(), initialCallerInfoAccessToken);
            mCaller = mInitialCaller;
        }
    }
    public Application getApplication() { return mApplication; }
    public ActivityThread getActivityThread() { return mMainThread; }
    public ComponentCaller getInitialCaller() { return mInitialCaller; }
    public ComponentCaller getCaller() { return mCaller; }
    public void setIntent(Intent newIntent) { internalSetIntent(newIntent, null); }
    public void setIntent(Intent newIntent, ComponentCaller newCaller) {
        internalSetIntent(newIntent, newCaller);
    }
    private void internalSetIntent(Intent newIntent, ComponentCaller newCaller) {
        mIntent = newIntent;
        mCaller = newCaller;
    }
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
    public boolean isChangingConfigurations() { return mChangingConfigurations; }
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
    /** Pinned Activity.WindowControllerCallback.isTaskRoot; the hosted window has no callback object. */
    public boolean isTaskRoot() {
        return ActivityClient.getInstance().getTaskForActivity(mToken, true /* onlyRoot */) >= 0;
    }
    public void overridePendingTransition(int enterAnim, int exitAnim) {}
    public void setRequestedOrientation(int requestedOrientation) {
        mRequestedOrientation = requestedOrientation;
    }
    public int getRequestedOrientation() { return mRequestedOrientation; }
    public int getChangingConfigurations() { return mConfigChangeFlags; }
    public void setVolumeControlStream(int streamType) {}
    public int getVolumeControlStream() { return 3; }
    public void invalidateOptionsMenu() {}
    public void closeOptionsMenu() {}
    public void openOptionsMenu() {}
    // Pinned AOSP Activity.recreate: never destroy the caller inside this callback.
    public void recreate() {
        if (mParent != null) {
            throw new IllegalStateException("Can only be called on top-level activity");
        }
        if (android.os.Looper.myLooper() != mMainThread.getLooper()) {
            throw new IllegalStateException("Must be called from main thread");
        }
        mMainThread.scheduleRelaunchActivity(mToken);
    }

    /** AOSP Activity.onTopResumedActivityChanged. */
    public void onTopResumedActivityChanged(boolean isTopResumedActivity) {}

    /** Pinned Activity.performTopResumedActivityChanged, including stock event logging. */
    final void performTopResumedActivityChanged(boolean isTopResumedActivity, String reason) {
        onTopResumedActivityChanged(isTopResumedActivity);

        if (isTopResumedActivity) {
            EventLogTags.writeWmOnTopResumedGainedCalled(mIdent, getComponentName().getClassName(),
                    reason);
        } else {
            EventLogTags.writeWmOnTopResumedLostCalled(mIdent, getComponentName().getClassName(),
                    reason);
        }
    }

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
    /** Pinned Activity identity queries delegate to the stock ActivityClient transport. */
    public String getLaunchedFromPackage() { return ActivityClient.getInstance().getLaunchedFromPackage(getActivityToken()); }
    /** AOSP Activity.requestWindowFeature: a feature request on the activity window. */
    public final boolean requestWindowFeature(int featureId) { return getWindow().requestFeature(featureId); }
    /** AOSP Activity.onActivityResult: results reach the activity through dispatchActivityResult. */
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {}
    /** Flagged AOSP callback; the default preserves legacy three-argument overrides. */
    public void onActivityResult(int requestCode, int resultCode, Intent data,
            ComponentCaller caller) {
        onActivityResult(requestCode, resultCode, data);
    }
    /** Flagged AOSP callback; the default preserves legacy one-argument overrides. */
    public void onNewIntent(Intent intent, ComponentCaller caller) { onNewIntent(intent); }
    /** AOSP caller scope: valid only from a caller-aware onActivityResult/onNewIntent callback. */
    public ComponentCaller getCurrentCaller() {
        if (mCurrentCaller == null) {
            throw new IllegalStateException("The caller is null because #getCurrentCaller should be"
                    + " called within #onNewIntent or #onActivityResult methods");
        }
        return mCurrentCaller;
    }
    public void startActivityForResult(Intent intent, int requestCode) { startActivityForResult(intent, requestCode, null); }
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        if (mParent != null) {
            if (options != null) mParent.startActivityFromChild(this, intent, requestCode, options);
            else mParent.startActivityFromChild(this, intent, requestCode);
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
        options = transferSpringboardActivityOptions(options);
        Instrumentation.ActivityResult result = mInstrumentation.execStartActivity(
                this, mMainThread.getApplicationThread(), mToken, child,
                intent, requestCode, options);
        if (result != null) {
            mMainThread.sendActivityResult(mToken, child.mEmbeddedID, requestCode,
                    result.getResultCode(), result.getResultData());
        }
        cancelInputsAndStartExitTransition(options);
    }
    /** Pinned Activity.startActivityForResult: monitor results are asynchronous activity
     * transactions. The surrounding Activity/window core remains hosted. */
    public void startActivityForResult(String who, Intent intent, int requestCode, Bundle options) {
        options = transferSpringboardActivityOptions(options);
        final Instrumentation.ActivityResult result;
        if (who == null) {
            result = mInstrumentation.execStartActivity(this,
                    mMainThread.getApplicationThread(), mToken, this,
                    intent, requestCode, options);
        } else {
            android.net.Uri referrer = onProvideReferrer();
            if (referrer != null) intent.putExtra(Intent.EXTRA_REFERRER, referrer);
            result = mInstrumentation.execStartActivity(this,
                    mMainThread.getApplicationThread(), mToken, who,
                    intent, requestCode, options);
        }
        if (result != null) {
            mMainThread.sendActivityResult(mToken, who, requestCode,
                    result.getResultCode(), result.getResultData());
        }
        cancelInputsAndStartExitTransition(options);
    }
    @Override public boolean canStartActivityForResult() { return true; }
    private Bundle transferSpringboardActivityOptions(Bundle options) {
        if (options == null && (mWindow != null && !mWindow.isActive())) {
            final SceneTransitionInfo info = getSceneTransitionInfo();
            if (info != null) {
                return ActivityOptions.makeBasic().setSceneTransitionInfo(info).toBundle();
            }
        }
        return options;
    }
    /** Pinned AOSP Activity IntentSender entry points; task dispatch is hosted behind Binder. */
    public void startIntentSenderForResult(android.content.IntentSender intent, int requestCode,
            Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags)
            throws android.content.IntentSender.SendIntentException {
        startIntentSenderForResult(intent, requestCode, fillInIntent, flagsMask, flagsValues, extraFlags, null);
    }
    public void startIntentSenderForResult(android.content.IntentSender intent, int requestCode,
            Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags, Bundle options)
            throws android.content.IntentSender.SendIntentException {
        if (mParent == null) {
            startIntentSenderForResultInner(intent, mEmbeddedID, requestCode, fillInIntent,
                    flagsMask, flagsValues, options);
        } else if (options != null) {
            mParent.startIntentSenderFromChild(this, intent, requestCode, fillInIntent,
                    flagsMask, flagsValues, extraFlags, options);
        } else {
            mParent.startIntentSenderFromChild(this, intent, requestCode, fillInIntent,
                    flagsMask, flagsValues, extraFlags);
        }
    }
    /** AOSP convertToTranslucent: no translucent conversion without the activity task manager. */
    public interface TranslucentConversionListener { void onTranslucentConversionComplete(boolean drawComplete); }
    public boolean convertToTranslucent(TranslucentConversionListener callback, ActivityOptions options) {
        if (callback != null) callback.onTranslucentConversionComplete(false);
        return false;
    }
    public void convertFromTranslucent() {}
    public void startIntentSenderForResult(android.content.IntentSender intent, String who,
            int requestCode, Intent fillInIntent, int flagsMask, int flagsValues, Bundle options)
            throws android.content.IntentSender.SendIntentException {
        startIntentSenderForResultInner(intent, who, requestCode, fillInIntent,
                flagsMask, flagsValues, options);
    }
    public void startIntentSenderFromChild(Activity child, android.content.IntentSender intent,
            int requestCode, Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags)
            throws android.content.IntentSender.SendIntentException {
        startIntentSenderFromChild(child, intent, requestCode, fillInIntent,
                flagsMask, flagsValues, extraFlags, null);
    }
    public void startIntentSenderFromChild(Activity child, android.content.IntentSender intent,
            int requestCode, Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags,
            Bundle options) throws android.content.IntentSender.SendIntentException {
        startIntentSenderForResultInner(intent, child.mEmbeddedID, requestCode, fillInIntent,
                flagsMask, flagsValues, options);
    }
    private void startIntentSenderFromFragment(Fragment fragment, android.content.IntentSender intent,
            int requestCode, Intent fillInIntent, int flagsMask, int flagsValues, Bundle options)
            throws android.content.IntentSender.SendIntentException {
        startIntentSenderForResultInner(intent, fragment.mWho, requestCode, fillInIntent,
                flagsMask, flagsValues, options);
    }
    /** AOSP startIntentSenderForResultInner, including fill-in preparation, cancellation mapping,
     * and result recipient identity. Window visibility still belongs to the presentation adapter. */
    public void startIntentSenderForResultInner(android.content.IntentSender intent, String who,
            int requestCode, Intent fillInIntent, int flagsMask, int flagsValues, Bundle options)
            throws android.content.IntentSender.SendIntentException {
        try {
            options = transferSpringboardActivityOptions(options);
            String resolvedType = null;
            if (fillInIntent != null) {
                fillInIntent.migrateExtraStreamToClipData(this);
                fillInIntent.prepareToLeaveProcess(this);
                resolvedType = fillInIntent.resolveTypeIfNeeded(getContentResolver());
            }
            int result = ActivityTaskManager.getService().startActivityIntentSender(
                    mMainThread.getApplicationThread(), intent != null ? intent.getTarget() : null,
                    intent != null ? intent.getWhitelistToken() : null, fillInIntent, resolvedType,
                    mToken, who, requestCode, flagsMask, flagsValues, options);
            if (result == ActivityManager.START_CANCELED) {
                throw new android.content.IntentSender.SendIntentException();
            }
            Instrumentation.checkStartActivityResult(result, null);
            if (options != null) cancelInputsAndStartExitTransition(options);
        } catch (android.os.RemoteException e) {
            // Pinned Activity retains this compatibility behavior for a dead system service.
        }
        if (requestCode >= 0) mStartedActivity = true;
    }
    public void setResult(int resultCode) { setResult(resultCode, null); }
    public void setResult(int resultCode, Intent data) {
        mResultCode = resultCode;
        mResultData = data;
    }
    /** Pinned AOSP compatibility overload used when caller identity is unavailable. */
    void dispatchActivityResult(String who, int requestCode, int resultCode, Intent data,
            String reason) {
        if (android.security.Flags.contentUriPermissionApis()) {
            internalDispatchActivityResult(who, requestCode, resultCode, data,
                    new ComponentCaller(getActivityToken(), null), reason);
        } else {
            internalDispatchActivityResult(who, requestCode, resultCode, data, null, reason);
        }
    }
    /** AOSP result dispatch shape overridden by ActivityGroup for embedded child ids. */
    void dispatchActivityResult(String who, int requestCode, int resultCode, Intent data,
            ComponentCaller caller, String reason) {
        internalDispatchActivityResult(who, requestCode, resultCode, data, caller, reason);
    }
    /** Pinned AOSP Activity.internalDispatchActivityResult, reduced to hosted fragments. */
    private void internalDispatchActivityResult(String who, int requestCode, int resultCode,
            Intent data, ComponentCaller caller, String reason) {
        mFragments.noteStateNotSaved();
        if (who == null) {
            if (android.security.Flags.contentUriPermissionApis()) {
                mCurrentCaller = caller;
                onActivityResult(requestCode, resultCode, data, caller);
                mCurrentCaller = null;
            } else {
                onActivityResult(requestCode, resultCode, data);
            }
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
    public boolean isActivityTransitionRunning() {
        return mActivityTransitionState.isTransitionRunning();
    }
    public void reportFullyDrawn() {}
    public void postponeEnterTransition() {
        mActivityTransitionState.postponeEnterTransition();
    }
    public void startPostponedEnterTransition() {
        mActivityTransitionState.startPostponedEnterTransition();
    }
    /** Pinned AOSP default: use the launching package as referrer. */
    public android.net.Uri onProvideReferrer() { return null; }
    public android.net.Uri getReferrer() {
        final Intent intent = getIntent();
        if (intent != null) {
            try {
                final android.net.Uri referrer = intent.getParcelableExtra(
                        Intent.EXTRA_REFERRER, android.net.Uri.class);
                if (referrer != null) return referrer;
                final String referrerName = intent.getStringExtra(Intent.EXTRA_REFERRER_NAME);
                if (referrerName != null) return android.net.Uri.parse(referrerName);
            } catch (android.os.BadParcelableException e) {
                android.util.Log.w(TAG, "Cannot read referrer from malformed intent extras", e);
            }
        }
        return mReferrer != null
                ? new android.net.Uri.Builder().scheme("android-app").authority(mReferrer).build()
                : null;
    }
    public int getLaunchedFromUid() { return ActivityClient.getInstance().getLaunchedFromUid(getActivityToken()); }
    public String getCallingPackage() { return ActivityClient.getInstance().getCallingPackage(mToken); }
    public android.content.ComponentName getCallingActivity() { return ActivityClient.getInstance().getCallingActivity(mToken); }
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
    Intent mIntent = new Intent();
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
    // Pinned Activity.setTheme and ContextThemeWrapper own theme/resource state.
    @Override public void setTheme(int resid) {
        super.setTheme(resid);
        mWindow.setTheme(resid);
    }
    @Override public Object getSystemService(String name) {
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
    public boolean isResumed() { return mResumed; }
    public boolean isFinishing() { return mFinished; }
    public boolean isDestroyed() { return mDestroyed; }
    /** Pinned AOSP Activity visibility contract used by AutofillClientController. */
    public final boolean isVisibleForAutofill() { return !mStopped; }

    // Pinned Activity nested API types, shared with its stock support classes. The containing
    // Activity still awaits the voice-interaction and window dependency closure.
    @android.annotation.IntDef(prefix = { "FULLSCREEN_REQUEST_" }, value = {
            FULLSCREEN_MODE_REQUEST_EXIT, FULLSCREEN_MODE_REQUEST_ENTER })
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface FullscreenModeRequest {}
    public static final int FULLSCREEN_MODE_REQUEST_EXIT = 0;
    public static final int FULLSCREEN_MODE_REQUEST_ENTER = 1;
    public interface ScreenCaptureCallback { void onScreenCaptured(); }

    public static final int DONT_FINISH_TASK_WITH_ACTIVITY = 0;
    public static final int FINISH_TASK_WITH_ROOT_ACTIVITY = 1;
    public static final int FINISH_TASK_WITH_ACTIVITY = 2;

    /** Pinned Activity.finish(int)/finishAndRemoveTask. The containing Activity still needs
     * its voice-interaction and window closure; the stock service owns task/result routing. */
    public void finish() {
        finish(DONT_FINISH_TASK_WITH_ACTIVITY);
    }
    private void finish(int finishTask) {
        if (mParent != null) {
            mParent.finishFromChild(this);
        } else {
            final int resultCode;
            final Intent resultData;
            synchronized (this) {
                resultCode = mResultCode;
                resultData = mResultData;
            }
            if (resultData != null) resultData.prepareToLeaveProcess(this);
            if (ActivityClient.getInstance().finishActivity(mToken, resultCode, resultData, finishTask)) {
                mFinished = true;
            }
        }
        getAutofillClientController().onActivityFinish(mIntent);
    }
    /** AOSP's default finishes the parent; ActivityGroup subclasses may route to their manager. */
    @Deprecated public void finishFromChild(Activity child) { finish(); }
    public void finishAndRemoveTask() { finish(FINISH_TASK_WITH_ROOT_ACTIVITY); }
    private android.view.autofill.AutofillClientController mAutofillClientController;
    private android.view.autofill.AutofillClientController getAutofillClientController() {
        if (mAutofillClientController == null) {
            mAutofillClientController = new android.view.autofill.AutofillClientController(this);
        }
        return mAutofillClientController;
    }
    public boolean moveTaskToBack(boolean nonRoot) {
        return ActivityClient.getInstance().moveActivityTaskToBack(mToken, nonRoot);
    }
    public ActivityInfo getActivityInfo() { return mActivityInfo; }
    public final void runOnUiThread(Runnable action) {
        if (Thread.currentThread() != mUiThread) {
            mHandler.post(action);
        } else {
            action.run();
        }
    }
    public int getTaskId() {
        return ActivityClient.getInstance().getTaskForActivity(mToken, false /* onlyRoot */);
    }
    /** AOSP: the assist token the activity manager issued; a process-local binder here. */
    public final android.os.IBinder getAssistToken() {
        return mParent != null ? mParent.getAssistToken() : mAssistToken;
    }
    private com.android.internal.app.IVoiceInteractor mVoiceInteractor;
    public void setVoiceInteractor(com.android.internal.app.IVoiceInteractor voiceInteractor) { mVoiceInteractor = voiceInteractor; }
    public VoiceInteractor getVoiceInteractor() { return null; }
    public android.content.ComponentName getComponentName() {
        return mComponent;
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
        final String pkg = getPackageName();
        final String cls = mComponent.getClassName();
        int packageLen = pkg.length();
        if (!cls.startsWith(pkg) || cls.length() <= packageLen
                || cls.charAt(packageLen) != '.') {
            return cls;
        }
        return cls.substring(packageLen+1);
    }
    public final @NonNull SplashScreen getSplashScreen() {
        return getOrCreateSplashScreen();
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
    protected void onResume() { mActivityTransitionState.onResume(this); mCalled = true; }
    @Deprecated public void onStateNotSaved() {}
    protected void onPostResume() {}
    protected void onPause() { mCalled = true; }
    protected void onStop() { mActivityTransitionState.onStop(this); mCalled = true; }
    protected void onDestroy() {}
    /** Pinned AOSP Activity.onCreateDescription; captured by the stop transaction report. */
    public CharSequence onCreateDescription() { return null; }
    /** AOSP Activity.onNewIntent; used when LocalActivityManager reuses a child activity. */
    protected void onNewIntent(Intent intent) {}
    protected void onSaveInstanceState(Bundle outState) {
        android.os.Parcelable fragmentState = mFragments.saveAllState();
        if (fragmentState != null) outState.putParcelable(FRAGMENTS_TAG, fragmentState);
    }
    protected void onRestoreInstanceState(Bundle savedInstanceState) {}
    // Pinned AOSP persistable callback defaults delegate to the Bundle overloads.
    public void onCreate(Bundle state, PersistableBundle persistentState) { onCreate(state); }
    public void onPostCreate(Bundle state, PersistableBundle persistentState) { onPostCreate(state); }
    public void onSaveInstanceState(Bundle state, PersistableBundle persistentState) {
        onSaveInstanceState(state);
    }
    public void onRestoreInstanceState(Bundle state, PersistableBundle persistentState) {
        if (state != null) onRestoreInstanceState(state);
    }
    public void onUserInteraction() {}
    protected void onUserLeaveHint() {}
    /** AOSP performUserLeaving ordering. The host advertises neither PiP nor Leanback,
     * so the TV implicit-PiP permission gate cannot affect an available operation. */
    final void performUserLeaving() {
        onUserInteraction();
        onUserLeaveHint();
    }
    public final void performCreate(Bundle state) { performCreate(state, null); }
    public final void performCreate(Bundle state, PersistableBundle persistentState) {
        Application app = appOrNull();
        if (app != null) app.dispatchActivityPreCreated(this, state);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPreCreated(this, state);
        if (persistentState != null) onCreate(state, persistentState); else onCreate(state);
        mActivityTransitionState.readState(state);
        if (app != null) app.dispatchActivityCreated(this, state);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityCreated(this, state);
        mFragments.dispatchActivityCreated();
        mActivityTransitionState.setEnterSceneTransitionInfo(this, getSceneTransitionInfo());
        if (app != null) app.dispatchActivityPostCreated(this, state);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPostCreated(this, state);
    }
    /** AOSP invokes onPostCreate after start and saved-state restoration. */
    public final void performPostCreate(Bundle state) { onPostCreate(state); }
    public final void performStart() {
        Application app = appOrNull();
        if (app != null) app.dispatchActivityPreStarted(this);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPreStarted(this);
        mActivityTransitionState.setEnterSceneTransitionInfo(this, getSceneTransitionInfo());
        mFragments.noteStateNotSaved();
        mFragments.execPendingActions();
        onStart();
        if (app != null) app.dispatchActivityStarted(this);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityStarted(this);
        mFragments.dispatchStart();
        mFragments.reportLoaderStart();
        mActivityTransitionState.enterReady(this);
        if (app != null) app.dispatchActivityPostStarted(this);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPostStarted(this);
    }
    /** AOSP Activity.performRestart, limited to the lifecycle state owned by ActivityThread. */
    final void performRestart(boolean start) {
        mStopped = false;
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
        mResumed = true;
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
        mCalled = false;
        onPause();
        mResumed = false;
        if (!mCalled && getApplicationInfo().targetSdkVersion
                >= android.os.Build.VERSION_CODES.GINGERBREAD) {
            throw new android.util.SuperNotCalledException(
                    "Activity " + getComponentName().toShortString()
                    + " did not call through to super.onPause()");
        }
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
        mStopped = true;
        if (app != null) app.dispatchActivityPostStopped(this);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPostStopped(this);
    }
    public final void performDestroy() {
        Application app = appOrNull();
        if (app != null) app.dispatchActivityPreDestroyed(this);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPreDestroyed(this);
        // AOSP performDestroy marks both the Activity and Window before fragment/onDestroy calls.
        mDestroyed = true;
        mWindow.destroy();
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
    final void performNewIntent(Intent intent, ComponentCaller caller) {
        mCurrentCaller = caller;
        try {
            onNewIntent(intent, caller);
        } finally {
            mCurrentCaller = null;
        }
    }
    public final void performSaveInstanceState(Bundle outState) {
        performSaveInstanceState(outState, null, false);
    }
    public final void performSaveInstanceState(Bundle outState, PersistableBundle persistentState) {
        performSaveInstanceState(outState, persistentState, true);
    }
    private void performSaveInstanceState(Bundle outState, PersistableBundle persistentState,
            boolean persistable) {
        Application app = appOrNull();
        if (app != null) app.dispatchActivityPreSaveInstanceState(this, outState);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPreSaveInstanceState(this, outState);
        if (persistable) onSaveInstanceState(outState, persistentState);
        else onSaveInstanceState(outState);
        // AOSP saves transition state only through the non-persistable overload.
        if (!persistable) mActivityTransitionState.saveState(outState);
        if (app != null) app.dispatchActivitySaveInstanceState(this, outState);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivitySaveInstanceState(this, outState);
        if (app != null) app.dispatchActivityPostSaveInstanceState(this, outState);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPostSaveInstanceState(this, outState);
    }
    public final void performRestoreInstanceState(Bundle savedInstanceState) {
        onRestoreInstanceState(savedInstanceState);
    }
    public final void performRestoreInstanceState(Bundle state, PersistableBundle persistentState) {
        onRestoreInstanceState(state, persistentState);
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
    /** Pinned Activity component-callback ownership. The activity/window class is still
     * adapted; the stock controller preserves registration and snapshot dispatch behavior. */
    @Override public void registerComponentCallbacks(android.content.ComponentCallbacks callback) {
        if (android.app.compat.CompatChanges.isChangeEnabled(OVERRIDABLE_COMPONENT_CALLBACKS)
                && mCallbacksController == null) {
            mCallbacksController = new android.content.ComponentCallbacksController();
        }
        if (mCallbacksController != null) {
            mCallbacksController.registerCallbacks(callback);
        } else {
            super.registerComponentCallbacks(callback);
        }
    }
    @Override public void unregisterComponentCallbacks(android.content.ComponentCallbacks callback) {
        if (mCallbacksController != null) {
            mCallbacksController.unregisterCallbacks(callback);
        } else {
            super.unregisterComponentCallbacks(callback);
        }
    }
    @Override public void onConfigurationChanged(Configuration newConfig) {
        mCalled = true;
        mFragments.dispatchConfigurationChanged(newConfig);
        if (mWindow != null) {
            mWindow.onConfigurationChanged(newConfig);
        }
        Application app = appOrNull();
        if (app != null) app.dispatchActivityConfigurationChanged(this);
        for (Application.ActivityLifecycleCallbacks callback : collectLifecycleCallbacks()) {
            callback.onActivityConfigurationChanged(this);
        }
        if (mCallbacksController != null) {
            mCallbacksController.dispatchConfigurationChanged(newConfig);
        }
    }
    /** AOSP Activity display-move dispatch; ContextWrapper updates the activity base context. */
    final void dispatchMovedToDisplay(int displayId, Configuration config) {
        updateDisplay(displayId);
        onMovedToDisplay(displayId, config);
    }
    public void onMovedToDisplay(int displayId, Configuration config) {}
    @Override public void onLowMemory() {
        mCalled = true;
        mFragments.dispatchLowMemory();
        if (mCallbacksController != null) mCallbacksController.dispatchLowMemory();
    }
    @Override public void onTrimMemory(int level) {
        mCalled = true;
        mFragments.dispatchTrimMemory(level);
        if (mCallbacksController != null) mCallbacksController.dispatchTrimMemory(level);
    }
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
            if (mParent == null) {
                startIntentSenderForResultInner(intent, fragment.mWho, requestCode, fillInIntent,
                        flagsMask, flagsValues, options);
            } else if (options != null) {
                mParent.startIntentSenderFromFragment(fragment, intent, requestCode, fillInIntent,
                        flagsMask, flagsValues, options);
            }
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
    // Reused AOSP Activity scene/splash APIs.
    private SplashScreen getOrCreateSplashScreen() {
        synchronized (this) {
            if (mSplashScreen == null) {
                mSplashScreen = new SplashScreen.SplashScreenImpl(this);
            }
            return mSplashScreen;
        }
    }

    final boolean isTopOfTask() {
        if (mToken == null || mWindow == null) {
            return false;
        }
        return ActivityClient.getInstance().isTopOfTask(getActivityToken());
    }

    public void finishAfterTransition() {
        if (!mActivityTransitionState.startExitBackTransition(this)) {
            finish();
        }
    }

    public void onActivityReenter(int resultCode, Intent data) {
    }

    public void setEnterSharedElementCallback(SharedElementCallback callback) {
        if (callback == null) {
            callback = SharedElementCallback.NULL_CALLBACK;
        }
        mEnterTransitionListener = callback;
    }

    public void setExitSharedElementCallback(SharedElementCallback callback) {
        if (callback == null) {
            callback = SharedElementCallback.NULL_CALLBACK;
        }
        mExitTransitionListener = callback;
    }

    public void onNewSceneTransitionInfo(ActivityOptions.SceneTransitionInfo info) {
        mActivityTransitionState.setEnterSceneTransitionInfo(this, info);
        if (!mStopped) {
            mActivityTransitionState.enterReady(this);
        }
    }

    SceneTransitionInfo getSceneTransitionInfo() {
        final SceneTransitionInfo sceneTransitionInfo = mSceneTransitionInfo;
        // The info only applies once.
        mSceneTransitionInfo = null;
        return sceneTransitionInfo;
    }

    private void cancelInputsAndStartExitTransition(Bundle options) {
        final View decor = mWindow != null ? mWindow.peekDecorView() : null;
        if (decor != null) {
            decor.cancelPendingInputEvents();
        }
        if (options != null) {
            mActivityTransitionState.startExitOutTransition(this, options);
        }
    }


}
