/* artzig: hand-written ViewRootImpl (ATL pattern) — the stock one is hard-coupled to
 * IWindowSession/InputChannel/DisplayEventReceiver. This one drives the view tree
 * over the Wayland-backed surface: measure/layout/record into HWUI display lists
 * each Choreographer frame. Grows as the real View demands more of its API. */
package android.view;

import android.view.SurfaceControl.Transaction;

import android.content.Context;
import android.content.res.Configuration;
import android.window.ActivityWindowInfo;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Message;
import android.os.Build;
import android.util.SparseArray;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.Rect;
import android.graphics.PointF;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.inputmethod.ImeTracker;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.AndroidRuntimeException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.OptionalInt;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeIdManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityNodeProvider;
import static android.view.accessibility.Flags.reduceWindowContentChangedEventThrottle;

public class ViewRootImpl implements ViewParent, AttachedSurfaceControl {
    // Verbatim pinned AOSP legacy WebView compatibility declarations.
    /**
     * Does nothing; Here only because of @UnsupportedAppUsage
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R,
            publicAlternatives = "Use {@link android.webkit.WebView} instead")
    public void detachFunctor(long functor) { }

    /**
     * Does nothing; Here only because of @UnsupportedAppUsage
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R,
            publicAlternatives = "Use {@link android.webkit.WebView} instead")
    public static void invokeFunctor(long functor, boolean waitForCompletion) { }


    // Pinned ViewRootImpl policy switch shared with WMS until the stock client cutover.
    public static final boolean CLIENT_TRANSIENT =
            android.os.SystemProperties.getBoolean("persist.wm.debug.client_transient", false);
    private static final boolean TRACE_SLOW_FRAMES =
            System.getenv("ARTZIG_SLOW_FRAME_TRACE") != null;
    private static boolean sProtoLogInitialized;
    private static final String TAG = "ViewRootImpl";
    private final AccessibilityManager mAccessibilityManager;
    private SendWindowContentChangedAccessibilityEvent mSendWindowContentChangedAccessibilityEvent;
    private HashSet<View> mTempHashSet;
    private final Rect mTempRect = new Rect();
    private View mAccessibilityFocusedHost;
    private AccessibilityNodeInfo mAccessibilityFocusedVirtualView;
    private boolean mStopped;
    private boolean mPausedForTransition;
    public final Context mContext;
    final Handler mHandler;
    final Choreographer mChoreographer;
    // AOSP ViewRootImpl.getChoreographer: the root's own traversal clock.
    public Choreographer getChoreographer() { return mChoreographer; }

    View mView;
    private final dev.artzig.window.HostedTransitionInput mTransitionInput =
            new dev.artzig.window.HostedTransitionInput();
    int mWidth, mHeight;
    final RenderNode mRenderNode = new RenderNode("artzig-view-root");
    boolean mTraversalScheduled;
    int mTraversalBarrier = -1;
    boolean mLayoutRequested;
    boolean mForceNextWindowRelayout;
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
        mAccessibilityManager = AccessibilityManager.getInstance(context);
        mHandler = new Handler(Looper.getMainLooper());
        mChoreographer = Choreographer.getInstance();
        initializeProtoLogInProcess();
    }

    // Stock WindowManagerGlobal owns registration. The desktop backend still
    // supplies a composited RenderNode instead of a native window per root.
    public ViewRootImpl(Context context, Display display) {
        this(context);
        mDisplay = display;
    }

    public ViewRootImpl(Context context, Display display, IWindowSession session,
            WindowLayout layout) {
        this(context, display);
        throw dev.artzig.HostedWindowRoots.unsupportedWindowless();
    }

    private final WindowLeaked mLocation = new WindowLeaked(null);
    private boolean mRegisteredWithWindowManager;
    private boolean mRemoved;
    private boolean mIsInTraversal;
    private View mPanelParentView;
    private int mClientWindowLayoutFlags;

    static final class GfxInfo {
        public int viewCount;
        public long renderNodeMemoryUsage;
        public long renderNodeMemoryAllocated;

        void add(GfxInfo other) {
            viewCount += other.viewCount;
            renderNodeMemoryUsage += other.renderNodeMemoryUsage;
            renderNodeMemoryAllocated += other.renderNodeMemoryAllocated;
        }
    }

    GfxInfo getGfxInfo() {
        GfxInfo info = new GfxInfo();
        if (mView != null) {
            appendGfxInfo(mView, info);
        }
        return info;
    }

    private static void computeRenderNodeUsage(RenderNode node, GfxInfo info) {
        if (node == null) return;
        info.renderNodeMemoryUsage += node.computeApproximateMemoryUsage();
        info.renderNodeMemoryAllocated += node.computeApproximateMemoryAllocated();
    }

    private static void appendGfxInfo(View view, GfxInfo info) {
        info.viewCount++;
        computeRenderNodeUsage(view.mRenderNode, info);
        computeRenderNodeUsage(view.mBackgroundRenderNode, info);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;

            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                appendGfxInfo(group.getChildAt(i), info);
            }
        }
    }


    final WindowLeaked getLocation() { return mLocation; }
    public IWindowSession getWindowSession() { return mWindowSession; }

    // Pinned ViewRootImpl overload and windowless client collaborators. The
    // windowless constructor still rejects the absent embedded-surface backend.
    public void setView(View view, WindowManager.LayoutParams attrs, View panelParentView) {
        setView(view, attrs, panelParentView, android.os.UserHandle.myUserId());
    }

    void forceWmRelayout() {
        mForceNextWindowRelayout = true;
        scheduleTraversals();
    }

    private Rect mOverrideInsetsFrame;
    void setOverrideInsetsFrame(Rect frame) {
        mOverrideInsetsFrame = new Rect(frame);
        getInsetsController().onFrameChanged(mOverrideInsetsFrame);
    }

    private android.view.accessibility.IAccessibilityEmbeddedConnection mAccessibilityEmbeddedConnection;
    public android.view.accessibility.IAccessibilityEmbeddedConnection getAccessibilityEmbeddedConnection() {
        if (mAccessibilityEmbeddedConnection == null) {
            mAccessibilityEmbeddedConnection = new AccessibilityEmbeddedConnection(this);
        }
        return mAccessibilityEmbeddedConnection;
    }

    void setBackKeyCallbackForWindowlessWindow(java.util.function.Predicate<KeyEvent> callback) {
        // This callback requires a windowless InputChannel. Such roots are
        // rejected at construction until surface embedding is hosted.
        throw dev.artzig.HostedWindowRoots.unsupportedWindowlessOperation(
                "setBackKeyCallbackForWindowlessWindow");
    }

    private boolean mReportNextDraw;
    private String mLastReportNextDrawReason;

    public void setReportNextDraw(boolean syncBuffer, String reason) {
        if (syncBuffer) {
            // The existing presentation path has no BLAST buffer transaction.
            throw dev.artzig.HostedWindowRoots.unsupportedWindowlessOperation("setReportNextDraw");
        }
        mReportNextDraw = true;
        mLastReportNextDrawReason = reason;
        invalidate();
    }

    /** Pinned notifyChildRebuilt, for a preserved root after activity relaunch.
     * Surface/InputQueue takeovers need producers absent from this host root. */
    public void notifyChildRebuilt() {
        checkThread();
        if (mView instanceof com.android.internal.view.RootViewSurfaceTaker) {
            com.android.internal.view.RootViewSurfaceTaker taker =
                    (com.android.internal.view.RootViewSurfaceTaker) mView;
            if (taker.willYouTakeTheSurface() != null || taker.willYouTakeTheInputQueue() != null) {
                throw dev.artzig.HostedWindowRoots.unsupportedSurfaceTakeover();
            }
        }
        updateLastConfigurationFromResources(mContext.getResources().getConfiguration());
        setReportNextDraw(false, "rebuilt");
        if (mStopped) setWindowStopped(false);
    }

    // AOSP captures callbacks before onDraw; registration during onDraw is for
    // the following frame. HostedRenderer submits a composite for all roots.
    private void captureFrameCommitCallbacks() {
        ArrayList<Runnable> callbacks = mAttachInfo.mTreeObserver.captureFrameCommitCallbacks();
        if (mReportNextDraw) {
            if (callbacks == null) callbacks = new ArrayList<>();
            final IWindow window = mWindow;
            final IWindowSession session = mWindowSession;
            callbacks.add(() -> {
                try {
                    session.finishDrawing(window, null, 0);
                } catch (RemoteException e) {
                    Log.e(TAG, "Unable to report draw finished", e);
                }
            });
            mReportNextDraw = false;
            mLastReportNextDrawReason = null;
        }
        if (callbacks != null && !callbacks.isEmpty()) {
            dev.artzig.HostedRenderer.getInstance(mContext)
                    .queueFrameCommitCallbacks(this, mHandler, callbacks);
        }
    }

    public void setView(View view, WindowManager.LayoutParams attrs, View panelParentView,
            int userId) {
        mRegisteredWithWindowManager = true;
        mPanelParentView = panelParentView;
        setView(view, 0, 0);
    }

    // AOSP ViewRootImpl.initializeProtoLogInProcess; the hosted root still owns
    // construction while its window backend does not provide a stock IWindowSession.
    private void initializeProtoLogInProcess() {
        if (!sProtoLogInitialized) {
            com.android.internal.protolog.ProtoLog.init(ViewProtoLogGroups.ALL_GROUPS);
            sProtoLogInitialized = true;
        }
    }

    public void setView(View view, int width, int height) {
        mRemoved = false;
        // re-showing a decor that was rooted before (in-process activity stack):
        // detach it from its old root first.
        if (view.getParent() instanceof ViewRootImpl && view.getParent() != this) {
            ViewRootImpl old = (ViewRootImpl) view.getParent();
            old.destroy();
        }
        mView = view;
        mLastConfigurationFromResources.setTo(view.getResources().getConfiguration());
        mLastReportedMergedConfiguration.setConfiguration(
                mLastConfigurationFromResources, new Configuration());
        mViewLayoutDirectionInitial = view.getRawLayoutDirection();
        mWidth = width;
        mHeight = height;
        if (mAttachInfo == null) {
            IWindow window = new IWindow.Default() {
                private final android.os.Binder mBinder = new android.os.Binder("artzig-window");
                @Override public android.os.IBinder asBinder() { return mBinder; }
            };
            mWindow = window;
            if (mWindowSession == null) mWindowSession = WindowManagerGlobal.getWindowSession();
            mAttachInfo = new View.AttachInfo(mWindowSession, window, mDisplay, this,
                    mHandler, new View.AttachInfo.Callbacks() {
                        @Override public void playSoundEffect(int effectId) {}
                        @Override public boolean performHapticFeedback(int effectId, int flags, int privFlags) { return false; }
                        @Override public void performHapticFeedbackForInputDevice(int effectId,
                                int inputDeviceId, int inputSource, int flags, int privFlags) {}
                    }, mContext);
            // Transitions key their animators by WindowId.  The platform normally
            // obtains this token from IWindowSession. Hosted windows do not yet
            // have server WindowStates, so provide a process-local identity.
            IWindowId windowId = new IWindowId.Stub() {
                @Override public void registerFocusObserver(IWindowFocusObserver observer) {}
                @Override public void unregisterFocusObserver(IWindowFocusObserver observer) {}
                @Override public boolean isFocused() { return true; }
            };
            mAttachInfo.mIWindowId = windowId;
            mAttachInfo.mWindowId = new WindowId(windowId);
            mAttachInfo.mHardwareAccelerated = true;
            mAttachInfo.mThreadedRenderer = dev.artzig.HostedRenderer.getInstance(mContext).renderer;
            mAttachInfo.mInTouchMode = true; // pointer taps act as touches; no focus rings
            mAttachInfo.mWindowVisibility = View.VISIBLE;
            mAttachInfo.mHasWindowFocus = true;
            if (mPanelParentView != null) {
                mAttachInfo.mPanelParentWindowToken = mPanelParentView.getWindowToken();
            }
        }
        if (view.getLayoutParams() instanceof WindowManager.LayoutParams) {
            mWindowAttributes.copyFrom((WindowManager.LayoutParams) view.getLayoutParams());
        }
        mClientWindowLayoutFlags = mWindowAttributes.flags;
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
    }
    public View getView() { return mView; }
    private HostedInputDispatcher.EventQueue mInputEventQueue;
    // AOSP returns null when no InputEventReceiver/input channel is attached.
    public IBinder getInputToken() { return null; }
    // The hosted dispatcher owns queue ordering and event recycling until stock input stages
    // can use a real InputChannel. InkWindow transfers ownership through this stock API.
    // AOSP IME visibility tracking and session notification.
    public void notifyImeVisibilityChanged(boolean visible, @NonNull ImeTracker.Token statsToken) {
        ImeTracker.forLogging().onProgress(statsToken,
                ImeTracker.PHASE_CLIENT_NOTIFY_IME_VISIBILITY_CHANGED);
        try {
            mWindowSession.notifyImeWindowVisibilityChangedFromClient(mWindow, visible, statsToken);
        } catch (RemoteException e) {
            ImeTracker.forLogging().onFailed(statsToken,
                    ImeTracker.PHASE_CLIENT_NOTIFY_IME_VISIBILITY_CHANGED);
            e.rethrowFromSystemServer();
        }
    }

    public void enqueueInputEvent(InputEvent event) {
        if (mInputEventQueue == null) mInputEventQueue = new HostedInputDispatcher.EventQueue(this);
        mInputEventQueue.enqueue(event);
    }

    // Pinned ViewRootImpl SyntheticInputStage and dispatchUnhandledInputEvent.
    // InputChannel stages are not hosted yet. The existing asynchronous queue
    // owns delivery; the four synthesis handlers below are reused verbatim.
    private static final boolean DEBUG_TRACKBALL = false;
    private static final boolean DEBUG_TOUCH_NAVIGATION = false;
    private static final int MAX_TRACKBALL_DELAY = 250;
    private final String mTag = TAG;
    private SyntheticTrackballHandler mSyntheticTrackball;
    private SyntheticJoystickHandler mSyntheticJoystick;
    private SyntheticTouchNavigationHandler mSyntheticTouchNavigation;
    private SyntheticKeyboardHandler mSyntheticKeyboard;

    public void dispatchUnhandledInputEvent(InputEvent event) {
        if (event instanceof MotionEvent) {
            event = MotionEvent.obtain((MotionEvent) event);
        }
        if (mInputEventQueue == null) mInputEventQueue = new HostedInputDispatcher.EventQueue(this);
        mInputEventQueue.enqueueUnhandled(event);
    }

    void processUnhandledInputEvent(InputEvent input) {
        if (input instanceof MotionEvent) {
            MotionEvent event = (MotionEvent) input;
            final int source = event.getSource();
            if ((source & InputDevice.SOURCE_CLASS_TRACKBALL) != 0) {
                if (!event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) {
                    if (mSyntheticTrackball == null) mSyntheticTrackball = new SyntheticTrackballHandler();
                    mSyntheticTrackball.process(event);
                }
            } else if ((source & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
                if (mSyntheticJoystick == null) mSyntheticJoystick = new SyntheticJoystickHandler();
                mSyntheticJoystick.process(event);
            } else if ((source & InputDevice.SOURCE_TOUCH_NAVIGATION) == InputDevice.SOURCE_TOUCH_NAVIGATION) {
                if (mSyntheticTouchNavigation == null) mSyntheticTouchNavigation = new SyntheticTouchNavigationHandler();
                mSyntheticTouchNavigation.process(event);
            }
        } else if (input instanceof KeyEvent) {
            if (mSyntheticKeyboard == null) mSyntheticKeyboard = new SyntheticKeyboardHandler();
            mSyntheticKeyboard.process((KeyEvent) input);
        }
    }

    /**
     * Creates dpad events from unhandled trackball movements.
     */
    final class SyntheticTrackballHandler {
        private final TrackballAxis mX = new TrackballAxis();
        private final TrackballAxis mY = new TrackballAxis();
        private long mLastTime;

        public void process(MotionEvent event) {
            // Translate the trackball event into DPAD keys and try to deliver those.
            long curTime = SystemClock.uptimeMillis();
            if ((mLastTime + MAX_TRACKBALL_DELAY) < curTime) {
                // It has been too long since the last movement,
                // so restart at the beginning.
                mX.reset(0);
                mY.reset(0);
                mLastTime = curTime;
            }

            final int action = event.getAction();
            final int metaState = event.getMetaState();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    mX.reset(2);
                    mY.reset(2);
                    enqueueInputEvent(new KeyEvent(curTime, curTime,
                            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0, metaState,
                            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                            InputDevice.SOURCE_KEYBOARD));
                    break;
                case MotionEvent.ACTION_UP:
                    mX.reset(2);
                    mY.reset(2);
                    enqueueInputEvent(new KeyEvent(curTime, curTime,
                            KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0, metaState,
                            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                            InputDevice.SOURCE_KEYBOARD));
                    break;
            }

            if (DEBUG_TRACKBALL) Log.v(mTag, "TB X=" + mX.position + " step="
                    + mX.step + " dir=" + mX.dir + " acc=" + mX.acceleration
                    + " move=" + event.getX()
                    + " / Y=" + mY.position + " step="
                    + mY.step + " dir=" + mY.dir + " acc=" + mY.acceleration
                    + " move=" + event.getY());
            final float xOff = mX.collect(event.getX(), event.getEventTime(), "X");
            final float yOff = mY.collect(event.getY(), event.getEventTime(), "Y");

            // Generate DPAD events based on the trackball movement.
            // We pick the axis that has moved the most as the direction of
            // the DPAD.  When we generate DPAD events for one axis, then the
            // other axis is reset -- we don't want to perform DPAD jumps due
            // to slight movements in the trackball when making major movements
            // along the other axis.
            int keycode = 0;
            int movement = 0;
            float accel = 1;
            if (xOff > yOff) {
                movement = mX.generate();
                if (movement != 0) {
                    keycode = movement > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT
                            : KeyEvent.KEYCODE_DPAD_LEFT;
                    accel = mX.acceleration;
                    mY.reset(2);
                }
            } else if (yOff > 0) {
                movement = mY.generate();
                if (movement != 0) {
                    keycode = movement > 0 ? KeyEvent.KEYCODE_DPAD_DOWN
                            : KeyEvent.KEYCODE_DPAD_UP;
                    accel = mY.acceleration;
                    mX.reset(2);
                }
            }

            if (keycode != 0) {
                if (movement < 0) movement = -movement;
                int accelMovement = (int)(movement * accel);
                if (DEBUG_TRACKBALL) Log.v(mTag, "Move: movement=" + movement
                        + " accelMovement=" + accelMovement
                        + " accel=" + accel);
                if (accelMovement > movement) {
                    if (DEBUG_TRACKBALL) Log.v(mTag, "Delivering fake DPAD: "
                            + keycode);
                    movement--;
                    int repeatCount = accelMovement - movement;
                    enqueueInputEvent(new KeyEvent(curTime, curTime,
                            KeyEvent.ACTION_MULTIPLE, keycode, repeatCount, metaState,
                            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                            InputDevice.SOURCE_KEYBOARD));
                }
                while (movement > 0) {
                    if (DEBUG_TRACKBALL) Log.v(mTag, "Delivering fake DPAD: "
                            + keycode);
                    movement--;
                    curTime = SystemClock.uptimeMillis();
                    enqueueInputEvent(new KeyEvent(curTime, curTime,
                            KeyEvent.ACTION_DOWN, keycode, 0, metaState,
                            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                            InputDevice.SOURCE_KEYBOARD));
                    enqueueInputEvent(new KeyEvent(curTime, curTime,
                            KeyEvent.ACTION_UP, keycode, 0, metaState,
                            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                            InputDevice.SOURCE_KEYBOARD));
                }
                mLastTime = curTime;
            }
        }

        public void cancel() {
            mLastTime = Integer.MIN_VALUE;

            // If we reach this, we consumed a trackball event.
            // Because we will not translate the trackball event into a key event,
            // touch mode will not exit, so we exit touch mode here.
            if (mView != null && mAdded) {
                ensureTouchMode(false);
            }
        }
    }

    /**
     * Maintains state information for a single trackball axis, generating
     * discrete (DPAD) movements based on raw trackball motion.
     */
    static final class TrackballAxis {
        /**
         * The maximum amount of acceleration we will apply.
         */
        static final float MAX_ACCELERATION = 20;

        /**
         * The maximum amount of time (in milliseconds) between events in order
         * for us to consider the user to be doing fast trackball movements,
         * and thus apply an acceleration.
         */
        static final long FAST_MOVE_TIME = 150;

        /**
         * Scaling factor to the time (in milliseconds) between events to how
         * much to multiple/divide the current acceleration.  When movement
         * is < FAST_MOVE_TIME this multiplies the acceleration; when >
         * FAST_MOVE_TIME it divides it.
         */
        static final float ACCEL_MOVE_SCALING_FACTOR = (1.0f/40);

        static final float FIRST_MOVEMENT_THRESHOLD = 0.5f;
        static final float SECOND_CUMULATIVE_MOVEMENT_THRESHOLD = 2.0f;
        static final float SUBSEQUENT_INCREMENTAL_MOVEMENT_THRESHOLD = 1.0f;

        float position;
        float acceleration = 1;
        long lastMoveTime = 0;
        int step;
        int dir;
        int nonAccelMovement;

        void reset(int _step) {
            position = 0;
            acceleration = 1;
            lastMoveTime = 0;
            step = _step;
            dir = 0;
        }

        /**
         * Add trackball movement into the state.  If the direction of movement
         * has been reversed, the state is reset before adding the
         * movement (so that you don't have to compensate for any previously
         * collected movement before see the result of the movement in the
         * new direction).
         *
         * @return Returns the absolute value of the amount of movement
         * collected so far.
         */
        float collect(float off, long time, String axis) {
            long normTime;
            if (off > 0) {
                normTime = (long)(off * FAST_MOVE_TIME);
                if (dir < 0) {
                    if (DEBUG_TRACKBALL) Log.v(TAG, axis + " reversed to positive!");
                    position = 0;
                    step = 0;
                    acceleration = 1;
                    lastMoveTime = 0;
                }
                dir = 1;
            } else if (off < 0) {
                normTime = (long)((-off) * FAST_MOVE_TIME);
                if (dir > 0) {
                    if (DEBUG_TRACKBALL) Log.v(TAG, axis + " reversed to negative!");
                    position = 0;
                    step = 0;
                    acceleration = 1;
                    lastMoveTime = 0;
                }
                dir = -1;
            } else {
                normTime = 0;
            }

            // The number of milliseconds between each movement that is
            // considered "normal" and will not result in any acceleration
            // or deceleration, scaled by the offset we have here.
            if (normTime > 0) {
                long delta = time - lastMoveTime;
                lastMoveTime = time;
                float acc = acceleration;
                if (delta < normTime) {
                    // The user is scrolling rapidly, so increase acceleration.
                    float scale = (normTime-delta) * ACCEL_MOVE_SCALING_FACTOR;
                    if (scale > 1) acc *= scale;
                    if (DEBUG_TRACKBALL) Log.v(TAG, axis + " accelerate: off="
                            + off + " normTime=" + normTime + " delta=" + delta
                            + " scale=" + scale + " acc=" + acc);
                    acceleration = acc < MAX_ACCELERATION ? acc : MAX_ACCELERATION;
                } else {
                    // The user is scrolling slowly, so decrease acceleration.
                    float scale = (delta-normTime) * ACCEL_MOVE_SCALING_FACTOR;
                    if (scale > 1) acc /= scale;
                    if (DEBUG_TRACKBALL) Log.v(TAG, axis + " deccelerate: off="
                            + off + " normTime=" + normTime + " delta=" + delta
                            + " scale=" + scale + " acc=" + acc);
                    acceleration = acc > 1 ? acc : 1;
                }
            }
            position += off;
            return Math.abs(position);
        }

        /**
         * Generate the number of discrete movement events appropriate for
         * the currently collected trackball movement.
         *
         * @return Returns the number of discrete movements, either positive
         * or negative, or 0 if there is not enough trackball movement yet
         * for a discrete movement.
         */
        int generate() {
            int movement = 0;
            nonAccelMovement = 0;
            do {
                final int dir = position >= 0 ? 1 : -1;
                switch (step) {
                    // If we are going to execute the first step, then we want
                    // to do this as soon as possible instead of waiting for
                    // a full movement, in order to make things look responsive.
                    case 0:
                        if (Math.abs(position) < FIRST_MOVEMENT_THRESHOLD) {
                            return movement;
                        }
                        movement += dir;
                        nonAccelMovement += dir;
                        step = 1;
                        break;
                    // If we have generated the first movement, then we need
                    // to wait for the second complete trackball motion before
                    // generating the second discrete movement.
                    case 1:
                        if (Math.abs(position) < SECOND_CUMULATIVE_MOVEMENT_THRESHOLD) {
                            return movement;
                        }
                        movement += dir;
                        nonAccelMovement += dir;
                        position -= SECOND_CUMULATIVE_MOVEMENT_THRESHOLD * dir;
                        step = 2;
                        break;
                    // After the first two, we generate discrete movements
                    // consistently with the trackball, applying an acceleration
                    // if the trackball is moving quickly.  This is a simple
                    // acceleration on top of what we already compute based
                    // on how quickly the wheel is being turned, to apply
                    // a longer increasing acceleration to continuous movement
                    // in one direction.
                    default:
                        if (Math.abs(position) < SUBSEQUENT_INCREMENTAL_MOVEMENT_THRESHOLD) {
                            return movement;
                        }
                        movement += dir;
                        position -= dir * SUBSEQUENT_INCREMENTAL_MOVEMENT_THRESHOLD;
                        float acc = acceleration;
                        acc *= 1.1f;
                        acceleration = acc < MAX_ACCELERATION ? acc : acceleration;
                        break;
                }
            } while (true);
        }
    }

    /**
     * Creates dpad events from unhandled joystick movements.
     */
    final class SyntheticJoystickHandler extends Handler {
        private final static int MSG_ENQUEUE_X_AXIS_KEY_REPEAT = 1;
        private final static int MSG_ENQUEUE_Y_AXIS_KEY_REPEAT = 2;

        private final JoystickAxesState mJoystickAxesState = new JoystickAxesState();
        private final SparseArray<KeyEvent> mDeviceKeyEvents = new SparseArray<>();

        public SyntheticJoystickHandler() {
            super(true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ENQUEUE_X_AXIS_KEY_REPEAT:
                case MSG_ENQUEUE_Y_AXIS_KEY_REPEAT: {
                    if (mAttachInfo.mHasWindowFocus) {
                        KeyEvent oldEvent = (KeyEvent) msg.obj;
                        KeyEvent e = KeyEvent.changeTimeRepeat(oldEvent,
                                SystemClock.uptimeMillis(), oldEvent.getRepeatCount() + 1);
                        enqueueInputEvent(e);
                        Message m = obtainMessage(msg.what, e);
                        m.setAsynchronous(true);
                        sendMessageDelayed(m, ViewConfiguration.getKeyRepeatDelay());
                    }
                } break;
            }
        }

        public void process(MotionEvent event) {
            switch(event.getActionMasked()) {
                case MotionEvent.ACTION_CANCEL:
                    cancel();
                    break;
                case MotionEvent.ACTION_MOVE:
                    update(event);
                    break;
                default:
                    Log.w(mTag, "Unexpected action: " + event.getActionMasked());
            }
        }

        private void cancel() {
            removeMessages(MSG_ENQUEUE_X_AXIS_KEY_REPEAT);
            removeMessages(MSG_ENQUEUE_Y_AXIS_KEY_REPEAT);
            for (int i = 0; i < mDeviceKeyEvents.size(); i++) {
                final KeyEvent keyEvent = mDeviceKeyEvents.valueAt(i);
                if (keyEvent != null) {
                    enqueueInputEvent(KeyEvent.changeTimeRepeat(keyEvent,
                            SystemClock.uptimeMillis(), 0));
                }
            }
            mDeviceKeyEvents.clear();
            mJoystickAxesState.resetState();
        }

        private void update(MotionEvent event) {
            final int historySize = event.getHistorySize();
            for (int h = 0; h < historySize; h++) {
                final long time = event.getHistoricalEventTime(h);
                mJoystickAxesState.updateStateForAxis(event, time, MotionEvent.AXIS_X,
                        event.getHistoricalAxisValue(MotionEvent.AXIS_X, 0, h));
                mJoystickAxesState.updateStateForAxis(event, time, MotionEvent.AXIS_Y,
                        event.getHistoricalAxisValue(MotionEvent.AXIS_Y, 0, h));
                mJoystickAxesState.updateStateForAxis(event, time, MotionEvent.AXIS_HAT_X,
                        event.getHistoricalAxisValue(MotionEvent.AXIS_HAT_X, 0, h));
                mJoystickAxesState.updateStateForAxis(event, time, MotionEvent.AXIS_HAT_Y,
                        event.getHistoricalAxisValue(MotionEvent.AXIS_HAT_Y, 0, h));
            }
            final long time = event.getEventTime();
            mJoystickAxesState.updateStateForAxis(event, time, MotionEvent.AXIS_X,
                    event.getAxisValue(MotionEvent.AXIS_X));
            mJoystickAxesState.updateStateForAxis(event, time, MotionEvent.AXIS_Y,
                    event.getAxisValue(MotionEvent.AXIS_Y));
            mJoystickAxesState.updateStateForAxis(event, time, MotionEvent.AXIS_HAT_X,
                    event.getAxisValue(MotionEvent.AXIS_HAT_X));
            mJoystickAxesState.updateStateForAxis(event, time, MotionEvent.AXIS_HAT_Y,
                    event.getAxisValue(MotionEvent.AXIS_HAT_Y));
        }

        final class JoystickAxesState {
            // State machine: from neutral state (no button press) can go into
            // button STATE_UP_OR_LEFT or STATE_DOWN_OR_RIGHT state, emitting an ACTION_DOWN event.
            // From STATE_UP_OR_LEFT or STATE_DOWN_OR_RIGHT state can go into neutral state,
            // emitting an ACTION_UP event.
            private static final int STATE_UP_OR_LEFT = -1;
            private static final int STATE_NEUTRAL = 0;
            private static final int STATE_DOWN_OR_RIGHT = 1;

            final int[] mAxisStatesHat = {STATE_NEUTRAL, STATE_NEUTRAL}; // {AXIS_HAT_X, AXIS_HAT_Y}
            final int[] mAxisStatesStick = {STATE_NEUTRAL, STATE_NEUTRAL}; // {AXIS_X, AXIS_Y}

            void resetState() {
                mAxisStatesHat[0] = STATE_NEUTRAL;
                mAxisStatesHat[1] = STATE_NEUTRAL;
                mAxisStatesStick[0] = STATE_NEUTRAL;
                mAxisStatesStick[1] = STATE_NEUTRAL;
            }

            void updateStateForAxis(MotionEvent event, long time, int axis, float value) {
                // Emit KeyEvent if necessary
                // axis can be AXIS_X, AXIS_Y, AXIS_HAT_X, AXIS_HAT_Y
                final int axisStateIndex;
                final int repeatMessage;
                if (isXAxis(axis)) {
                    axisStateIndex = 0;
                    repeatMessage = MSG_ENQUEUE_X_AXIS_KEY_REPEAT;
                } else if (isYAxis(axis)) {
                    axisStateIndex = 1;
                    repeatMessage = MSG_ENQUEUE_Y_AXIS_KEY_REPEAT;
                } else {
                    Log.e(mTag, "Unexpected axis " + axis + " in updateStateForAxis!");
                    return;
                }
                final int newState = joystickAxisValueToState(value);

                final int currentState;
                if (axis == MotionEvent.AXIS_X || axis == MotionEvent.AXIS_Y) {
                    currentState = mAxisStatesStick[axisStateIndex];
                } else {
                    currentState = mAxisStatesHat[axisStateIndex];
                }

                if (currentState == newState) {
                    return;
                }

                final int metaState = event.getMetaState();
                final int deviceId = event.getDeviceId();
                final int source = event.getSource();

                if (currentState == STATE_DOWN_OR_RIGHT || currentState == STATE_UP_OR_LEFT) {
                    // send a button release event
                    final int keyCode = joystickAxisAndStateToKeycode(axis, currentState);
                    if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                        enqueueInputEvent(new KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode,
                                0, metaState, deviceId, 0, KeyEvent.FLAG_FALLBACK, source));
                        // remove the corresponding pending UP event if focus lost/view detached
                        mDeviceKeyEvents.put(deviceId, null);
                    }
                    removeMessages(repeatMessage);
                }

                if (newState == STATE_DOWN_OR_RIGHT || newState == STATE_UP_OR_LEFT) {
                    // send a button down event
                    final int keyCode = joystickAxisAndStateToKeycode(axis, newState);
                    if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                        KeyEvent keyEvent = new KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode,
                                0, metaState, deviceId, 0, KeyEvent.FLAG_FALLBACK, source);
                        enqueueInputEvent(keyEvent);
                        Message m = obtainMessage(repeatMessage, keyEvent);
                        m.setAsynchronous(true);
                        sendMessageDelayed(m, ViewConfiguration.getKeyRepeatTimeout());
                        // store the corresponding ACTION_UP event so that it can be sent
                        // if focus is lost or root view is removed
                        mDeviceKeyEvents.put(deviceId,
                                new KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode,
                                        0, metaState, deviceId, 0,
                                        KeyEvent.FLAG_FALLBACK | KeyEvent.FLAG_CANCELED,
                                        source));
                    }
                }
                if (axis == MotionEvent.AXIS_X || axis == MotionEvent.AXIS_Y) {
                    mAxisStatesStick[axisStateIndex] = newState;
                } else {
                    mAxisStatesHat[axisStateIndex] = newState;
                }
            }

            private boolean isXAxis(int axis) {
                return axis == MotionEvent.AXIS_X || axis == MotionEvent.AXIS_HAT_X;
            }
            private boolean isYAxis(int axis) {
                return axis == MotionEvent.AXIS_Y || axis == MotionEvent.AXIS_HAT_Y;
            }

            private int joystickAxisAndStateToKeycode(int axis, int state) {
                if (isXAxis(axis) && state == STATE_UP_OR_LEFT) {
                    return KeyEvent.KEYCODE_DPAD_LEFT;
                }
                if (isXAxis(axis) && state == STATE_DOWN_OR_RIGHT) {
                    return KeyEvent.KEYCODE_DPAD_RIGHT;
                }
                if (isYAxis(axis) && state == STATE_UP_OR_LEFT) {
                    return KeyEvent.KEYCODE_DPAD_UP;
                }
                if (isYAxis(axis) && state == STATE_DOWN_OR_RIGHT) {
                    return KeyEvent.KEYCODE_DPAD_DOWN;
                }
                Log.e(mTag, "Unknown axis " + axis + " or direction " + state);
                return KeyEvent.KEYCODE_UNKNOWN; // should never happen
            }

            private int joystickAxisValueToState(float value) {
                if (value >= 0.5f) {
                    return STATE_DOWN_OR_RIGHT;
                } else if (value <= -0.5f) {
                    return STATE_UP_OR_LEFT;
                } else {
                    return STATE_NEUTRAL;
                }
            }
        }
    }

    /**
     * Creates DPAD events from unhandled touch navigation movements.
     */
    final class SyntheticTouchNavigationHandler extends Handler {
        private static final String LOCAL_TAG = "SyntheticTouchNavigationHandler";

        // The id of the input device that is being tracked.
        private int mCurrentDeviceId = -1;
        private int mCurrentSource;

        private int mPendingKeyMetaState;

        private final GestureDetector mGestureDetector;

        SyntheticTouchNavigationHandler() {
            super(true);
            mGestureDetector = new GestureDetector(mContext,
                    new GestureDetector.OnGestureListener() {
                        @Override
                        public boolean onDown(@NonNull MotionEvent e) {
                            // This can be ignored since it's not clear what KeyEvent this will
                            // belong to.
                            return true;
                        }

                        @Override
                        public void onShowPress(@NonNull MotionEvent e) {
                        }

                        @Override
                        public boolean onSingleTapUp(@NonNull MotionEvent e) {
                            dispatchTap(e.getEventTime());
                            return true;
                        }

                        @Override
                        public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2,
                                float distanceX, float distanceY) {
                            // Scroll doesn't translate to DPAD events so should be ignored.
                            return true;
                        }

                        @Override
                        public void onLongPress(@NonNull MotionEvent e) {
                            // Long presses don't translate to DPAD events so should be ignored.
                        }

                        @Override
                        public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2,
                                float velocityX, float velocityY) {
                            dispatchFling(velocityX, velocityY, e2.getEventTime());
                            return true;
                        }
                    },
                    /* handler= */ null,
                    VelocityTracker.VELOCITY_TRACKER_STRATEGY_IMPULSE);
        }

        public void process(MotionEvent event) {
            if (event.getDevice() == null) {
                // The current device is not supported.
                if (DEBUG_TOUCH_NAVIGATION) {
                    Log.d(LOCAL_TAG,
                            "Current device not supported so motion event is not processed");
                }
                return;
            }
            mPendingKeyMetaState = event.getMetaState();
            // Update the current device information.
            final int deviceId = event.getDeviceId();
            final int source = event.getSource();
            if (mCurrentDeviceId != deviceId || mCurrentSource != source) {
                mCurrentDeviceId = deviceId;
                mCurrentSource = source;
            }

            // Interpret the event.
            mGestureDetector.onTouchEvent(event);
        }

        private void dispatchTap(long time) {
            dispatchEvent(time, KeyEvent.KEYCODE_DPAD_CENTER);
        }

        private void dispatchFling(float x, float y, long time) {
            if (Math.abs(x) > Math.abs(y)) {
                dispatchEvent(time,
                        x > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
            } else {
                dispatchEvent(time, y > 0 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP);
            }
        }

        private void dispatchEvent(long time, int keyCode) {
            if (DEBUG_TOUCH_NAVIGATION) {
                Log.d(LOCAL_TAG, "Dispatching DPAD events DOWN and UP with keycode " + keyCode);
            }
            enqueueInputEvent(new KeyEvent(time, time,
                    KeyEvent.ACTION_DOWN, keyCode, /* repeat= */ 0, mPendingKeyMetaState,
                    mCurrentDeviceId, /* scancode= */ 0, KeyEvent.FLAG_FALLBACK,
                    mCurrentSource));
            enqueueInputEvent(new KeyEvent(time, time,
                    KeyEvent.ACTION_UP, keyCode, /* repeat= */ 0, mPendingKeyMetaState,
                    mCurrentDeviceId, /* scancode= */ 0, KeyEvent.FLAG_FALLBACK,
                    mCurrentSource));
        }
    }

    final class SyntheticKeyboardHandler {
        public void process(KeyEvent event) {
            if ((event.getFlags() & KeyEvent.FLAG_FALLBACK) != 0) {
                return;
            }

            final KeyCharacterMap kcm = event.getKeyCharacterMap();
            final int keyCode = event.getKeyCode();
            final int metaState = event.getMetaState();

            // Check for fallback actions specified by the key character map.
            KeyCharacterMap.FallbackAction fallbackAction =
                    kcm.getFallbackAction(keyCode, metaState);
            if (fallbackAction != null) {
                final int flags = event.getFlags() | KeyEvent.FLAG_FALLBACK;
                KeyEvent fallbackEvent = KeyEvent.obtain(
                        event.getDownTime(), event.getEventTime(),
                        event.getAction(), fallbackAction.keyCode,
                        event.getRepeatCount(), fallbackAction.metaState,
                        event.getDeviceId(), event.getScanCode(),
                        flags, event.getSource(), null);
                fallbackAction.recycle();
                enqueueInputEvent(fallbackEvent);
            }
        }
    }

    /**
     * AOSP EarlyPostImeInputStage.processPointerEvent() followed by
     * ViewPostImeInputStage.processPointerEvent(), adapted because InputChannel,
     * InputEventReceiver, IME input queues, and WindowManagerService are absent.
     */
    public boolean dispatchPointerEvent(MotionEvent event) {
        if (mTransitionInput.shouldDrop(event)) return false;
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

        // AOSP ViewPostImeInputStage.processPointerEvent: handwriting precedes view
        // dispatch, except in IME windows when the corresponding flag is enabled.
        boolean handled = false;
        if (!com.android.text.flags.Flags.disableHandwritingInitiatorForIme()
                || mWindowAttributes.type != WindowManager.LayoutParams.TYPE_INPUT_METHOD) {
            handled = getHandwritingInitiator().onTouchEvent(event);
        }
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
        if (mTransitionInput.shouldDrop(event)) return false;
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
        if (mTransitionInput.shouldDrop(event)) return false;
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
        checkThread();
        if (mRemoved) return;
        mRemoved = true;
        if (mAttachInfo != null) {
            dev.artzig.HostedRenderer.forgetRootCallbacks(this);
        }
        if (mSyntheticJoystick != null) mSyntheticJoystick.cancel();
        if (mInputEventQueue != null) mInputEventQueue.clear();
        unscheduleTraversals();
        if (mFrameListener != null) mHandler.removeCallbacks(mFrameListener);
        mHandler.removeCallbacks(mInvalidateOnAnimation);
        HostedInputDispatcher.onRootDestroyed(this);
        if (!mStopped) {
            for (SurfaceChangedCallback callback : new ArrayList<>(mSurfaceChangedCallbacks)) {
                callback.surfaceDestroyed();
            }
        }
        if (mView != null) {
            mView.dispatchWindowFocusChanged(false);
            mView.dispatchWindowVisibilityChanged(View.GONE);
            mView.dispatchDetachedFromWindow();
            mView.assignParent(null);
            mView = null;
        }
        dev.artzig.HostedWindowStack.onRootDestroyed(this);
        if (mRegisteredWithWindowManager) {
            mRegisteredWithWindowManager = false;
            WindowManagerGlobal.getInstance().doRemoveView(this);
        }
        mInvalidateViews.clear();
        for (View.AttachInfo.InvalidateInfo info : mInvalidateRects) info.recycle();
        mInvalidateRects.clear();
        removeSendWindowContentChangedCallback();
        mAttachInfo = null;
        mHasRenderNode = false;
        mRenderNode.discardDisplayList();
    }

    /** Pinned ViewRootImpl.dump: dump the state present at the hosted window boundary.
     * The complete root still requires Android input stages and SurfaceFlinger synchronization. */
    public void dump(String prefix, java.io.PrintWriter writer) {
        String innerPrefix = prefix + "  ";
        writer.println(prefix + "ViewRoot:");
        writer.println(innerPrefix + "mAdded=" + mAdded);
        writer.println(innerPrefix + "mRemoved=" + mRemoved);
        writer.println(innerPrefix + "mStopped=" + mStopped);
        writer.println(innerPrefix + "mReportNextDraw=" + mReportNextDraw
                + " reason=" + mLastReportNextDrawReason);
        writer.println(innerPrefix + "mPausedForTransition=" + mPausedForTransition);
        writer.println(innerPrefix + "mTraversalScheduled=" + mTraversalScheduled);
        if (mAttachInfo != null) {
            writer.print(innerPrefix + "mAttachInfo= ");
            mAttachInfo.dump(innerPrefix, writer);
        } else {
            writer.println(innerPrefix + "mAttachInfo=<null>");
        }
        mChoreographer.dump(prefix, writer);
        if (mInsetsController != null) mInsetsController.dump(prefix, writer);
        writer.println(prefix + "View Hierarchy:");
        dumpViewHierarchy(innerPrefix, writer, mView);
    }

    private void dumpViewHierarchy(String prefix, java.io.PrintWriter writer, View view) {
        writer.print(prefix);
        if (view == null) {
            writer.println("null");
            return;
        }
        writer.println(view.toString());
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup grp = (ViewGroup)view;
        final int N = grp.getChildCount();
        if (N <= 0) {
            return;
        }
        prefix = prefix + "  ";
        for (int i=0; i<N; i++) {
            dumpViewHierarchy(prefix, writer, grp.getChildAt(i));
        }
    }

    // AOSP die/doDie ordering: deferred removal leaves the registry intact
    // while closeAll iterates, and never tears down a hierarchy during traversal.
    boolean die(boolean immediate) {
        if (immediate && !mIsInTraversal) {
            doDie();
            return false;
        }
        mHandler.post(this::doDie);
        return true;
    }

    void doDie() { destroy(); }

    void checkThread() {
        if (mThread != Thread.currentThread()) {
            throw new CalledFromWrongThreadException(
                    "Only the original thread that created a view hierarchy can touch its views."
                    + " Expected: " + mThread.getName()
                    + " Calling: " + Thread.currentThread().getName());
        }
    }

    // AOSP setWindowStopped, applied to this root's display lists. The host
    // shares one renderer across windows, so stopping its renderer would also
    // stop unrelated windows. SurfaceView observes the same surface callbacks.
    void setWindowStopped(boolean stopped) {
        checkThread();
        if (mStopped == stopped || mView == null) return;
        mStopped = stopped;
        dev.artzig.HostedActivityProcess.schedulePresentationUpdate();
        if (stopped) {
            unscheduleTraversals();
            mView.destroyHardwareResources();
            mRenderNode.discardDisplayList();
            mHasRenderNode = false;
            for (SurfaceChangedCallback callback : new ArrayList<>(mSurfaceChangedCallbacks)) {
                callback.surfaceDestroyed();
            }
        } else {
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                for (SurfaceChangedCallback callback : new ArrayList<>(mSurfaceChangedCallbacks)) {
                    callback.surfaceCreated(transaction);
                }
            }
            requestLayout();
        }
    }

    public boolean isWindowStopped$artzig() { return mStopped; }

    // Preserve AOSP's caller flags, surface-inset preservation and soft-input
    // adjustment rules. Relayout is supplied by the existing desktop geometry seam.
    public void setLayoutParams(WindowManager.LayoutParams attrs, boolean newView) {
        synchronized (this) {
            Rect oldInsets = new Rect(mWindowAttributes.surfaceInsets);
            boolean oldManual = mWindowAttributes.hasManualSurfaceInsets;
            int oldSoftInputMode = mWindowAttributes.softInputMode;
            int visibility = mWindowAttributes.systemUiVisibility;
            int subtreeVisibility = mWindowAttributes.subtreeSystemUiVisibility;
            int appearance = mWindowAttributes.insetsFlags.appearance;
            int behavior = mWindowAttributes.insetsFlags.behavior;
            mClientWindowLayoutFlags = attrs.flags;
            int callerCutoutMode = adjustLayoutInDisplayCutoutMode(attrs);
            int changes = mWindowAttributes.copyFrom(attrs);
            attrs.layoutInDisplayCutoutMode = callerCutoutMode;
            mWindowAttributes.systemUiVisibility = visibility;
            mWindowAttributes.subtreeSystemUiVisibility = subtreeVisibility;
            mWindowAttributes.insetsFlags.appearance = appearance;
            mWindowAttributes.insetsFlags.behavior = behavior;
            if (mWindowAttributes.packageName == null) {
                mWindowAttributes.packageName = mContext.getBasePackageName();
            }
            if (mWindowAttributes.preservePreviousSurfaceInsets) {
                mWindowAttributes.surfaceInsets.set(oldInsets);
                mWindowAttributes.hasManualSurfaceInsets = oldManual;
            } else if (!oldInsets.equals(mWindowAttributes.surfaceInsets)) {
                mHasRenderNode = false;
            }
            if ((attrs.softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                    == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED) {
                mWindowAttributes.softInputMode = (mWindowAttributes.softInputMode
                        & ~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                        | (oldSoftInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
            }
            if (mAttachInfo != null) {
                applyKeepScreenOnFlag(mWindowAttributes);
                if ((changes & WindowManager.LayoutParams.TRANSLUCENT_FLAGS_CHANGED) != 0) {
                    mAttachInfo.mRecomputeGlobalAttributes = true;
                }
                if ((changes & WindowManager.LayoutParams.LAYOUT_CHANGED) != 0) {
                    mAttachInfo.mNeedsUpdateLightCenter = true;
                }
            }
            if ((changes & WindowManager.LayoutParams.COLOR_MODE_CHANGED) != 0) invalidate();
            if (newView || (changes & WindowManager.LayoutParams.LAYOUT_CHANGED) != 0) requestLayout();
            if (mWindowAttributes.softInputMode != oldSoftInputMode) requestFitSystemWindows();
            scheduleTraversals();
        }
    }

    private int adjustLayoutInDisplayCutoutMode(WindowManager.LayoutParams attrs) {
        final int originalMode = attrs.layoutInDisplayCutoutMode;
        if ((attrs.privateFlags & (WindowManager.LayoutParams.PRIVATE_FLAG_EDGE_TO_EDGE_ENFORCED
                | WindowManager.LayoutParams.PRIVATE_FLAG_OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE)) != 0
                && attrs.isFullscreen()
                && attrs.getFitInsetsTypes() == 0
                && attrs.getFitInsetsSides() == 0) {
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        return originalMode;
    }

    void invalidateWorld(View view) {
        view.invalidate();
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) {
                invalidateWorld(parent.getChildAt(i));
            }
        }
    }

    private void applyKeepScreenOnFlag(WindowManager.LayoutParams params) {
        // Update window's global keep screen on flag: if a view has requested
        // that the screen be kept on, then it is always set; otherwise, it is
        // set to whatever the client last requested for the global state.
        if (mAttachInfo.mKeepScreenOn) {
            params.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        } else {
            params.flags = (params.flags&~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    | (mClientWindowLayoutFlags&WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    public void loadSystemProperties() {
        mHandler.post(() -> {
            if (mAttachInfo == null) return;
            if (mAttachInfo.mThreadedRenderer != null
                    && mAttachInfo.mThreadedRenderer.loadSystemProperties()) invalidate();
            boolean layout = android.sysprop.DisplayProperties.debug_layout().orElse(false);
            if (layout != mAttachInfo.mDebugLayout) {
                mAttachInfo.mDebugLayout = layout;
                mHandler.postDelayed(() -> {
                    if (mView != null) invalidateWorld(mView);
                }, 200);
            }
        });
    }

    private final android.content.res.Configuration mLastConfigurationFromResources =
            new android.content.res.Configuration();
    private final android.util.MergedConfiguration mLastReportedMergedConfiguration =
            new android.util.MergedConfiguration();
    private int mViewLayoutDirectionInitial;

    public void requestUpdateConfiguration(android.content.res.Configuration config) {
        // MSG_UPDATE_CONFIGURATION / performConfigurationChange ordering. The
        // desktop dispatcher owns global resource updates; window-context and
        // activity callbacks retain their stock responsibility for overrides.
        mHandler.post(() -> {
            if (mView == null) return;
            Configuration global = config.isOtherSeqNewer(
                    mLastReportedMergedConfiguration.getMergedConfiguration())
                    ? mLastReportedMergedConfiguration.getGlobalConfiguration() : config;
            Configuration override = mLastReportedMergedConfiguration.getOverrideConfiguration();
            synchronized (sConfigCallbacks) {
                for (int i = sConfigCallbacks.size() - 1; i >= 0; --i) {
                    sConfigCallbacks.get(i).onConfigurationChanged(global);
                }
            }
            mLastReportedMergedConfiguration.setConfiguration(global, override);
            if (mActivityConfigCallback != null) {
                mActivityConfigCallback.onConfigurationChanged(override, Display.INVALID_DISPLAY, null);
            } else {
                if (com.android.window.flags.Flags.enableWindowContextResourcesUpdateOnConfigChange()
                        && mContext.getWindowContextToken() instanceof android.window.WindowTokenClient) {
                    android.window.WindowTokenClientController.getInstance()
                            .onWindowConfigurationChanged(mContext.getWindowContextToken(),
                                    mLastReportedMergedConfiguration.getMergedConfiguration(),
                                    getDisplayId());
                }
                updateConfiguration(Display.INVALID_DISPLAY);
            }
        });
    }

    // AOSP updateConfiguration reads the already-updated Resources, then sends
    // display/configuration callbacks and requests a fresh layout in that order.
    public void updateConfiguration(int newDisplayId) {
        if (mView == null) return;
        android.content.res.Configuration config = mView.getResources().getConfiguration();
        if (newDisplayId != Display.INVALID_DISPLAY) onMovedToDisplay(newDisplayId, config);
        if (mLastConfigurationFromResources.diff(config) != 0) {
            updateLastConfigurationFromResources(config);
            mView.dispatchConfigurationChanged(config);
            requestLayout();
        }
    }

    // Pinned ViewRootImpl.updateLastConfigurationFromResources.
    private void updateLastConfigurationFromResources(Configuration config) {
        int oldDirection = mLastConfigurationFromResources.getLayoutDirection();
        mLastConfigurationFromResources.setTo(config);
        if (oldDirection != config.getLayoutDirection() && mView != null
                && mViewLayoutDirectionInitial == View.LAYOUT_DIRECTION_INHERIT) {
            mView.setLayoutDirection(config.getLayoutDirection());
        }
    }

    public void onMovedToDisplay(int displayId, android.content.res.Configuration config) {
        if (mView == null || (mDisplay != null && mDisplay.getDisplayId() == displayId)) return;
        Display display = android.app.ResourcesManager.getInstance()
                .getAdjustedDisplay(displayId, mView.getResources());
        if (display == null) display = android.app.ResourcesManager.getInstance()
                .getAdjustedDisplay(Display.DEFAULT_DISPLAY, mView.getResources());
        mDisplay = display;
        mContext.updateDisplay(display.getDisplayId());
        getImeFocusController().onMovedToDisplay();
        mAttachInfo.mDisplayState = display.getState();
        mView.dispatchMovedToDisplay(display, config);
    }

    private Boolean mCanvasOpaque;

    // AOSP changeCanvasOpacity applies the transparent-region override before
    // changing renderer opacity. The shared renderer always clears; apply this
    // root's background policy to its composited display list instead.
    void changeCanvasOpacity(boolean opaque) {
        if (mView == null) return;
        opaque &= (mView.mPrivateFlags & View.PFLAG_REQUEST_TRANSPARENT_REGIONS) == 0;
        if (mCanvasOpaque == null || mCanvasOpaque != opaque) {
            mCanvasOpaque = opaque;
            mHasRenderNode = false;
            invalidate();
        }
    }

    /**
     * Supplies the host-owned window constraints before Choreographer's traversal phase.
     * AOSP obtains these constraints from WMS relayout; the hosted runtime has no WMS.
     */
    public void prepareWindowTraversal$artzig(WindowManager.LayoutParams lp, int maxW, int maxH) {
        mWindowAttributes.width = lp.width;
        mWindowAttributes.height = lp.height;
        mWindowAttributes.x = lp.x;
        mWindowAttributes.y = lp.y;
        mWindowAttributes.gravity = lp.gravity;
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
        mIsInTraversal = true;
        try { performTraversal(); } finally { mIsInTraversal = false; }
        return getRenderNode$artzig();
    }

    public RenderNode getRenderNode$artzig() {
        return mView != null && !mStopped && mHasRenderNode ? mRenderNode : null;
    }

    private void performTraversal() {
        if (mView == null || mStopped) return;
        if (mForceNextWindowRelayout) {
            mLayoutRequested = true;
            mForceNextWindowRelayout = false;
        }
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
        captureFrameCommitCallbacks();
        // AOSP ViewRootImpl.draw(): observers run before recording display lists.
        for (int i = 0; i < mSurfaceChangedCallbacks.size(); i++) {
            mSurfaceChangedCallbacks.get(i).vriDrawStarted(false);
        }
        mAttachInfo.mTreeObserver.dispatchOnDraw();
        RenderNode viewNode = mView.updateDisplayListIfDirty();
        if (!mHasRenderNode) {
            RecordingCanvas canvas = mRenderNode.beginRecording(width, height);
            try {
                // Overlay roots are replayed into the activity composite, so a clear would punch
                // through transparent popup corners. The activity root remains opaque black.
                if (mCanvasOpaque != null ? mCanvasOpaque : !mHostedWindowLayout) {
                    canvas.drawColor(0xff000000);
                }
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
        if (mStopped || mRemoved) return;
        if (!mTraversalScheduled) {
            mTraversalScheduled = true;
            // AOSP ViewRootImpl.scheduleTraversals(). WMS and DisplayEventReceiver are absent,
            // but the main-queue barrier and Choreographer phase ordering are process-local.
            mTraversalBarrier = mHandler.getLooper().getQueue().postSyncBarrier();
            mChoreographer.postCallback(Choreographer.CALLBACK_TRAVERSAL,
                    mTraversalRunnable, null);
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
    @Override
    public boolean requestSendAccessibilityEvent(View child, AccessibilityEvent event) {
        if (mView == null || mStopped || mPausedForTransition) {
            return false;
        }

        // Immediately flush pending content changed event (if any) to preserve event order
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && mSendWindowContentChangedAccessibilityEvent != null
                && mSendWindowContentChangedAccessibilityEvent.mSource != null) {
            mSendWindowContentChangedAccessibilityEvent.removeCallbacksAndRun();
        }

        // Intercept accessibility focus events fired by virtual nodes to keep
        // track of accessibility focus position in such nodes.
        final int eventType = event.getEventType();
        final View source = getSourceForAccessibilityEvent(event);
        switch (eventType) {
            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED: {
                if (source != null) {
                    AccessibilityNodeProvider provider = source.getAccessibilityNodeProvider();
                    if (provider != null) {
                        final int virtualNodeId = AccessibilityNodeInfo.getVirtualDescendantId(
                                event.getSourceNodeId());
                        final AccessibilityNodeInfo node;
                        node = provider.createAccessibilityNodeInfo(virtualNodeId);
                        setAccessibilityFocus(source, node);
                    }
                }
            } break;
            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED: {
                if (source != null && source.getAccessibilityNodeProvider() != null) {
                    setAccessibilityFocus(null, null);
                }
            } break;


            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: {
                handleWindowContentChangedEvent(event);
            } break;
        }
        mAccessibilityManager.sendAccessibilityEvent(event);
        return true;
    }
    @Override
    public void notifySubtreeAccessibilityStateChanged(View child, View source, int changeType) {
        postSendWindowContentChangedCallback(Objects.requireNonNull(source), changeType);
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
        // AOSP ViewRootImpl constructor collaborators, retained lazily by this host root.
        if (mHandwritingInitiator == null) {
            mHandwritingInitiator = new HandwritingInitiator(ViewConfiguration.get(mContext),
                    mContext.getSystemService(android.view.inputmethod.InputMethodManager.class));
        }
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
        applyKeepScreenOnFlag(mWindowAttributes);

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
    public View getAccessibilityFocusedHost() { return mAccessibilityFocusedHost; }
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
    /** android 16: tracing wrapper over notifyRendererOfExpensiveFrame(). */
    public void notifyRendererOfExpensiveFrame(String reason) { notifyRendererOfExpensiveFrame(); }
    /** android 16: AOSP drops its cached vibrator; the hosted root caches none. */
    public void clearSystemVibrator() {}
    /** android 16: insets animating-type bookkeeping rides the WM session, absent here. */
    public void updateAnimatingTypes(int animatingTypes, android.view.inputmethod.ImeTracker.Token statsToken) {}
    /** android 16 (AOSP ViewRootImpl.categoryToString). */
    static String categoryToString(int frameRateCategory) {
        switch (frameRateCategory) {
            case Surface.FRAME_RATE_CATEGORY_NO_PREFERENCE: return "no preference";
            case Surface.FRAME_RATE_CATEGORY_LOW: return "low";
            case Surface.FRAME_RATE_CATEGORY_NORMAL: return "normal";
            case Surface.FRAME_RATE_CATEGORY_HIGH_HINT: return "high hint";
            case Surface.FRAME_RATE_CATEGORY_HIGH: return "high";
            default: return "default";
        }
    }
    /** android 16: the hosted root never changes the default compatibility. */
    public int getFrameRateCompatibility() { return Surface.FRAME_RATE_COMPATIBILITY_DEFAULT; }
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
    public void setAccessibilityFocus(View view, AccessibilityNodeInfo node) {
        // If we have a virtual view with accessibility focus we need
        // to clear the focus and invalidate the virtual view bounds.
        if (mAccessibilityFocusedVirtualView != null) {

            AccessibilityNodeInfo focusNode = mAccessibilityFocusedVirtualView;
            View focusHost = mAccessibilityFocusedHost;

            // Wipe the state of the current accessibility focus since
            // the call into the provider to clear accessibility focus
            // will fire an accessibility event which will end up calling
            // this method and we want to have clean state when this
            // invocation happens.
            mAccessibilityFocusedHost = null;
            mAccessibilityFocusedVirtualView = null;

            // Clear accessibility focus on the host after clearing state since
            // this method may be reentrant.
            focusHost.clearAccessibilityFocusNoCallbacks(
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);

            AccessibilityNodeProvider provider = focusHost.getAccessibilityNodeProvider();
            if (provider != null) {
                // Invalidate the area of the cleared accessibility focus.
                focusNode.getBoundsInParent(mTempRect);
                focusHost.invalidate(mTempRect);
                // Clear accessibility focus in the virtual node.
                final int virtualNodeId = AccessibilityNodeInfo.getVirtualDescendantId(
                        focusNode.getSourceNodeId());
                provider.performAction(virtualNodeId,
                        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null);
            }
            focusNode.recycle();
        }
        if ((mAccessibilityFocusedHost != null) && (mAccessibilityFocusedHost != view))  {
            // Clear accessibility focus in the view.
            mAccessibilityFocusedHost.clearAccessibilityFocusNoCallbacks(
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        }

        // Set the new focus host and node.
        mAccessibilityFocusedHost = view;
        mAccessibilityFocusedVirtualView = node;
        // The hosted window backend has no WindowManager keep-clear regions.

        invalidate();
    }
    public final ViewFrameInfo mViewFrameInfo = new ViewFrameInfo();

    // AOSP getUpdatedFrameInfo: merge this root's timing into the shared frame,
    // then reset it. Hosted input has no InputEventAssigner timeline to finish.
    protected android.graphics.FrameInfo getUpdatedFrameInfo() {
        android.graphics.FrameInfo info = mChoreographer.mFrameInfo;
        mViewFrameInfo.populateFrameInfo(info);
        mViewFrameInfo.reset();
        return info;
    }
    public Thread mThread = Thread.currentThread();
    public void addObserver(android.graphics.HardwareRendererObserver observer) {}
    public void removeObserver(android.graphics.HardwareRendererObserver observer) {}
    public void dumpDebug(android.util.proto.ProtoOutputStream proto, long fieldId) {}
    public int getDisplayId() { return mDisplay != null ? mDisplay.getDisplayId() : Display.DEFAULT_DISPLAY; }
    public android.os.IBinder getWindowToken() { return mWindow == null ? null : mWindow.asBinder(); }
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
    public int getHostVisibility() { return mView == null ? View.GONE : mView.getVisibility(); }
    public void updateCompatSysUiVisibility(int type, int visibility, int mask) {}
    public void registerRtFrameCallback(android.graphics.HardwareRenderer.FrameDrawingCallback callback) {}
    public int dipToPx(int dip) { return dip; }
    public void notifyInsetsAnimationRunningStateChanged(boolean running) {}
    public void updateKeepClearRectsForView(View view) {}
    public boolean shouldCheckFrameRateCategory() { return false; }
    public boolean shouldCheckFrameRate(boolean b) { return false; }
    public void setLocalDragState(Object obj) {}
    public void dispatchCheckFocus() {}
    public CharSequence getTitle() { return mWindowAttributes.getTitle(); }
    public boolean isHandlingPointerEvent() { return false; }
    public void setDragFocus(View newDragTarget, DragEvent event) {}
    public boolean performHapticFeedback(int effectId, boolean always, boolean fromIme) { return false; }
    public void playSoundEffect(int effectId) {}
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
    /** Register callbacks to be notified when the ViewRootImpl surface changes. */
    public interface SurfaceChangedCallback {
        void surfaceCreated(Transaction t);
        void surfaceReplaced(Transaction t);
        void surfaceDestroyed();
        default void vriDrawStarted(boolean isWmSync) {};
    }

    private final ArrayList<SurfaceChangedCallback> mSurfaceChangedCallbacks = new ArrayList<>();
    public void addSurfaceChangedCallback(SurfaceChangedCallback c) {
        mSurfaceChangedCallbacks.add(c);
    }

    public void removeSurfaceChangedCallback(SurfaceChangedCallback c) {
        mSurfaceChangedCallbacks.remove(c);
    }

    // The hosted root has no SurfaceControl-backed surface yet. Keep the stock
    // callback registry; creation/replacement/destruction require such a surface.
    public SurfaceControl getSurfaceControl() { return new SurfaceControl(); }
    public void mergeWithNextTransaction(SurfaceControl.Transaction t, long frameNumber) { t.apply(); }
    public void registerAnimatingRenderNode(android.graphics.RenderNode renderNode) {
        dev.artzig.HostedRenderer.getInstance(mContext).renderer.registerAnimatingRenderNode(renderNode);
        scheduleTraversals();
    }
    public void registerVectorDrawableAnimator(NativeVectorDrawableAnimator animator) {
        dev.artzig.HostedRenderer.getInstance(mContext).renderer.registerVectorDrawableAnimator(animator);
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
    public void dispatchInputEvent(InputEvent event) { dispatchInputEvent(event, null); }
    // Pinned dispatchInputEvent retains the receiver through asynchronous delivery.
    public void dispatchInputEvent(InputEvent event, InputEventReceiver receiver) {
        if (mInputEventQueue == null) mInputEventQueue = new HostedInputDispatcher.EventQueue(this);
        mInputEventQueue.enqueue(event, receiver);
    }
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
        /**
         * Notifies about override config change and/or move to different display.
         * @param overrideConfig New override config to apply to activity.
         * @param newDisplayId New display id, {@link Display#INVALID_DISPLAY} if not changed.
         */
        default void onConfigurationChanged(@NonNull Configuration overrideConfig,
                int newDisplayId) {
            // Must override one of the #onConfigurationChanged.
            throw new IllegalStateException("Not implemented");
        }

        /**
         * Notifies about override config change and/or move to different display.
         * @param overrideConfig New override config to apply to activity.
         * @param newDisplayId New display id, {@link Display#INVALID_DISPLAY} if not changed.
         * @param activityWindowInfo New ActivityWindowInfo to apply to activity.
         */
        default void onConfigurationChanged(@NonNull Configuration overrideConfig,
                int newDisplayId, @Nullable ActivityWindowInfo activityWindowInfo) {
            onConfigurationChanged(overrideConfig, newDisplayId);
        }
    }
    private ActivityConfigCallback mActivityConfigCallback;
    public void setActivityConfigCallback(ActivityConfigCallback callback) { mActivityConfigCallback = callback; }
    public interface ConfigChangedCallback {
        void onConfigurationChanged(android.content.res.Configuration globalConfig);
    }
    private static final ArrayList<ConfigChangedCallback> sConfigCallbacks = new ArrayList<>();
    public static void addConfigCallback(ConfigChangedCallback callback) {
        synchronized (sConfigCallbacks) { sConfigCallbacks.add(callback); }
    }
    public static void removeConfigCallback(ConfigChangedCallback callback) {
        synchronized (sConfigCallbacks) { sConfigCallbacks.remove(callback); }
    }
    public android.view.accessibility.AccessibilityNodeInfo getAccessibilityFocusedVirtualView() { return mAccessibilityFocusedVirtualView; }
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
    // Reused AOSP ViewRootImpl accessibility delivery, content-change throttling,
    // and focus bookkeeping. The hosted root still owns the view hierarchy until
    // IWindowSession is available; no test consumer is called by these methods.
    private void postSendWindowContentChangedCallback(View source, int changeType) {
        if (mSendWindowContentChangedAccessibilityEvent == null) {
            mSendWindowContentChangedAccessibilityEvent =
                new SendWindowContentChangedAccessibilityEvent();
        }
        mSendWindowContentChangedAccessibilityEvent.runOrPost(source, changeType);
    }

    private void removeSendWindowContentChangedCallback() {
        if (mSendWindowContentChangedAccessibilityEvent != null) {
            mHandler.removeCallbacks(mSendWindowContentChangedAccessibilityEvent);
        }
    }

    private View getSourceForAccessibilityEvent(AccessibilityEvent event) {
        final long sourceNodeId = event.getSourceNodeId();
        final int accessibilityViewId = AccessibilityNodeInfo.getAccessibilityViewId(
                sourceNodeId);
        return AccessibilityNodeIdManager.getInstance().findView(accessibilityViewId);
    }

    private void handleWindowContentChangedEvent(AccessibilityEvent event) {
        final View focusedHost = mAccessibilityFocusedHost;
        if (focusedHost == null || mAccessibilityFocusedVirtualView == null) {
            // No virtual view focused, nothing to do here.
            return;
        }

        final AccessibilityNodeProvider provider = focusedHost.getAccessibilityNodeProvider();
        if (provider == null) {
            // Error state: virtual view with no provider. Clear focus.
            mAccessibilityFocusedHost = null;
            mAccessibilityFocusedVirtualView = null;
            focusedHost.clearAccessibilityFocusNoCallbacks(0);
            return;
        }

        // We only care about change types that may affect the bounds of the
        // focused virtual view.
        final int changes = event.getContentChangeTypes();
        if ((changes & AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE) == 0
                && changes != AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED) {
            return;
        }

        final long eventSourceNodeId = event.getSourceNodeId();
        final int changedViewId = AccessibilityNodeInfo.getAccessibilityViewId(eventSourceNodeId);

        // Search up the tree for subtree containment.
        boolean hostInSubtree = false;
        View root = mAccessibilityFocusedHost;
        while (root != null && !hostInSubtree) {
            if (changedViewId == root.getAccessibilityViewId()) {
                hostInSubtree = true;
            } else {
                final ViewParent parent = root.getParent();
                if (parent instanceof View) {
                    root = (View) parent;
                } else {
                    root = null;
                }
            }
        }

        // We care only about changes in subtrees containing the host view.
        if (!hostInSubtree) {
            return;
        }

        final long focusedSourceNodeId = mAccessibilityFocusedVirtualView.getSourceNodeId();
        int focusedChildId = AccessibilityNodeInfo.getVirtualDescendantId(focusedSourceNodeId);

        // Refresh the node for the focused virtual view.
        final Rect oldBounds = mTempRect;
        mAccessibilityFocusedVirtualView.getBoundsInScreen(oldBounds);
        mAccessibilityFocusedVirtualView = provider.createAccessibilityNodeInfo(focusedChildId);
        if (mAccessibilityFocusedVirtualView == null) {
            // Error state: The node no longer exists. Clear focus.
            mAccessibilityFocusedHost = null;
            focusedHost.clearAccessibilityFocusNoCallbacks(0);

            // This will probably fail, but try to keep the provider's internal
            // state consistent by clearing focus.
            provider.performAction(focusedChildId,
                    AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS.getId(), null);
            invalidate();
        } else {
            // The node was refreshed, invalidate bounds if necessary.
            final Rect newBounds = mAccessibilityFocusedVirtualView.getBoundsInScreen();
            if (!oldBounds.equals(newBounds)) {
                oldBounds.union(newBounds);
                invalidate();
            }
        }
    }

    private View getCommonPredecessor(View first, View second) {
        if (mTempHashSet == null) {
            mTempHashSet = new HashSet<View>();
        }
        HashSet<View> seen = mTempHashSet;
        seen.clear();
        View firstCurrent = first;
        while (firstCurrent != null) {
            seen.add(firstCurrent);
            ViewParent firstCurrentParent = firstCurrent.mParent;
            if (firstCurrentParent instanceof View) {
                firstCurrent = (View) firstCurrentParent;
            } else {
                firstCurrent = null;
            }
        }
        View secondCurrent = second;
        while (secondCurrent != null) {
            if (seen.contains(secondCurrent)) {
                seen.clear();
                return secondCurrent;
            }
            ViewParent secondCurrentParent = secondCurrent.mParent;
            if (secondCurrentParent instanceof View) {
                secondCurrent = (View) secondCurrentParent;
            } else {
                secondCurrent = null;
            }
        }
        seen.clear();
        return null;
    }

    private class SendWindowContentChangedAccessibilityEvent implements Runnable {
        private int mChangeTypes = 0;

        public View mSource;
        public long mLastEventTimeMillis;
        /**
         * Keep track of action that caused the event.
         * This is empty if there's no performing actions for pending events.
         * This is zero if there're multiple events performed for pending events.
         */
        @NonNull public OptionalInt mAction = OptionalInt.empty();
        /**
         * Override for {@link AccessibilityEvent#originStackTrace} to provide the stack trace
         * of the original {@link #runOrPost} call instead of one for sending the delayed event
         * from a looper.
         */
        public StackTraceElement[] mOrigin;

        @Override
        public void run() {
            // Protect against re-entrant code and attempt to do the right thing in the case that
            // we're multithreaded.
            View source = mSource;
            mSource = null;
            if (source == null) {
                Log.e(TAG, "Accessibility content change has no source");
                return;
            }
            // The accessibility may be turned off while we were waiting so check again.
            if (mAccessibilityManager.isEnabled()) {
                mLastEventTimeMillis = SystemClock.uptimeMillis();
                AccessibilityEvent event = AccessibilityEvent.obtain();
                event.setEventType(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
                event.setContentChangeTypes(mChangeTypes);
                if (mAction.isPresent()) event.setAction(mAction.getAsInt());
                if (AccessibilityEvent.DEBUG_ORIGIN) event.originStackTrace = mOrigin;
                source.sendAccessibilityEventUnchecked(event);
            } else {
                mLastEventTimeMillis = 0;
            }
            // In any case reset to initial state.
            source.resetSubtreeAccessibilityStateChanged();
            mChangeTypes = 0;
            mAction = OptionalInt.empty();
            if (AccessibilityEvent.DEBUG_ORIGIN) mOrigin = null;
        }

        public void runOrPost(View source, int changeType) {
            if (mHandler.getLooper() != Looper.myLooper()) {
                CalledFromWrongThreadException e = new CalledFromWrongThreadException("Only the "
                        + "original thread that created a view hierarchy can touch its views.");
                // TODO: Throw the exception
                Log.e(TAG, "Accessibility content change on non-UI thread. Future Android "
                        + "versions will throw an exception.", e);
                // Attempt to recover. This code does not eliminate the thread safety issue, but
                // it should force any issues to happen near the above log.
                mHandler.removeCallbacks(this);
                if (mSource != null) {
                    // Dispatch whatever was pending. It's still possible that the runnable started
                    // just before we removed the callbacks, and bad things will happen, but at
                    // least they should happen very close to the logged error.
                    run();
                }
            }

            if (!canContinueThrottle(source, changeType)) {
                removeCallbacksAndRun();
            }

            if (mSource != null) {
                View newSource = getCommonPredecessor(mSource, source);
                if (newSource != null) {
                    newSource = newSource.getSelfOrParentImportantForA11y();
                }
                if (newSource == null) {
                    // If there is no common predecessor, then mSource points to
                    // a removed view, hence in this case always prefer the source.
                    newSource = source;
                }

                mChangeTypes |= changeType;
                if (mSource != newSource) {
                    mChangeTypes |= AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE;
                    mSource = newSource;
                }

                final int performingAction = mAccessibilityManager.getPerformingAction();
                if (performingAction != 0) {
                    if (mAction.isEmpty()) {
                        mAction = OptionalInt.of(performingAction);
                    } else if (mAction.getAsInt() != performingAction) {
                        // Multiple actions are performed for pending events. We cannot decide one
                        // action here.
                        // We're doing best effort to set action value, and it's fine to set
                        // no action this case.
                        mAction = OptionalInt.of(0);
                    }
                }

                return;
            }
            mSource = source;
            mChangeTypes = changeType;
            if (mAccessibilityManager.getPerformingAction() != 0) {
                mAction = OptionalInt.of(mAccessibilityManager.getPerformingAction());
            }
            if (AccessibilityEvent.DEBUG_ORIGIN) {
                mOrigin = Thread.currentThread().getStackTrace();
            }
            final long timeSinceLastMillis = SystemClock.uptimeMillis() - mLastEventTimeMillis;
            final long minEventIntervalMillis =
                    ViewConfiguration.getSendRecurringAccessibilityEventsInterval();
            if (timeSinceLastMillis >= minEventIntervalMillis) {
                removeCallbacksAndRun();
            } else {
                mHandler.postDelayed(this, minEventIntervalMillis - timeSinceLastMillis);
            }
        }

        public void removeCallbacksAndRun() {
            mHandler.removeCallbacks(this);
            run();
        }

        private boolean canContinueThrottle(View source, int changeType) {
            if (!reduceWindowContentChangedEventThrottle()) {
                // Old behavior. Always throttle.
                return true;
            }
            if (mSource == null) {
                // We don't have a pending event.
                return true;
            }
            if (mSource == source) {
                // We can merge a new event with a pending event from the same source.
                return true;
            }
            // We can merge subtree change events.
            return changeType == AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
                    && mChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE;
        }
    }


    public static final class CalledFromWrongThreadException extends AndroidRuntimeException {
        @UnsupportedAppUsage
        public CalledFromWrongThreadException(String msg) {
            super(msg);
        }
    }
    public void setPausedForTransition(boolean paused) {
        mPausedForTransition = paused;
        mTransitionInput.setPaused(paused);
    }
}
