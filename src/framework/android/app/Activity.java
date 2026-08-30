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
    private Window mWindow;
    private ActivityInfo mActivityInfo = new ActivityInfo();
    private int mRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private boolean mChangingConfigurations;
    public Activity() { super(null); }
    public void attach(Context base) {
        attachBaseContext(base);
        if (base instanceof ContextImpl) ((ContextImpl) base).setOuterContext(this);
        mWindow = new PhoneWindow(this);
        mWindow.setCallback(this);
        // Real Activity.attach makes the Activity the inflater's private
        // factory. FragmentActivity overrides onCreateView to inflate
        // AndroidX <fragment> tags through its FragmentController.
        mWindow.getLayoutInflater().setPrivateFactory(this);
        mWindow.setWindowManager((WindowManager) base.getSystemService(Context.WINDOW_SERVICE),
                null, getClass().getName(), false);
    }
    public void attach(Context base, ActivityInfo activityInfo) {
        attach(base);
        mActivityInfo = activityInfo != null ? new ActivityInfo(activityInfo) : new ActivityInfo();
        mRequestedOrientation = mActivityInfo.screenOrientation;
    }
    public Application getApplication() {
        return ActivityThread.currentApplication();
    }
    public void setIntent(Intent newIntent) { mIntent = newIntent; }
    public android.view.LayoutInflater getLayoutInflater() { return mWindow.getLayoutInflater(); }
    public Object getLastNonConfigurationInstance() { return null; }
    public Object onRetainNonConfigurationInstance() { return null; }
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
    public void requestPermissions(String[] permissions, int requestCode) {}
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {}
    public void startActivityForResult(Intent intent, int requestCode) { startActivity(intent); }
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) { startActivity(intent); }
    public void setResult(int resultCode) {}
    public void setResult(int resultCode, Intent data) {}
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
    private FragmentManager mFragmentManager;
    public android.app.FragmentManager getFragmentManager() {
        if (mFragmentManager == null) mFragmentManager = new FragmentManager();
        return mFragmentManager;
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
    private Application.ActivityLifecycleCallbacks[] collectLifecycleCallbacks() {
        java.util.ArrayList<Application.ActivityLifecycleCallbacks> all = new java.util.ArrayList<>();
        Application app = getApplication();
        if (app != null) java.util.Collections.addAll(all, app.collectActivityLifecycleCallbacks());
        synchronized (mLifecycleCallbacks) { all.addAll(mLifecycleCallbacks); }
        return all.toArray(new Application.ActivityLifecycleCallbacks[0]);
    }
    public void registerComponentCallbacks(android.content.ComponentCallbacks callback) {}
    public void unregisterComponentCallbacks(android.content.ComponentCallbacks callback) {}
    private int mThemeRes;
    private android.content.res.Resources.Theme mTheme;
    @Override public void setTheme(int resid) {
        if (mThemeRes != resid) { mThemeRes = resid; mTheme = null; }
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
    public android.os.IBinder getActivityToken() { return null; }
    public android.os.IBinder getShareableActivityToken() { return null; }
    public Window getWindow() { return mWindow; }
    public WindowManager getWindowManager() { return mWindow != null ? mWindow.getWindowManager() : null; }
    public boolean isResumed() { return true; }
    public boolean isFinishing() { return false; }
    public boolean isDestroyed() { return false; }
    public void finish() { ActivityThread.finishActivityInProcess(this); }
    public void finishAndRemoveTask() {}
    public boolean moveTaskToBack(boolean nonRoot) { return false; }
    public ActivityInfo getActivityInfo() { return mActivityInfo; }
    public void runOnUiThread(Runnable action) { action.run(); }
    public int getTaskId() { return 0; }
    public android.content.ComponentName getComponentName() {
        return new android.content.ComponentName("dev.artzig", getClass().getName());
    }
    public void addDumpable(Object dumpable) {}
    @Override public View onCreateView(String name, Context context,
            android.util.AttributeSet attrs) { return null; }
    @Override public View onCreateView(View parent, String name, Context context,
            android.util.AttributeSet attrs) { return onCreateView(name, context, attrs); }
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
    protected void onCreate(Bundle savedInstanceState) {}
    protected void onPostCreate(Bundle savedInstanceState) {}
    protected void onStart() {}
    protected void onResume() {}
    protected void onPostResume() {}
    protected void onPause() {}
    protected void onStop() {}
    protected void onDestroy() {}
    protected void onSaveInstanceState(Bundle outState) {}
    protected void onRestoreInstanceState(Bundle savedInstanceState) {}
    public final void performCreate(Bundle state) {
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPreCreated(this, state);
        onCreate(state);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) {
            cb.onActivityCreated(this, state);
            cb.onActivityPostCreated(this, state);
        }
        onPostCreate(state);
    }
    public final void performStart() {
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPreStarted(this);
        onStart();
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) {
            cb.onActivityStarted(this);
            cb.onActivityPostStarted(this);
        }
    }
    public final void performResume() {
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPreResumed(this);
        onResume();
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityResumed(this);
        onPostResume();
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPostResumed(this);
    }
    public final void performPause() {
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPrePaused(this);
        onPause();
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) {
            cb.onActivityPaused(this);
            cb.onActivityPostPaused(this);
        }
    }
    public final void performStop() {
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPreStopped(this);
        onStop();
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) {
            cb.onActivityStopped(this);
            cb.onActivityPostStopped(this);
        }
    }
    public final void performDestroy() {
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) cb.onActivityPreDestroyed(this);
        onDestroy();
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) {
            cb.onActivityDestroyed(this);
            cb.onActivityPostDestroyed(this);
        }
    }
    public final void performSaveInstanceState(Bundle outState) {
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) {
            cb.onActivityPreSaveInstanceState(this, outState);
        }
        onSaveInstanceState(outState);
        for (Application.ActivityLifecycleCallbacks cb : collectLifecycleCallbacks()) {
            cb.onActivitySaveInstanceState(this, outState);
            cb.onActivityPostSaveInstanceState(this, outState);
        }
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
        return featureId == Window.FEATURE_OPTIONS_PANEL && onCreateOptionsMenu(menu);
    }
    @Override public boolean onPreparePanel(int featureId, View view, Menu menu) {
        return featureId != Window.FEATURE_OPTIONS_PANEL || onPrepareOptionsMenu(menu);
    }
    @Override public boolean onMenuOpened(int featureId, Menu menu) { return true; }
    @Override public boolean onMenuItemSelected(int featureId, MenuItem item) {
        if (featureId == Window.FEATURE_OPTIONS_PANEL) return onOptionsItemSelected(item);
        return false;
    }
    public boolean onOptionsItemSelected(MenuItem item) { return false; }
    public boolean onCreateOptionsMenu(Menu menu) { return true; }
    public boolean onPrepareOptionsMenu(Menu menu) { return true; }
    @Override public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {}
    @Override public void onContentChanged() {}
    @Override public void onWindowFocusChanged(boolean hasFocus) {}
    @Override public void onAttachedToWindow() {}
    @Override public void onDetachedFromWindow() {}
    @Override public void onPanelClosed(int featureId, Menu menu) {}
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
    public void onBackPressed() { finish(); }
    public void onConfigurationChanged(Configuration newConfig) {
        if (mWindow != null) {
            mWindow.getDecorView().dispatchConfigurationChanged(newConfig);
        }
    }
    public Object getSystemService$override(String name) { return null; }
}
