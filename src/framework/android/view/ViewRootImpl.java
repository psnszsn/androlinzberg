/* artzig: hand-written ViewRootImpl (ATL pattern) — the stock one is hard-coupled to
 * IWindowSession/InputChannel/DisplayEventReceiver. This one drives the view tree
 * over the Wayland-backed surface: measure/layout/record into HWUI display lists
 * each Choreographer frame. Grows as the real View demands more of its API. */
package android.view;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.PointF;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayList;

public class ViewRootImpl implements ViewParent, AttachedSurfaceControl {
    private static final boolean TRACE_SLOW_FRAMES =
            System.getenv("ARTZIG_SLOW_FRAME_TRACE") != null;
    private static long sNextArtzigAccessibilityWindowId = 1;
    final long mArtzigAccessibilityWindowId = sNextArtzigAccessibilityWindowId++;
    public final Context mContext;
    final Handler mHandler;
    final Choreographer mChoreographer;
    View mView;
    int mWidth, mHeight;
    final RenderNode mRenderNode = new RenderNode("artzig-view-root");
    boolean mTraversalScheduled;
    int mTraversalBarrier = -1;
    boolean mLayoutRequested;
    boolean mInLayout;
    boolean mHandlingLayoutInLayoutRequest;
    private final ArrayList<View> mLayoutRequesters = new ArrayList<>();
    Runnable mFrameListener;
    private final Runnable mTraversalRunnable = this::doTraversal;
    private boolean mHasRenderNode;
    private boolean mHostedWindowLayout;
    private WindowManager.LayoutParams mHostedWindowParams;
    private int mHostedWindowMaxWidth;
    private int mHostedWindowMaxHeight;
    private int mHostedWindowParamWidth = Integer.MIN_VALUE;
    private int mHostedWindowParamHeight = Integer.MIN_VALUE;
    private final ArrayList<View> mInvalidateViews = new ArrayList<>();
    private final ArrayList<View.AttachInfo.InvalidateInfo> mInvalidateRects = new ArrayList<>();
    private boolean mInvalidatePosted;
    private boolean mApplyInsetsScheduled;
    private int mTypesHiddenByLegacySystemUi;
    private PointerIcon mResolvedPointerIcon;
    private final PointF mLastTouchPoint = new PointF();
    private int mLastTouchSource;
    private int mLastTouchDeviceId = KeyCharacterMap.VIRTUAL_KEYBOARD;
    private int mLastTouchPointerId;
    private int mLastClickToolType = MotionEvent.TOOL_TYPE_UNKNOWN;
    private boolean mPointerCapture;
    private final WindowInsets mWindowInsets = new WindowInsets.Builder()
            .setInsets(WindowInsets.Type.systemBars(), android.graphics.Insets.NONE)
            .build();
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
        // ThreadedRenderer.updateRootDisplayList() records a stable reference to
        // the view-owned RenderNode. A different decor therefore needs a new
        // wrapper display list even when this ViewRootImpl is reused.
        mHasRenderNode = false;
        view.dispatchAttachedToWindow(mAttachInfo, View.VISIBLE);
        view.dispatchWindowVisibilityChanged(View.VISIBLE);
        view.dispatchWindowFocusChanged(true);
        mAttachInfo.mRecomputeGlobalAttributes = true;
        requestLayout();
        requestFitSystemWindows();
        ArtzigAccessibilityBridge.markChanged("root-attached");
    }
    public View getView() { return mView; }

    /**
     * AOSP EarlyPostImeInputStage.processPointerEvent() followed by
     * ViewPostImeInputStage.processPointerEvent(), adapted because InputChannel,
     * InputEventReceiver, IME input queues, and WindowManagerService are absent.
     */
    public boolean dispatchPointerEvent(MotionEvent event) {
        if (mView == null || mAttachInfo == null) return false;

        // EarlyPostImeInputStage ordering.
        if (mTranslator != null) mTranslator.translateEventInScreenToAppWindow(event);
        final int action = event.getAction();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_SCROLL) {
            ensureTouchMode(true);
        }
        if (action == MotionEvent.ACTION_DOWN) {
            android.view.autofill.AutofillManager autofill =
                    mContext.getSystemService(android.view.autofill.AutofillManager.class);
            if (autofill != null) autofill.requestHideFillUi();
            if (mAttachInfo.mTooltipHost != null) mAttachInfo.mTooltipHost.hideTooltip();
        }
        if (mCurScrollY != 0) event.offsetLocation(0, mCurScrollY);
        if (event.isTouchEvent()) {
            // Kept in the same EarlyPostImeInputStage position as AOSP. These values are consumed
            // by drag initiation and IME-facing ViewRootImpl APIs even though the host has no
            // system input queue.
            mLastTouchPoint.x = event.getRawX();
            mLastTouchPoint.y = event.getRawY();
            mLastTouchSource = event.getSource();
            mLastTouchDeviceId = event.getDeviceId();
            mLastTouchPointerId = event.getPointerId(0);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                mLastClickToolType = event.getToolType(event.getActionIndex());
            }
        }

        // ViewPostImeInputStage ordering. Handwriting is observationally inert in the host, but
        // remains ahead of view dispatch as it is in AOSP.
        boolean handled = getHandwritingInitiator().onTouchEvent(event);
        if (handled) {
            mLastClickToolType = event.getToolType(event.getActionIndex());
        }
        mAttachInfo.mUnbufferedDispatchRequested = false;
        mAttachInfo.mHandlingPointerEvent = true;
        try {
            if (!handled) handled = mView.dispatchPointerEvent(event);
            maybeUpdatePointerIcon(event);
            maybeUpdateTooltip(event);
            return handled;
        } finally {
            mAttachInfo.mHandlingPointerEvent = false;
            // There is no batched InputEventReceiver in the host. A request for unbuffered
            // dispatch is therefore already satisfied by this immediate synchronous dispatch.
        }
    }

    /**
     * AOSP ViewPreImeInputStage, EarlyPostImeInputStage, and ViewPostImeInputStage key ordering,
     * adapted because this host has no InputChannel or system IME input queue.
     */
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mView == null || mAttachInfo == null) return false;
        if (mView.dispatchKeyEventPreIme(event)) return true;
        if (mAttachInfo.mTooltipHost != null) {
            mAttachInfo.mTooltipHost.handleTooltipKey(event);
        }
        if (checkForLeavingTouchModeAndConsume(event)) return true;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            mLastClickToolType = MotionEvent.TOOL_TYPE_UNKNOWN;
        }
        if (mView.dispatchKeyEvent(event)) return true;
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && !KeyEvent.metaStateHasNoModifiers(event.getMetaState())
                && event.getRepeatCount() == 0
                && !KeyEvent.isModifierKey(event.getKeyCode())) {
            return mView.dispatchKeyShortcutEvent(event);
        }
        return false;
    }

    private boolean checkForLeavingTouchModeAndConsume(KeyEvent event) {
        if (!mAttachInfo.mInTouchMode) return false;
        final int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_MULTIPLE) return false;
        if ((event.getFlags() & KeyEvent.FLAG_KEEP_TOUCH_MODE) != 0) return false;
        if (event.hasNoModifiers() && isNavigationKey(event)) return ensureTouchMode(false);
        if (event.getUnicodeChar() > 0) ensureTouchMode(false);
        return false;
    }

    private static boolean isNavigationKey(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MOVE_HOME:
            case KeyEvent.KEYCODE_MOVE_END:
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
                return true;
            default:
                return false;
        }
    }

    /** AOSP ViewPostImeInputStage.processTrackballEvent() host adaptation. */
    public boolean dispatchCapturedPointerEvent(MotionEvent event) {
        if (mView == null || mAttachInfo == null) return false;
        if (event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) {
            if (!hasPointerCapture() || mView.dispatchCapturedPointerEvent(event)) return true;
        }
        return mView.dispatchTrackballEvent(event);
    }

    public int getResolvedPointerIconType() {
        return mResolvedPointerIcon != null
                ? mResolvedPointerIcon.getType() : PointerIcon.TYPE_ARROW;
    }

    private void maybeUpdatePointerIcon(MotionEvent event) {
        if (event.getPointerCount() != 1
                || !event.isFromSource(InputDevice.SOURCE_MOUSE)) return;
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_HOVER_ENTER
                || action == MotionEvent.ACTION_HOVER_EXIT) {
            mResolvedPointerIcon = null;
        }
        if (action == MotionEvent.ACTION_HOVER_EXIT) return;
        final float x = event.getX();
        final float y = event.getY();
        if (x < 0 || x >= mView.getWidth() || y < 0 || y >= mView.getHeight()) return;
        PointerIcon icon = mView.onResolvePointerIcon(event, 0);
        mResolvedPointerIcon = icon != null ? icon
                : PointerIcon.getSystemIcon(mContext, PointerIcon.TYPE_ARROW);
    }

    private void maybeUpdateTooltip(MotionEvent event) {
        if (event.getPointerCount() != 1) return;
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_HOVER_ENTER
                || action == MotionEvent.ACTION_HOVER_MOVE
                || action == MotionEvent.ACTION_HOVER_EXIT) {
            mView.dispatchTooltipHoverEvent(event);
        }
    }

    /** Detach the hosted hierarchy and release references held by this root. */
    public void destroy() {
        unscheduleTraversals();
        if (mFrameListener != null) mHandler.removeCallbacks(mFrameListener);
        mHandler.removeCallbacks(mInvalidateOnAnimation);
        HostedInputDispatcher.onRootDestroyed(this);
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
        mHasRenderNode = false;
        mRenderNode.discardDisplayList();
        ArtzigAccessibilityBridge.markChanged("root-destroyed");
    }

    /**
     * Supplies the host-owned window constraints before Choreographer's traversal phase.
     * AOSP obtains these constraints from WMS relayout; the hosted runtime has no WMS.
     */
    public void prepareWindowTraversal$artzig(WindowManager.LayoutParams lp, int maxW, int maxH) {
        if (maxW <= 0 || maxH <= 0) return;
        boolean changed = !mHostedWindowLayout
                || mHostedWindowMaxWidth != maxW || mHostedWindowMaxHeight != maxH
                || mHostedWindowParamWidth != lp.width || mHostedWindowParamHeight != lp.height;
        mHostedWindowLayout = true;
        mHostedWindowParams = lp;
        mHostedWindowMaxWidth = maxW;
        mHostedWindowMaxHeight = maxH;
        mHostedWindowParamWidth = lp.width;
        mHostedWindowParamHeight = lp.height;
        if (changed) {
            mHasRenderNode = false;
            requestLayout();
        }
    }

    /** Compatibility entry point for non-frame-loop callers. */
    public RenderNode doTraversalWindow(WindowManager.LayoutParams lp, int maxW, int maxH) {
        prepareWindowTraversal$artzig(lp, maxW, maxH);
        return doTraversal();
    }

    /** Window resized (artzig). */
    public void setSize(int width, int height) {
        if (mWidth != width || mHeight != height) mHasRenderNode = false;
        mWidth = width;
        mHeight = height;
        requestLayout();
        if (mView != null) mView.requestLayout();
    }

    /**
     * AOSP ViewRootImpl.doTraversal(), adapted to record a host-composited RenderNode instead of
     * submitting a SurfaceControl transaction. The synchronization barrier is removed before
     * measure/layout so View.post callbacks resume only after this traversal returns.
     */
    public RenderNode doTraversal() {
        if (!mTraversalScheduled) return getRenderNode$artzig();
        mTraversalScheduled = false;
        if (mTraversalBarrier >= 0) {
            mHandler.getLooper().getQueue().removeSyncBarrier(mTraversalBarrier);
            mTraversalBarrier = -1;
        }
        performTraversal();
        return getRenderNode$artzig();
    }

    public RenderNode getRenderNode$artzig() {
        return mView != null && mHasRenderNode ? mRenderNode : null;
    }

    private void performTraversal() {
        if (mView == null) return;
        int previousWidth = mWidth;
        int previousHeight = mHeight;
        long traceStart = TRACE_SLOW_FRAMES ? SystemClock.uptimeMillis() : 0;
        collectViewAttributes();
        long traceAttributes = TRACE_SLOW_FRAMES ? SystemClock.uptimeMillis() : 0;
        computeScrollTree(mView);
        long traceScroll = TRACE_SLOW_FRAMES ? SystemClock.uptimeMillis() : 0;
        // AOSP ViewRootImpl.performTraversals() measures and lays out only when
        // mLayoutRequested is set. Invalidations and animations still produce a
        // draw traversal, but must not repeatedly force the whole hierarchy
        // through measure/layout or fire OnLayoutChangeListener callbacks.
        if (mLayoutRequested) {
            int widthMeasureSpec;
            int heightMeasureSpec;
            if (mHostedWindowLayout && mHostedWindowParams != null) {
                WindowManager.LayoutParams lp = mHostedWindowParams;
                widthMeasureSpec = lp.width == WindowManager.LayoutParams.MATCH_PARENT
                        ? View.MeasureSpec.makeMeasureSpec(mHostedWindowMaxWidth,
                                View.MeasureSpec.EXACTLY)
                        : lp.width > 0
                                ? View.MeasureSpec.makeMeasureSpec(
                                        Math.min(lp.width, mHostedWindowMaxWidth),
                                        View.MeasureSpec.EXACTLY)
                                : View.MeasureSpec.makeMeasureSpec(mHostedWindowMaxWidth,
                                        View.MeasureSpec.AT_MOST);
                heightMeasureSpec = lp.height == WindowManager.LayoutParams.MATCH_PARENT
                        ? View.MeasureSpec.makeMeasureSpec(mHostedWindowMaxHeight,
                                View.MeasureSpec.EXACTLY)
                        : lp.height > 0
                                ? View.MeasureSpec.makeMeasureSpec(
                                        Math.min(lp.height, mHostedWindowMaxHeight),
                                        View.MeasureSpec.EXACTLY)
                                : View.MeasureSpec.makeMeasureSpec(mHostedWindowMaxHeight,
                                        View.MeasureSpec.AT_MOST);
            } else {
                widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(mWidth,
                        View.MeasureSpec.EXACTLY);
                heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(mHeight,
                        View.MeasureSpec.EXACTLY);
            }
            mView.measure(widthMeasureSpec, heightMeasureSpec);
            int layoutWidth = mHostedWindowLayout
                    ? Math.max(1, mView.getMeasuredWidth()) : mWidth;
            int layoutHeight = mHostedWindowLayout
                    ? Math.max(1, mView.getMeasuredHeight()) : mHeight;
            // Clear this after measure and before layout, matching AOSP, so a
            // request made during layout is retained for a subsequent pass.
            mLayoutRequested = false;
            mWidth = layoutWidth;
            mHeight = layoutHeight;
            performLayout(widthMeasureSpec, heightMeasureSpec, layoutWidth, layoutHeight);
            if (mHostedWindowLayout) {
                mWidth = Math.max(1, mView.getMeasuredWidth());
                mHeight = Math.max(1, mView.getMeasuredHeight());
            }
            if (mWidth != previousWidth || mHeight != previousHeight) mHasRenderNode = false;
        }
        long traceLayout = TRACE_SLOW_FRAMES ? SystemClock.uptimeMillis() : 0;
        int width = mWidth;
        int height = mHeight;
        dispatchOnPreDraw();
        long tracePreDraw = TRACE_SLOW_FRAMES ? SystemClock.uptimeMillis() : 0;
        updateSurfacePositions(mView);
        long traceSurfaces = TRACE_SLOW_FRAMES ? SystemClock.uptimeMillis() : 0;
        mRenderNode.setPosition(0, 0, width, height);
        // Follow AOSP ThreadedRenderer.updateRootDisplayList(): update only dirty
        // view display lists, then keep a stable root node which references the
        // decor's RenderNode. Replaying mView.draw() here forced dispatchDraw()
        // across the complete hierarchy on every animation frame.
        RenderNode viewNode = mView.updateDisplayListIfDirty();
        if (!mHasRenderNode) {
            RecordingCanvas canvas = mRenderNode.beginRecording(width, height);
            try {
                // Overlay roots are replayed into the activity composite, so a clear would punch
                // through transparent popup corners. The activity root remains opaque black.
                if (!mHostedWindowLayout) canvas.drawColor(0xff000000);
                canvas.enableZ();
                canvas.drawRenderNode(viewNode);
                canvas.disableZ();
            } finally {
                mRenderNode.endRecording();
            }
            mHasRenderNode = true;
        }
        if (TRACE_SLOW_FRAMES) {
            long traceDone = SystemClock.uptimeMillis();
            if (traceDone - traceStart > 8) {
                Log.d("ViewRootFrame", "slow traversal=" + (traceDone - traceStart)
                        + "ms attrs=" + (traceAttributes - traceStart)
                        + " scroll=" + (traceScroll - traceAttributes)
                        + " layout=" + (traceLayout - traceScroll)
                        + " predraw=" + (tracePreDraw - traceLayout)
                        + " surfaces=" + (traceSurfaces - tracePreDraw)
                        + " draw=" + (traceDone - traceSurfaces)
                        + " requested=" + mLayoutRequested);
            }
        }
    }

    /**
     * AOSP ViewRootImpl.performLayout(): honor first-pass requestLayout() calls with a second
     * pass now, then move requests from that second pass to the next traversal.
     */
    private void performLayout(int widthMeasureSpec, int heightMeasureSpec, int width, int height) {
        mInLayout = true;
        try {
            mView.layout(0, 0, width, height);
        } finally {
            mInLayout = false;
        }

        ArrayList<View> validRequesters = getValidLayoutRequesters(mLayoutRequesters);
        if (validRequesters == null) return;

        mHandlingLayoutInLayoutRequest = true;
        try {
            for (View requester : validRequesters) requester.requestLayout();
            mView.measure(widthMeasureSpec, heightMeasureSpec);
            mInLayout = true;
            try {
                mView.layout(0, 0, mView.getMeasuredWidth(), mView.getMeasuredHeight());
            } finally {
                mInLayout = false;
            }
        } finally {
            mHandlingLayoutInLayoutRequest = false;
        }

        mLayoutRequesters.clear();
    }

    private static ArrayList<View> getValidLayoutRequesters(ArrayList<View> requesters) {
        ArrayList<View> valid = null;
        for (View requester : requesters) {
            if (requester == null || requester.mAttachInfo == null || requester.mParent == null
                    || (requester.mPrivateFlags & View.PFLAG_FORCE_LAYOUT) == 0) {
                continue;
            }
            boolean gone = false;
            for (View ancestor = requester; ancestor != null;) {
                if ((ancestor.mViewFlags & View.VISIBILITY_MASK) == View.GONE) {
                    gone = true;
                    break;
                }
                ancestor = ancestor.mParent instanceof View ? (View) ancestor.mParent : null;
            }
            if (!gone) {
                if (valid == null) valid = new ArrayList<>();
                valid.add(requester);
            }
        }
        for (View requester : requesters) clearLayoutRequestFlags(requester);
        requesters.clear();
        return valid;
    }

    private static void clearLayoutRequestFlags(View requester) {
        for (View current = requester; current != null
                && (current.mPrivateFlags & View.PFLAG_FORCE_LAYOUT) != 0;) {
            current.mPrivateFlags &= ~View.PFLAG_FORCE_LAYOUT;
            current = current.mParent instanceof View ? (View) current.mParent : null;
        }
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
            // AOSP ViewRootImpl.scheduleTraversals(). WMS and DisplayEventReceiver are absent,
            // but the main-queue barrier and Choreographer phase ordering are process-local.
            mTraversalBarrier = mHandler.getLooper().getQueue().postSyncBarrier();
            mChoreographer.postCallback(Choreographer.CALLBACK_TRAVERSAL,
                    mTraversalRunnable, null);
            ArtzigAccessibilityBridge.markChanged("traversal");
            if (mFrameListener != null) mHandler.post(mFrameListener);
        }
    }

    private void unscheduleTraversals() {
        if (!mTraversalScheduled) return;
        mTraversalScheduled = false;
        mChoreographer.removeCallbacks(Choreographer.CALLBACK_TRAVERSAL,
                mTraversalRunnable, null);
        if (mTraversalBarrier >= 0) {
            mHandler.getLooper().getQueue().removeSyncBarrier(mTraversalBarrier);
            mTraversalBarrier = -1;
        }
    }

    // ---- ViewParent ----
    @Override public void requestLayout() {
        if (!mHandlingLayoutInLayoutRequest) {
            mLayoutRequested = true;
            scheduleTraversals();
        }
    }
    @Override public boolean isLayoutRequested() { return mLayoutRequested; }
    @Override public ViewParent getParent() { return null; }
    /** Follows AOSP ViewRootImpl.onDescendantInvalidated(). Hardware-accelerated
     * ViewGroup invalidation reaches the root through this callback rather
     * than the deprecated invalidateChild() path. */
    @Override public void onDescendantInvalidated(View child, View descendant) {
        if ((descendant.mPrivateFlags & View.PFLAG_DRAW_ANIMATION) != 0) {
            mIsAnimating = true;
        }
        invalidate();
    }
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
    @Override public void recomputeViewAttributes(View child) {
        if (mView == child && mAttachInfo != null) {
            mAttachInfo.mRecomputeGlobalAttributes = true;
            scheduleTraversals();
        }
    }
    @Override public void clearChildFocus(View child) {}
    @Override public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) { return false; }
    @Override public boolean requestSendAccessibilityEvent(View child, android.view.accessibility.AccessibilityEvent event) {
        ArtzigAccessibilityBridge.markAccessibilityEvent(event);
        return true;
    }
    @Override public void notifySubtreeAccessibilityStateChanged(View child, View source, int changeType) {
        ArtzigAccessibilityBridge.markChanged("subtree:" + changeType);
    }
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

    /**
     * AOSP ViewRootImpl.collectViewAttributes() followed by
     * ViewRootImpl.controlInsetsForCompatibility(), adapted at the host seam because this root has
     * no WindowManagerService or SystemUI process.
     */
    private void collectViewAttributes() {
        if (mView == null || mAttachInfo == null || !mAttachInfo.mRecomputeGlobalAttributes) {
            return;
        }
        mAttachInfo.mRecomputeGlobalAttributes = false;
        mAttachInfo.mKeepScreenOn = false;
        mAttachInfo.mSystemUiVisibility = 0;
        mAttachInfo.mHasSystemUiListeners = false;
        mView.dispatchCollectViewAttributes(mAttachInfo, 0);

        int visibility = mAttachInfo.mSystemUiVisibility;
        mWindowAttributes.subtreeSystemUiVisibility = visibility;
        mTypesHiddenByLegacySystemUi = HostedSystemUiController.reconcileLegacyVisibility(
                getInsetsController(), mTypesHiddenByLegacySystemUi, visibility);
        mView.dispatchWindowSystemUiVisiblityChanged(visibility);
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
    public int getLastClickToolType() { return mLastClickToolType; }
    public int getLastTouchSource() { return mLastTouchSource; }
    public int getLastTouchPointerId() { return mLastTouchPointerId; }
    public int getLastTouchDeviceId() { return mLastTouchDeviceId; }
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
    /**
     * AOSP ViewRootImpl.requestPointerCapture()/handlePointerCaptureChanged() adapted at the
     * Wayland host seam because InputManagerGlobal and WindowManagerService are absent.
     */
    public void requestPointerCapture(boolean enabled) {
        HostedInputDispatcher.requestPointerCapture(this, enabled);
    }
    public boolean hasPointerCapture() { return mPointerCapture; }
    void handleHostedPointerCaptureChanged(boolean hasCapture) {
        if (mPointerCapture == hasCapture) return;
        mPointerCapture = hasCapture;
        if (mView != null) mView.dispatchPointerCaptureChanged(hasCapture);
    }
    public void notifyRendererOfExpensiveFrame() {}
    public void notifyRendererOfFramePause() {}
    public void setDragStartedViewForAccessibility(View view) {}
    public static void outputDisplayList(View view) {}
    public void removeThreadedRendererView(View view) {}
    public void addThreadedRendererView(View view) {}
    public int getWindowFlags() { return mWindowAttributes.flags; }
    public void getDisplayFrame(android.graphics.Rect outFrame) { outFrame.set(0, 0, mWidth, mHeight); }
    public boolean isInTouchMode() {
        return mAttachInfo != null && mAttachInfo.mInTouchMode;
    }
    public boolean ensureTouchMode(boolean inTouchMode) {
        if (mAttachInfo == null || mAttachInfo.mInTouchMode == inTouchMode) return false;
        mAttachInfo.mInTouchMode = inTouchMode;
        mAttachInfo.mTreeObserver.dispatchOnTouchModeChanged(inTouchMode);
        return inTouchMode ? enterTouchMode() : leaveTouchMode();
    }
    private boolean enterTouchMode() {
        if (mView == null || !mView.hasFocus()) return false;
        final View focused = mView.findFocus();
        if (focused == null || focused.isFocusableInTouchMode()) return false;
        final ViewGroup ancestor = findAncestorToTakeFocusInTouchMode(focused);
        if (ancestor != null) return ancestor.requestFocus();
        focused.clearFocusInternal(null, true, false);
        return true;
    }
    private static ViewGroup findAncestorToTakeFocusInTouchMode(View focused) {
        ViewParent parent = focused.getParent();
        while (parent instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) parent;
            if (group.getDescendantFocusability() == ViewGroup.FOCUS_AFTER_DESCENDANTS
                    && group.isFocusableInTouchMode()) return group;
            if (group.isRootNamespace()) return null;
            parent = group.getParent();
        }
        return null;
    }
    private boolean leaveTouchMode() {
        if (mView == null) return false;
        if (mView.hasFocus()) {
            final View focused = mView.findFocus();
            if (!(focused instanceof ViewGroup)) return false;
            if (((ViewGroup) focused).getDescendantFocusability()
                    != ViewGroup.FOCUS_AFTER_DESCENDANTS) return false;
        }
        return mView.restoreDefaultFocus();
    }
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
    @Override public void requestFitSystemWindows() {
        if (mApplyInsetsScheduled) return;
        mApplyInsetsScheduled = true;
        mHandler.post(() -> {
            mApplyInsetsScheduled = false;
            if (mView == null) return;
            mView.dispatchApplyWindowInsets(mWindowInsets);
            requestLayout();
        });
    }
    public WindowInsets getWindowInsets(boolean forceConstruct) { return mWindowInsets; }
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
    public boolean requestLayoutDuringLayout(View view) {
        if (view.mParent == null || view.mAttachInfo == null) return true;
        if (mHandlingLayoutInLayoutRequest) {
            if (!mLayoutRequesters.contains(view)) {
                mLayoutRequesters.add(view);
                // AOSP queues second-pass requests in getRunQueue(). The hosted root has no
                // separate RunQueue, so its main Handler provides the same post-traversal order.
                mHandler.post(() -> {
                    if (view.mParent == null || view.mAttachInfo == null) return;
                    clearLayoutRequestFlags(view);
                    view.requestLayout();
                });
            }
            return false;
        }
        if (!mLayoutRequesters.contains(view)) {
            mLayoutRequesters.add(view);
        }
        return true;
    }
    public void getLastTouchPoint(android.graphics.Point outPoint) {
        outPoint.set((int) mLastTouchPoint.x, (int) mLastTouchPoint.y);
    }
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
    public boolean isInLayout() { return mInLayout; }
    public void requestInvalidateRootRenderNode() {}
    public void invalidate() { scheduleTraversals(); }
    public boolean isFrameRatePowerSavingsBalanced() { return false; }
    public void votePreferredFrameRate(float frameRate, int type) {}
    public Handler getHandler() { return mHandler; }
    public Context getContext() { return mContext; }
}
