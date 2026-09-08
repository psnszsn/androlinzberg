/* artzig shim: SurfaceView over a Wayland subsurface video layer. The view
 * punches a transparent hole so the layer (placed below the UI surface)
 * shows through; the compositor scales the buffer to the view rect. */
package android.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;

import java.util.concurrent.CopyOnWriteArrayList;

public class SurfaceView extends View implements ViewRootImpl.SurfaceChangedCallback {
    public static final int SURFACE_LIFECYCLE_DEFAULT = 0;
    public static final int SURFACE_LIFECYCLE_FOLLOWS_VISIBILITY = 1;
    public static final int SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT = 2;

    private final CopyOnWriteArrayList<SurfaceHolder.Callback> mCallbacks =
            new CopyOnWriteArrayList<>();
    private Surface mSurface;
    private boolean mCreated;
    private boolean mAttachedToWindow;
    private boolean mWindowStopped;
    private int mRequestedSurfaceLifecycleStrategy = SURFACE_LIFECYCLE_DEFAULT;
    private final int[] mLocation = new int[2];
    private final Rect mVisibleRect = new Rect();
    private int mLastX = Integer.MIN_VALUE, mLastY, mLastW, mLastH;
    private int mLastVisibleX, mLastVisibleY, mLastVisibleW, mLastVisibleH;

    public SurfaceView(Context context) { this(context, null); }
    public SurfaceView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public SurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }
    public SurfaceView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setWillNotDraw(false);
    }

    private final SurfaceHolder mHolder = new SurfaceHolder() {
        @Override public void addCallback(SurfaceHolder.Callback callback) {
            mCallbacks.addIfAbsent(callback);
            if (mCreated) callback.surfaceCreated(this);
        }
        @Override public void removeCallback(SurfaceHolder.Callback callback) {
            mCallbacks.remove(callback);
        }
        @Override public boolean isCreating() { return false; }
        @Override public void setType(int type) {}
        @Override public void setFixedSize(int width, int height) {}
        @Override public void setSizeFromLayout() {}
        @Override public void setFormat(int format) {}
        @Override public void setKeepScreenOn(boolean screenOn) {}
        @Override public Canvas lockCanvas() { return null; }
        @Override public Canvas lockCanvas(Rect dirty) { return null; }
        @Override public void unlockCanvasAndPost(Canvas canvas) {}
        @Override public Rect getSurfaceFrame() {
            return new Rect(0, 0, getWidth(), getHeight());
        }
        @Override public Surface getSurface() { return mSurface; }
    };

    public SurfaceHolder getHolder() { return mHolder; }

    /** Pinned SurfaceView.setChildSurfacePackage requires compositor reparenting,
     * accessibility association and an embedded input channel. */
    public void setChildSurfacePackage(SurfaceControlViewHost.SurfacePackage surfacePackage) {
        dev.artzig.HostedWindowRoots.attachEmbeddedSurface(surfacePackage);
    }


    /** AOSP SurfaceView.getName(), used by SurfaceSyncGroup diagnostics. */
    public String getName() {
        ViewRootImpl viewRoot = getViewRootImpl();
        String viewRootName = viewRoot == null ? "detached" : viewRoot.getTitle().toString();
        return "SurfaceView[" + viewRootName + "]";
    }

    /** AOSP delegates this operation to BLASTBufferQueue.syncNextTransaction.
     * The hosted video layer has no BLAST transaction producer. */
    public void syncNextFrame(java.util.function.Consumer<SurfaceControl.Transaction> consumer) {
        dev.artzig.system.HostedWindowManagerService.syncNextSurfaceFrame(consumer);
    }


    /**
     * Follows AOSP SurfaceView.setSurfaceLifecycle/updateSurface. SurfaceControl is unavailable
     * on the host, so creation and destruction are applied to the Wayland video-layer seam.
     */
    public void setSurfaceLifecycle(int lifecycleStrategy) {
        mRequestedSurfaceLifecycleStrategy = lifecycleStrategy;
        updateSurface();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAttachedToWindow = true;
        mWindowStopped = false;
        getViewRootImpl().addSurfaceChangedCallback(this);
        updateSurface();
        updateRect();
    }

    @Override public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        updateSurface();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateSurface();
    }

    @Override protected void onDetachedFromWindow() {
        // Follows AOSP SurfaceView.onDetachedFromWindow(): updateSurface()
        // makes the surface unavailable and delivers surfaceDestroyed before
        // releaseSurfaces(). The host has no SurfaceControl, so its Wayland
        // video layer is the resource released at that seam.
        mAttachedToWindow = false;
        ViewRootImpl root = getViewRootImpl();
        if (root != null) root.removeSurfaceChangedCallback(this);
        updateSurface();
        super.onDetachedFromWindow();
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateRect();
        if (mCreated) {
            for (SurfaceHolder.Callback cb : mCallbacks) {
                cb.surfaceChanged(mHolder, android.graphics.PixelFormat.RGBX_8888,
                        getWidth(), getHeight());
            }
        }
    }

    private void updateRect() {
        if (mSurface == null || getWidth() == 0) return;
        getLocationInWindow(mLocation);
        View root = getRootView();
        boolean visible = isShown() && getGlobalVisibleRect(mVisibleRect)
                && root != null
                && mVisibleRect.intersect(0, 0, root.getWidth(), root.getHeight())
                && mVisibleRect.intersect(mLocation[0], mLocation[1],
                        mLocation[0] + getWidth(), mLocation[1] + getHeight());
        int visibleX = visible ? mVisibleRect.left : 0;
        int visibleY = visible ? mVisibleRect.top : 0;
        int visibleW = visible ? mVisibleRect.width() : 0;
        int visibleH = visible ? mVisibleRect.height() : 0;
        if (mLocation[0] != mLastX || mLocation[1] != mLastY
                || getWidth() != mLastW || getHeight() != mLastH
                || visibleX != mLastVisibleX || visibleY != mLastVisibleY
                || visibleW != mLastVisibleW || visibleH != mLastVisibleH) {
            mLastX = mLocation[0];
            mLastY = mLocation[1];
            mLastW = getWidth();
            mLastH = getHeight();
            mLastVisibleX = visibleX;
            mLastVisibleY = visibleY;
            mLastVisibleW = visibleW;
            mLastVisibleH = visibleH;
            ArtzigVideoLayer.setGeometry(mSurface,
                    mLastX, mLastY, mLastW, mLastH,
                    visibleX, visibleY, visibleW, visibleH);
        }
    }

    // Pinned protected updateSurface entry point; the existing hosted lifecycle owns
    // allocation, callbacks, geometry and release until SurfaceControl is available.
    protected void updateSurface() {
        boolean followsAttachment = mRequestedSurfaceLifecycleStrategy
                == SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT;
        boolean shouldExist = mAttachedToWindow && !mWindowStopped && (followsAttachment
                || (getVisibility() == VISIBLE && getWindowVisibility() == VISIBLE));
        if (shouldExist) {
            if (mSurface == null) {
                mSurface = ArtzigVideoLayer.createSurface();
                // The destination cache belongs to the old native video layer.
                // A replacement surface must receive its rectangle even when the
                // Java view was reattached at exactly the same bounds.
                mLastX = Integer.MIN_VALUE;
            }
            if (!mCreated) {
                mCreated = true;
                for (SurfaceHolder.Callback cb : mCallbacks) cb.surfaceCreated(mHolder);
                if (getWidth() > 0 && getHeight() > 0) {
                    for (SurfaceHolder.Callback cb : mCallbacks) {
                        cb.surfaceChanged(mHolder, android.graphics.PixelFormat.RGBX_8888,
                                getWidth(), getHeight());
                    }
                }
            }
            updateRect();
        } else {
            if (mCreated) {
                mCreated = false;
                for (SurfaceHolder.Callback cb : mCallbacks) cb.surfaceDestroyed(mHolder);
            }
            if (mSurface != null) {
                ArtzigVideoLayer.destroy(mSurface);
                mSurface = null;
            }
        }
    }

    // AOSP SurfaceView's ViewRootImpl callbacks stop and resume the surface
    // independently of the requested visibility/attachment lifecycle strategy.
    @Override public void surfaceCreated(SurfaceControl.Transaction transaction) {
        mWindowStopped = false;
        updateSurface();
    }

    @Override public void surfaceDestroyed() {
        mWindowStopped = true;
        updateSurface();
    }

    @Override public void surfaceReplaced(SurfaceControl.Transaction transaction) {
        updateRect();
    }

    /**
     * Host traversal hook. A real SurfaceView has a RenderThread position
     * listener which keeps its SurfaceControl in step with ancestor property
     * animations. The host Wayland subsurface needs the equivalent update
     * even when this view's cached display list is not re-recorded.
     */
    void updateSurfacePosition$artzig() {
        updateRect();
    }

    @Override public void draw(Canvas canvas) {
        // Restrict the transparent hole to this view. Clearing the canvas color
        // would clear the entire damage clip, which can extend beyond our bounds
        // while a resize or ancestor animation is in progress.
        canvas.punchHole(0f, 0f, getWidth(), getHeight(), 0f, 0f, getAlpha());
        super.draw(canvas);
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        updateRect();
        super.dispatchDraw(canvas);
    }

    public SurfaceControl getSurfaceControl() { return new SurfaceControl(); }
    public void setZOrderOnTop(boolean onTop) {}
    public void setZOrderMediaOverlay(boolean isMediaOverlay) {}
    public void setSecure(boolean isSecure) {}
    public void setUseAlpha() {}
}
