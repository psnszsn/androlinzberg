/* artzig: hand-written ViewRootImpl (ATL pattern) — the stock one is hard-coupled to
 * IWindowSession/InputChannel/DisplayEventReceiver. This one drives the view tree
 * over the Wayland-backed surface: measure/layout/record into HWUI display lists
 * each Choreographer frame. Grows as the real View demands more of its API. */
package android.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;

public class ViewRootImpl implements ViewParent, AttachedSurfaceControl {
    public final Context mContext;
    final Handler mHandler;
    final Choreographer mChoreographer;
    View mView;
    int mWidth, mHeight;
    final RenderNode mRenderNode = new RenderNode("artzig-view-root");
    boolean mTraversalScheduled;
    boolean mLayoutRequested;
    Runnable mFrameListener;
    private final ArrayList<View> mInvalidateViews = new ArrayList<>();
    private final ArrayList<View.AttachInfo.InvalidateInfo> mInvalidateRects = new ArrayList<>();
    private boolean mInvalidatePosted;
    private final Runnable mInvalidateOnAnimation = () -> {
        final ArrayList<View> views;
        final ArrayList<View.AttachInfo.InvalidateInfo> rects;
        synchronized (mInvalidateViews) {
            mInvalidatePosted = false;
            views = new ArrayList<>(mInvalidateViews);
            rects = new ArrayList<>(mInvalidateRects);
            mInvalidateViews.clear();
            mInvalidateRects.clear();
        }
        for (View view : views) view.invalidate();
        for (View.AttachInfo.InvalidateInfo info : rects) {
            info.target.invalidate(info.left, info.top, info.right, info.bottom);
            info.recycle();
        }
    };

    public ViewRootImpl(Context context) {
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        mChoreographer = Choreographer.getInstance();
    }

    public void setView(View view, int width, int height) {
        // re-showing a decor that was rooted before (in-process activity stack):
        // detach it from its old root first.
        if (view.getParent() instanceof ViewRootImpl && view.getParent() != this) {
            ViewRootImpl old = (ViewRootImpl) view.getParent();
            view.dispatchDetachedFromWindow();
            view.assignParent(null);
            old.mView = null;
        }
        mView = view;
        mWidth = width;
        mHeight = height;
        if (mAttachInfo == null) {
            IWindow window = new IWindow.Default() {
                private final android.os.Binder mBinder = new android.os.Binder("artzig-window");
                @Override public android.os.IBinder asBinder() { return mBinder; }
            };
            mAttachInfo = new View.AttachInfo(null /* session */, window, mDisplay, this,
                    mHandler, new View.AttachInfo.Callbacks() {
                        @Override public void playSoundEffect(int effectId) {}
                        @Override public boolean performHapticFeedback(int effectId, int flags, int privFlags) { return false; }
                        @Override public void performHapticFeedbackForInputDevice(int effectId,
                                int inputDeviceId, int inputSource, int flags, int privFlags) {}
                    }, mContext);
            // Transitions key their animators by WindowId.  The platform normally
            // obtains this token from IWindowSession, which artzig deliberately
            // does not use, so provide an equivalent process-local identity.
            IWindowId windowId = new IWindowId.Stub() {
                @Override public void registerFocusObserver(IWindowFocusObserver observer) {}
                @Override public void unregisterFocusObserver(IWindowFocusObserver observer) {}
                @Override public boolean isFocused() { return true; }
            };
            mAttachInfo.mIWindowId = windowId;
            mAttachInfo.mWindowId = new WindowId(windowId);
            mAttachInfo.mHardwareAccelerated = true;
            mAttachInfo.mThreadedRenderer = ThreadedRenderer.getInstance();
            mAttachInfo.mInTouchMode = true; // pointer taps act as touches; no focus rings
            mAttachInfo.mWindowVisibility = View.VISIBLE;
            mAttachInfo.mHasWindowFocus = true;
        }
        view.assignParent(this);
        view.dispatchAttachedToWindow(mAttachInfo, View.VISIBLE);
        view.dispatchWindowVisibilityChanged(View.VISIBLE);
        view.dispatchWindowFocusChanged(true);
        requestLayout();
    }
    public View getView() { return mView; }

    /** Detach the hosted hierarchy and release references held by this root. */
    public void destroy() {
        if (mFrameListener != null) mHandler.removeCallbacks(mFrameListener);
        mHandler.removeCallbacks(mInvalidateOnAnimation);
        if (mView != null) {
            mView.dispatchWindowFocusChanged(false);
            mView.dispatchWindowVisibilityChanged(View.GONE);
            mView.dispatchDetachedFromWindow();
            mView.assignParent(null);
            mView = null;
        }
        mInvalidateViews.clear();
        for (View.AttachInfo.InvalidateInfo info : mInvalidateRects) info.recycle();
        mInvalidateRects.clear();
        mAttachInfo = null;
        mRenderNode.discardDisplayList();
    }

    /** Overlay-window traversal (artzig): size by LayoutParams within max bounds,
     *  record a transparent display list, and return its RenderNode. */
    public RenderNode doTraversalWindow(WindowManager.LayoutParams lp, int maxW, int maxH) {
        if (mView == null) return null;
        mTraversalScheduled = false;
        mLayoutRequested = false;
        computeScrollTree(mView);
        int ws = lp.width == WindowManager.LayoutParams.MATCH_PARENT
                ? View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.EXACTLY)
                : lp.width > 0
                        ? View.MeasureSpec.makeMeasureSpec(Math.min(lp.width, maxW),
                                View.MeasureSpec.EXACTLY)
                        : View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST);
        int hs = lp.height == WindowManager.LayoutParams.MATCH_PARENT
                ? View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.EXACTLY)
                : lp.height > 0
                        ? View.MeasureSpec.makeMeasureSpec(Math.min(lp.height, maxH),
                                View.MeasureSpec.EXACTLY)
                        : View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST);
        mView.measure(ws, hs);
        int w = Math.max(1, mView.getMeasuredWidth());
        int h = Math.max(1, mView.getMeasuredHeight());
        mWidth = w;
        mHeight = h;
        mView.layout(0, 0, w, h);
        dispatchOnPreDraw();
        updateSurfacePositions(mView);
        mRenderNode.setPosition(0, 0, w, h);
        RecordingCanvas canvas = mRenderNode.beginRecording(w, h);
        try {
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
            mView.draw(canvas);
        } finally {
            mRenderNode.endRecording();
        }
        return mRenderNode;
    }

    /** Window resized (artzig). */
    public void setSize(int width, int height) {
        mWidth = width;
        mHeight = height;
        requestLayout();
        if (mView != null) mView.requestLayout();
    }

    /** Runs a full traversal now and records the root display list. */
    public RenderNode doTraversal() {
        if (mView == null) return null;
        mTraversalScheduled = false;
        mLayoutRequested = false;
        computeScrollTree(mView);
        mView.measure(View.MeasureSpec.makeMeasureSpec(mWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(mHeight, View.MeasureSpec.EXACTLY));
        mView.layout(0, 0, mWidth, mHeight);
        dispatchOnPreDraw();
        updateSurfacePositions(mView);
        mRenderNode.setPosition(0, 0, mWidth, mHeight);
        RecordingCanvas canvas = mRenderNode.beginRecording(mWidth, mHeight);
        try {
            canvas.drawColor(0xff000000);
            mView.draw(canvas);
        } finally {
            mRenderNode.endRecording();
        }
        return mRenderNode;
    }

    /** Run callbacks which Android normally dispatches between layout and draw.
     * Delayed transitions (including PopupWindow dismissal) depend on this. */
    private void dispatchOnPreDraw() {
        if (mAttachInfo != null && mAttachInfo.mTreeObserver.dispatchOnPreDraw()) {
            scheduleTraversals();
        }
    }

    /** Our root records a host RenderNode every compositor frame while child
     * RenderNodes retain Android's normal dirty-bit caching. Advance nested
     * scrollers explicitly so a child that invalidates itself from
     * computeScroll() cannot lose the final settle frame during recording. */
    private static void computeScrollTree(View view) {
        view.computeScroll();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); ++i) {
                computeScrollTree(group.getChildAt(i));
            }
        }
    }

    private static void updateSurfacePositions(View view) {
        if (view instanceof SurfaceView) {
            ((SurfaceView) view).updateSurfacePosition$artzig();
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); ++i) {
                updateSurfacePositions(group.getChildAt(i));
            }
        }
    }

    void scheduleTraversals() {
        if (!mTraversalScheduled) {
            mTraversalScheduled = true;
            if (mFrameListener != null) mHandler.post(mFrameListener);
        }
    }

    // ---- ViewParent ----
    @Override public void requestLayout() { mLayoutRequested = true; scheduleTraversals(); }
    @Override public boolean isLayoutRequested() { return mLayoutRequested; }
    @Override public ViewParent getParent() { return null; }
    @Override public void requestTransparentRegion(View child) {}
    @Override public ViewParent invalidateChildInParent(int[] location, Rect dirty) {
        scheduleTraversals();
        return null;
    }
    @Override public void invalidateChild(View child, Rect dirty) { scheduleTraversals(); }
    @Override public boolean getChildVisibleRect(View child, Rect r, android.graphics.Point offset) { return true; }
    @Override public View focusSearch(View v, int direction) { return null; }
    @Override public void bringChildToFront(View child) {}
    @Override public void focusableViewAvailable(View v) {}
    @Override public boolean showContextMenuForChild(View originalView) { return false; }
    @Override public boolean showContextMenuForChild(View originalView, float x, float y) { return false; }
    @Override public ActionMode startActionModeForChild(View originalView, ActionMode.Callback callback) { return null; }
    @Override public ActionMode startActionModeForChild(View originalView, ActionMode.Callback callback, int type) { return null; }
    @Override public void childDrawableStateChanged(View child) {}
    @Override public void createContextMenu(ContextMenu menu) {}
    @Override public void childHasTransientStateChanged(View child, boolean hasTransientState) {}
    @Override public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {}
    @Override public void requestChildFocus(View child, View focused) {}
    @Override public void recomputeViewAttributes(View child) {}
    @Override public void clearChildFocus(View child) {}
    @Override public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) { return false; }
    @Override public boolean requestSendAccessibilityEvent(View child, android.view.accessibility.AccessibilityEvent event) { return false; }
    @Override public void notifySubtreeAccessibilityStateChanged(View child, View source, int changeType) {}
    @Override public boolean canResolveLayoutDirection() { return true; }
    @Override public boolean isLayoutDirectionResolved() { return true; }
    @Override public int getLayoutDirection() { return View.LAYOUT_DIRECTION_LTR; }
    @Override public boolean canResolveTextDirection() { return true; }
    @Override public boolean isTextDirectionResolved() { return true; }
    @Override public int getTextDirection() { return View.TEXT_DIRECTION_LTR; }
    @Override public boolean canResolveTextAlignment() { return true; }
    @Override public boolean isTextAlignmentResolved() { return true; }
    @Override public int getTextAlignment() { return View.TEXT_ALIGNMENT_GRAVITY; }
    @Override public boolean onStartNestedScroll(View child, View target, int nestedScrollAxes) { return false; }
    @Override public void onNestedScrollAccepted(View child, View target, int nestedScrollAxes) {}
    @Override public void onStopNestedScroll(View target) {}
    @Override public void onNestedScroll(View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed) {}
    @Override public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) {}
    @Override public boolean onNestedFling(View target, float velocityX, float velocityY, boolean consumed) { return false; }
    @Override public boolean onNestedPreFling(View target, float velocityX, float velocityY) { return false; }
    @Override public boolean onNestedPrePerformAccessibilityAction(View target, int action, android.os.Bundle arguments) { return false; }
    @Override public View keyboardNavigationClusterSearch(View currentCluster, int direction) { return null; }

    // ---- surface used by View/ViewGroup beyond ViewParent (grown on demand) ----
    public final WindowManager.LayoutParams mWindowAttributes = new WindowManager.LayoutParams();
    public View.AttachInfo mAttachInfo;
    public Display mDisplay;
    public int mCurScrollY;
    private HandwritingInitiator mHandwritingInitiator;
    private InsetsController mInsetsController;
    public HandwritingInitiator getHandwritingInitiator() {
        if (mHandwritingInitiator == null) mHandwritingInitiator = new HandwritingInitiator();
        return mHandwritingInitiator;
    }
    public InsetsController getInsetsController() {
        if (mInsetsController == null) {
            try {
                mInsetsController = new InsetsController(new ArtzigInsetsHost());
            } catch (Throwable t) {
                android.util.Log.w("ViewRootImpl", "InsetsController unavailable", t);
            }
        }
        return mInsetsController;
    }

    private final class ArtzigInsetsHost implements InsetsController.Host {
        @Override public android.os.Handler getHandler() { return mHandler; }
        @Override public void notifyInsetsChanged() {}
        @Override public void dispatchWindowInsetsAnimationPrepare(WindowInsetsAnimation animation) {}
        @Override public WindowInsetsAnimation.Bounds dispatchWindowInsetsAnimationStart(
                WindowInsetsAnimation animation, WindowInsetsAnimation.Bounds bounds) { return bounds; }
        @Override public WindowInsets dispatchWindowInsetsAnimationProgress(WindowInsets insets,
                java.util.List<WindowInsetsAnimation> runningAnimations) { return insets; }
        @Override public void dispatchWindowInsetsAnimationEnd(WindowInsetsAnimation animation) {}
        @Override public void applySurfaceParams(SyncRtSurfaceTransactionApplier.SurfaceParams... params) {}
        @Override public void updateRequestedVisibleTypes(int types,
                android.view.inputmethod.ImeTracker.Token statsToken) {}
        @Override public boolean hasAnimationCallbacks() { return false; }
        @Override public void setSystemBarsAppearance(int appearance, int mask) {}
        @Override public int getSystemBarsAppearance() { return 0; }
        @Override public void setSystemBarsBehavior(int behavior) {}
        @Override public int getSystemBarsBehavior() { return 0; }
        @Override public void releaseSurfaceControlFromRt(SurfaceControl surfaceControl) {}
        @Override public void addOnPreDrawRunnable(Runnable r) { mHandler.post(r); }
        @Override public void postInsetsAnimationCallback(Runnable r) { mHandler.post(r); }
        @Override public android.view.inputmethod.InputMethodManager getInputMethodManager() {
            return (android.view.inputmethod.InputMethodManager)
                    mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
        }
        @Override public String getRootViewTitle() { return "artzig"; }
        @Override public int dipToPx(int dips) { return dips; }
        @Override public android.os.IBinder getWindowToken() { return null; }
    }
    public int getLastClickToolType() { return 0; }
    public int getLastTouchSource() { return 0; }
    public int getLastTouchPointerId() { return 0; }
    public int getLastTouchDeviceId() { return -1; }
    public static boolean isViewDescendantOf(View child, View parent) {
        if (child == parent) return true;
        Object p = child.getParent();
        return (p instanceof View) && isViewDescendantOf((View) p, parent);
    }
    private ImeFocusController mImeFocusController;
    public ImeFocusController getImeFocusController() {
        if (mImeFocusController == null) {
            mImeFocusController = new ImeFocusController(this);
            try {
                java.lang.reflect.Field f = ImeFocusController.class.getDeclaredField("mDelegate");
                f.setAccessible(true);
                f.set(mImeFocusController, new ImeFocusController.InputMethodManagerDelegate() {
                    @Override public void onPreWindowGainedFocus(ViewRootImpl viewRootImpl) {}
                    @Override public void onPostWindowGainedFocus(View viewForWindowFocus,
                            WindowManager.LayoutParams windowAttribute) {}
                    @Override public void onWindowLostFocus(ViewRootImpl viewRootImpl) {}
                    @Override public void onViewFocusChanged(View view, boolean hasFocus) {}
                    @Override public void onScheduledCheckFocus(ViewRootImpl viewRootImpl) {}
                    @Override public void onViewDetachedFromWindow(View view, ViewRootImpl viewRootImpl) {}
                    @Override public void onWindowDismissed(ViewRootImpl viewRootImpl) {}
                });
            } catch (Throwable ignored) {}
        }
        return mImeFocusController;
    }
    public View getAccessibilityFocusedHost() { return null; }
    public Surface mSurface = new Surface();
    public android.os.IBinder mLeashToken;
    public void dispatchKeyFromIme(KeyEvent event) {}
    public void dispatchKeyFromAutofill(KeyEvent event) {}
    public boolean mWindowAttributesChanged;
    public void requestPointerCapture(boolean enabled) {}
    public boolean hasPointerCapture() { return false; }
    public void notifyRendererOfExpensiveFrame() {}
    public void notifyRendererOfFramePause() {}
    public void setDragStartedViewForAccessibility(View view) {}
    public static void outputDisplayList(View view) {}
    public void removeThreadedRendererView(View view) {}
    public void addThreadedRendererView(View view) {}
    public int getWindowFlags() { return mWindowAttributes.flags; }
    public void getDisplayFrame(android.graphics.Rect outFrame) { outFrame.set(0, 0, mWidth, mHeight); }
    public boolean isInTouchMode() { return true; }
    public boolean ensureTouchMode(boolean inTouchMode) { return false; }
    public void votePreferredFrameRateCategory(int category, int reason, View view) {}
    public void updateSystemGestureExclusionRectsForView(View view) {}
    public void transformMatrixToLocal(android.graphics.Matrix matrix) {}
    public void transformMatrixToGlobal(android.graphics.Matrix matrix) {}
    public boolean isSameResourcesOverrideConfig(android.os.IBinder token, android.content.res.Configuration config) { return true; }
    public void setAccessibilityFocus(View view, android.view.accessibility.AccessibilityNodeInfo node) {}
    public final ViewFrameInfo mViewFrameInfo = new ViewFrameInfo();
    public Thread mThread = Thread.currentThread();
    public void addObserver(android.graphics.HardwareRendererObserver observer) {}
    public void removeObserver(android.graphics.HardwareRendererObserver observer) {}
    public void dumpDebug(android.util.proto.ProtoOutputStream proto, long fieldId) {}
    public int getDisplayId() { return 0; }
    public android.os.IBinder getWindowToken() { return null; }
    public void onInputConnectionCreated(View view) {}
    public boolean mAdded = true;
    public boolean mIsAnimating;
    public android.content.res.CompatibilityInfo.Translator mTranslator;
    public IWindowSession mWindowSession;
    public IWindow mWindow;
    public int mScrollY;
    public static final int INTERMITTENT_STATE_NOT_INTERMITTENT = 0,
            INTERMITTENT_STATE_IN_TRANSITION = 1, INTERMITTENT_STATE_INTERMITTENT = 2;
    public int getIntermittentState() { return INTERMITTENT_STATE_NOT_INTERMITTENT; }
    public void notifyInsetsChanged() {}
    public int getHostVisibility() { return View.VISIBLE; }
    public void updateCompatSysUiVisibility(int type, int visibility, int mask) {}
    public void registerRtFrameCallback(android.graphics.HardwareRenderer.FrameDrawingCallback callback) {}
    public int dipToPx(int dip) { return dip; }
    public void notifyInsetsAnimationRunningStateChanged(boolean running) {}
    public void updateKeepClearRectsForView(View view) {}
    public boolean shouldCheckFrameRateCategory() { return false; }
    public boolean shouldCheckFrameRate(boolean b) { return false; }
    public void setLocalDragState(Object obj) {}
    public void dispatchCheckFocus() {}
    public CharSequence getTitle() { return ""; }
    public boolean isHandlingPointerEvent() { return false; }
    public void setDragFocus(View newDragTarget, DragEvent event) {}
    public boolean performHapticFeedback(int effectId, boolean always, boolean fromIme) { return false; }
    public void playSoundEffect(int effectId) {}
    public View getCommonPredecessor(View first, View second) { return null; }
    public void notifyEnterAnimationComplete() {}
    public ViewParent getParentForAccessibility() { return null; }
    @Override public void requestFitSystemWindows() {}
    public WindowInsets getWindowInsets(boolean forceConstruct) { return WindowInsets.CONSUMED; }
    public void getWindowVisibleDisplayFrame(android.graphics.Rect outRect) { outRect.set(0, 0, mWidth, mHeight); }
    public void dispatchInvalidateDelayed(View view, long delayMilliseconds) {
        mHandler.postDelayed(view::invalidate, delayMilliseconds);
    }
    public void dispatchInvalidateRectDelayed(View.AttachInfo.InvalidateInfo info, long delayMilliseconds) {
        mHandler.postDelayed(() -> {
            info.target.invalidate(info.left, info.top, info.right, info.bottom);
            info.recycle();
        }, delayMilliseconds);
    }
    public void dispatchInvalidateOnAnimation(View view) {
        synchronized (mInvalidateViews) {
            mInvalidateViews.add(view);
            postInvalidateCallbackLocked();
        }
    }
    public void dispatchInvalidateRectOnAnimation(View.AttachInfo.InvalidateInfo info) {
        synchronized (mInvalidateViews) {
            mInvalidateRects.add(info);
            postInvalidateCallbackLocked();
        }
    }
    private void postInvalidateCallbackLocked() {
        if (!mInvalidatePosted) {
            mInvalidatePosted = true;
            mChoreographer.postCallback(Choreographer.CALLBACK_ANIMATION,
                    mInvalidateOnAnimation, null);
        }
    }
    public void cancelInvalidate(View view) {
        synchronized (mInvalidateViews) {
            mInvalidateViews.remove(view);
            for (int i = mInvalidateRects.size() - 1; i >= 0; --i) {
                View.AttachInfo.InvalidateInfo info = mInvalidateRects.get(i);
                if (info.target == view) {
                    mInvalidateRects.remove(i);
                    info.recycle();
                }
            }
            if (mInvalidatePosted && mInvalidateViews.isEmpty() && mInvalidateRects.isEmpty()) {
                mChoreographer.removeCallbacks(Choreographer.CALLBACK_ANIMATION,
                        mInvalidateOnAnimation, null);
                mInvalidatePosted = false;
            }
        }
    }
    public void dispatchCompatFakeFocus() {}
    public boolean probablyHasInput() { return false; }
    public void applyViewLocationSandboxingIfNeeded(int[] outLocation) {}
    public boolean requestLayoutDuringLayout(View view) { return true; }
    public void getLastTouchPoint(android.graphics.Point outPoint) { outPoint.set(0, 0); }
    public SurfaceControl getSurfaceControl() { return new SurfaceControl(); }
    public void mergeWithNextTransaction(SurfaceControl.Transaction t, long frameNumber) { t.apply(); }
    public void registerAnimatingRenderNode(android.graphics.RenderNode renderNode) {
        ThreadedRenderer.getInstance().registerAnimatingRenderNode(renderNode);
        scheduleTraversals();
    }
    public void registerVectorDrawableAnimator(NativeVectorDrawableAnimator animator) {
        ThreadedRenderer.getInstance().registerVectorDrawableAnimator(animator);
        scheduleTraversals();
    }
    public void requestTransitionStart(android.animation.LayoutTransition transition) {}
    public void refreshPointerIcon() {}
    public void addSensitiveContentAppProtection() {}
    public void removeSensitiveContentAppProtection() {}
    public int intermittentUpdateState() { return INTERMITTENT_STATE_NOT_INTERMITTENT; }
    public void recordViewPercentage(float sizePercentage) {}
    public void applyViewBoundsSandboxingIfNeeded(android.graphics.Rect outRect) {}
    public int getWidth() { return mWidth; }
    public int getHeight() { return mHeight; }
    public void dispatchInputEvent(InputEvent event) {}
    public boolean injectBackKeyEvents(boolean preImeOnly) { return false; }
    public void dispatchBlurRegions(float[][] blurRegions, long frameNumber) {}
    public com.android.internal.graphics.drawable.BackgroundBlurDrawable createBackgroundBlurDrawable() { return null; }
    public void addWindowCallbacks(WindowCallbacks callbacks) {}
    public void removeWindowCallbacks(WindowCallbacks callbacks) {}
    public void reportDrawFinish() {}
    public boolean dispatchUnhandledKeyEvent(KeyEvent event) { return false; }
    public void updateDecorViewGestureInterception(boolean intercepting) {}
    public void windowFocusChanged(boolean hasFocus) {}
    public void touchModeChanged(boolean inTouchMode) {}
    public void setRootSystemGestureExclusionRects(java.util.List<android.graphics.Rect> rects) {}
    public java.util.List<android.graphics.Rect> getRootSystemGestureExclusionRects() { return new java.util.ArrayList<>(); }
    public void setOnContentApplyWindowInsetsListener(Window.OnContentApplyWindowInsetsListener listener) {}
    public void dispatchScrollCaptureRequest(IScrollCaptureResponseListener listener) {}
    public void addScrollCaptureCallback(ScrollCaptureCallback callback) {}
    public void removeScrollCaptureCallback(ScrollCaptureCallback callback) {}
    public void onWindowTitleChanged() {}
    // ---- AttachedSurfaceControl ----
    @Override public SurfaceControl.Transaction buildReparentTransaction(SurfaceControl child) { return null; }
    @Override public boolean applyTransactionOnDraw(SurfaceControl.Transaction t) { t.apply(); return true; }
    public void setScrollY(int scrollY) { mScrollY = scrollY; }
    public int getScrollY() { return mScrollY; }
    public final java.util.concurrent.Executor mExecutor = new java.util.concurrent.Executor() {
        @Override public void execute(Runnable r) { mHandler.post(r); }
    };
    public int getDirectAccessibilityConnectionId() { return -1; }
    public interface ActivityConfigCallback {
        void onConfigurationChanged(android.content.res.Configuration overrideConfig, int newDisplayId);
        default void requestCompatCameraControl(boolean showControl, boolean transformationApplied,
                Object callback) {}
    }
    public void setActivityConfigCallback(ActivityConfigCallback callback) {}
    public interface ConfigChangedCallback {
        void onConfigurationChanged(android.content.res.Configuration globalConfig);
    }
    public static void addConfigCallback(ConfigChangedCallback callback) {}
    public static void removeConfigCallback(ConfigChangedCallback callback) {}
    public android.view.accessibility.AccessibilityNodeInfo getAccessibilityFocusedVirtualView() { return null; }
    private android.window.WindowOnBackInvokedDispatcher mOnBackInvokedDispatcher;
    public android.window.WindowOnBackInvokedDispatcher getOnBackInvokedDispatcher() {
        if (mOnBackInvokedDispatcher == null) {
            mOnBackInvokedDispatcher = new android.window.WindowOnBackInvokedDispatcher(mContext, android.os.Looper.myLooper());
        }
        return mOnBackInvokedDispatcher;
    }
    public boolean isInLayout() { return false; }
    public void requestInvalidateRootRenderNode() {}
    public void invalidate() { scheduleTraversals(); }
    public boolean isFrameRatePowerSavingsBalanced() { return false; }
    public void votePreferredFrameRate(float frameRate, int type) {}
    public Handler getHandler() { return mHandler; }
    public Context getContext() { return mContext; }
}
