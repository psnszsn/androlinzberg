/* artzig: functional android.app.Dialog — real PhoneWindow, rendered as an
 * overlay window via WindowManagerImpl/ArtzigWindowStack (ATL pattern). */
package android.app;

import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import com.android.internal.policy.PhoneWindow;

public class Dialog implements DialogInterface, Window.Callback,
        KeyEvent.Callback, View.OnCreateContextMenuListener {
    private static final String TAG = "Dialog";
    private final Context mContext;
    private final Window mWindow;
    private final WindowManager mWindowManager;
    private View mDecor;
    private Activity mOwnerActivity;
    private boolean mCreated;
    private boolean mShowing;
    private boolean mCancelable = true;
    private boolean mCanceledOnTouchOutside = true;
    private DialogInterface.OnCancelListener mCancelListener;
    private DialogInterface.OnDismissListener mDismissListener;
    private DialogInterface.OnShowListener mShowListener;
    private DialogInterface.OnKeyListener mOnKeyListener;

    public Dialog(Context context) {
        this(context, 0, true);
    }

    public Dialog(Context context, int themeResId) {
        this(context, themeResId, true);
    }

    protected Dialog(Context context, int themeResId, boolean createContextThemeWrapper) {
        if (createContextThemeWrapper) {
            if (themeResId == 0) {
                TypedValue tv = new TypedValue();
                context.getTheme().resolveAttribute(android.R.attr.dialogTheme, tv, true);
                themeResId = tv.resourceId;
            }
            mContext = new ContextThemeWrapper(context, themeResId);
        } else {
            mContext = context;
        }
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        Window w = new PhoneWindow(mContext);
        mWindow = w;
        w.setCallback(this);
        w.setWindowManager(mWindowManager, null, null);
        w.setGravity(Gravity.CENTER);
    }

    protected Dialog(Context context, boolean cancelable,
            DialogInterface.OnCancelListener cancelListener) {
        this(context, 0, true);
        mCancelable = cancelable;
        mCancelListener = cancelListener;
    }

    public Context getContext() { return mContext; }
    public final Window getWindow() { return mWindow; }
    public View getCurrentFocus() { return mWindow.getCurrentFocus(); }
    public ActionBar getActionBar() { return null; }

    public void setOwnerActivity(Activity activity) { mOwnerActivity = activity; }
    public Activity getOwnerActivity() { return mOwnerActivity; }

    public LayoutInflater getLayoutInflater() { return mWindow.getLayoutInflater(); }

    public void create() { dispatchOnCreate(null); }

    void dispatchOnCreate(Bundle savedInstanceState) {
        if (!mCreated) {
            onCreate(savedInstanceState);
            mCreated = true;
        }
    }

    public void show() {
        if (mShowing) return;
        dispatchOnCreate(null);
        onStart();
        mDecor = mWindow.getDecorView();
        WindowManager.LayoutParams l = mWindow.getAttributes();
        try {
            mWindowManager.addView(mDecor, l);
            android.view.ArtzigWindowStack.setOnBack(mDecor, this::onBackPressed);
        } catch (Throwable t) {
            Log.w(TAG, "show failed", t);
        }
        mShowing = true;
        if (mShowListener != null) mShowListener.onShow(this);
    }

    public void hide() {
        if (mDecor != null) mDecor.setVisibility(View.GONE);
    }

    @Override
    public void dismiss() {
        if (!mShowing) return;
        mShowing = false;
        if (mDecor != null) {
            try {
                mWindowManager.removeViewImmediate(mDecor);
            } catch (Throwable t) {
                Log.w(TAG, "dismiss failed", t);
            }
        }
        mDecor = null;
        onStop();
        mCreated = false;
        if (mDismissListener != null) mDismissListener.onDismiss(this);
    }

    @Override
    public void cancel() {
        if (mCancelListener != null) mCancelListener.onCancel(this);
        dismiss();
    }

    public boolean isShowing() { return mShowing; }

    protected void onCreate(Bundle savedInstanceState) {}
    protected void onStart() {}
    protected void onStop() {}

    public void setContentView(int layoutResID) { mWindow.setContentView(layoutResID); }
    public void setContentView(View view) { mWindow.setContentView(view); }
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        mWindow.setContentView(view, params);
    }
    public void addContentView(View view, ViewGroup.LayoutParams params) {
        mWindow.addContentView(view, params);
    }

    public <T extends View> T findViewById(int id) { return mWindow.findViewById(id); }
    public final <T extends View> T requireViewById(int id) {
        T view = findViewById(id);
        if (view == null) throw new IllegalArgumentException("ID does not reference a View");
        return view;
    }

    public void setTitle(CharSequence title) { mWindow.setTitle(title); }
    public void setTitle(int titleId) { setTitle(mContext.getText(titleId)); }

    public void setCancelable(boolean flag) { mCancelable = flag; }
    public void setCanceledOnTouchOutside(boolean cancel) {
        if (cancel && !mCancelable) mCancelable = true;
        mCanceledOnTouchOutside = cancel;
    }

    public void setOnCancelListener(DialogInterface.OnCancelListener listener) {
        mCancelListener = listener;
    }
    public void setOnDismissListener(DialogInterface.OnDismissListener listener) {
        mDismissListener = listener;
    }
    public void setOnShowListener(DialogInterface.OnShowListener listener) {
        mShowListener = listener;
    }
    public void setOnKeyListener(DialogInterface.OnKeyListener onKeyListener) {
        mOnKeyListener = onKeyListener;
    }

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

    public Bundle onSaveInstanceState() { return new Bundle(); }
    public void onRestoreInstanceState(Bundle savedInstanceState) {}

    public void onBackPressed() {
        if (mCancelable) cancel();
        else dismiss();
    }

    // ---- input dispatch ----
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_OUTSIDE) {
            if (mCancelable && mCanceledOnTouchOutside && mShowing) {
                cancel();
                return true;
            }
            return false;
        }
        if (mWindow.superDispatchTouchEvent(ev)) return true;
        return onTouchEvent(ev);
    }
    public boolean onTouchEvent(MotionEvent event) { return false; }

    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mOnKeyListener != null
                && mOnKeyListener.onKey(this, event.getKeyCode(), event)) return true;
        if (mWindow.superDispatchKeyEvent(event)) return true;
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_UP) {
            onBackPressed();
            return true;
        }
        return false;
    }
    public boolean dispatchKeyShortcutEvent(KeyEvent event) { return false; }
    public boolean dispatchTrackballEvent(MotionEvent ev) { return false; }
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        return mWindow.superDispatchGenericMotionEvent(ev);
    }
    public boolean dispatchPopulateAccessibilityEvent(
            android.view.accessibility.AccessibilityEvent event) { return false; }

    // ---- KeyEvent.Callback ----
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) { return false; }
    @Override public boolean onKeyLongPress(int keyCode, KeyEvent event) { return false; }
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) { onBackPressed(); return true; }
        return false;
    }
    @Override public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        return false;
    }

    // ---- Window.Callback ----
    @Override public View onCreatePanelView(int featureId) { return null; }
    @Override public boolean onCreatePanelMenu(int featureId, Menu menu) { return false; }
    @Override public boolean onPreparePanel(int featureId, View view, Menu menu) { return false; }
    @Override public boolean onMenuOpened(int featureId, Menu menu) { return true; }
    @Override public boolean onMenuItemSelected(int featureId, MenuItem item) { return false; }
    @Override public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
        if (mDecor != null) mWindowManager.updateViewLayout(mDecor, attrs);
    }
    @Override public void onContentChanged() {}
    @Override public void onWindowFocusChanged(boolean hasFocus) {}
    @Override public void onAttachedToWindow() {}
    @Override public void onDetachedFromWindow() {}
    @Override public void onPanelClosed(int featureId, Menu menu) {}
    @Override public boolean onSearchRequested() { return false; }
    @Override public boolean onSearchRequested(android.view.SearchEvent searchEvent) {
        return false;
    }
    @Override public android.view.ActionMode onWindowStartingActionMode(
            android.view.ActionMode.Callback callback) { return null; }
    @Override public android.view.ActionMode onWindowStartingActionMode(
            android.view.ActionMode.Callback callback, int type) { return null; }
    @Override public void onActionModeStarted(android.view.ActionMode mode) {}
    @Override public void onActionModeFinished(android.view.ActionMode mode) {}

    @Override public void onCreateContextMenu(android.view.ContextMenu menu, View v,
            android.view.ContextMenu.ContextMenuInfo menuInfo) {}

    public void takeKeyEvents(boolean get) {}
    public final boolean requestWindowFeature(int featureId) {
        return mWindow.requestFeature(featureId);
    }
    public void invalidateOptionsMenu() {}
    public void openOptionsMenu() {}
    public void closeOptionsMenu() {}
    public void registerForContextMenu(View view) {}
    public void unregisterForContextMenu(View view) {}
    public void openContextMenu(View view) {}
    public boolean onContextItemSelected(MenuItem item) { return false; }
    public void onContextMenuClosed(Menu menu) {}
    public boolean onOptionsItemSelected(MenuItem item) { return false; }
    public boolean onCreateOptionsMenu(Menu menu) { return false; }
    public boolean onPrepareOptionsMenu(Menu menu) { return false; }
    public void onOptionsMenuClosed(Menu menu) {}
    public boolean onMenuItemSelected$check(int featureId, MenuItem item) { return false; }
    public void onDetachedFromWindow$noop() {}
}
