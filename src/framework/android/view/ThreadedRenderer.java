/* artzig host seam: AOSP ThreadedRenderer is the RenderThread client; this one drives the hosted HardwareRenderer (Ganesh/GL on the Wayland EGL surface) directly. */
package android.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.DisplayMetrics;
import android.view.animation.AnimationUtils;
import com.android.internal.R;
import android.graphics.Bitmap;
import android.graphics.HardwareRenderer;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import java.util.concurrent.Executor;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/** Host HWUI renderer backed by Ganesh/OpenGL and a direct Wayland EGL surface. */
public final class ThreadedRenderer extends HardwareRenderer {
    private static ThreadedRenderer sInstance;

    private int mWidth;
    private int mHeight;
    private final RenderNode mScaledRoot = new RenderNode("artzig-scaled-root");
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    private ThreadedRenderer() {
        super();
        setName("artzig-host-hwui");
        setOpaque(false);
        setLightSourceGeometry(0f, 0f, 600f, 800f);
        setLightSourceAlpha(0.039f, 0.19f);
    }

    public static synchronized ThreadedRenderer getInstance() {
        if (sInstance == null) sInstance = new ThreadedRenderer();
        return sInstance;
    }

    public static synchronized void destroyInstance() {
        if (sInstance != null) {
            sInstance.destroy();
            sInstance = null;
        }
    }

    /** Synchronize one display-list frame; RenderThread presents it through EGL. */
    public synchronized void render(RenderNode content, int width, int height,
            int bufferWidth, int bufferHeight) {
        // Compatibility with the first fractional-scale host ABI. New hosts
        // record directly in physical pixels and use the three-argument form.
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid logical target " + width + "x" + height);
        }
        if (bufferWidth <= 0 || bufferHeight <= 0) {
            throw new IllegalArgumentException(
                    "invalid Wayland buffer " + bufferWidth + "x" + bufferHeight);
        }
        RenderNode target = content;
        if (content != null && (bufferWidth != width || bufferHeight != height)) {
            mScaledRoot.setPosition(0, 0, bufferWidth, bufferHeight);
            RecordingCanvas canvas = mScaledRoot.beginRecording(bufferWidth, bufferHeight);
            try {
                canvas.scale(bufferWidth / (float) width, bufferHeight / (float) height);
                canvas.drawRenderNode(content);
            } finally {
                mScaledRoot.endRecording();
            }
            target = mScaledRoot;
        }
        render(target, bufferWidth, bufferHeight);
    }

    /** Synchronize one display-list frame at the EGL buffer's physical size. */
    public synchronized void render(RenderNode content, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid render target " + width + "x" + height);
        }
        if (width != mWidth || height != mHeight) {
            mWidth = width;
            mHeight = height;
            nConfigureHostSurface(width, height);
        }
        setContentRoot(content);
        forceDrawNextFrame();
        // AOSP HardwareRenderer recommends pipelined presentation for normal frames. The frame
        // commit callback wakes the Wayland owner after RenderThread has submitted the child EGL
        // buffer; that thread then publishes the synchronized parent transaction.
        int result = createRenderRequest()
                .setVsyncTime(System.nanoTime())
                .setFrameCommitCallback(DIRECT_EXECUTOR, ThreadedRenderer::nNotifyHostFrameCommitted)
                .setWaitForPresent(false)
                .syncAndDraw();
        // A dropped request produces no child-surface commit. Release the parent transaction
        // explicitly so its wl_surface.frame callback can pace a later retry; eventfd coalescing
        // makes this safe if HWUI also completes the registered callback while dropping.
        if ((result & SYNC_FRAME_DROPPED) != 0) {
            nNotifyHostFrameCommitted();
        }
        if ((result & SYNC_LOST_SURFACE_REWARD_IF_FOUND) != 0) {
            throw new IllegalStateException("HWUI lost the host render surface: " + result);
        }
    }

    @Override
    public synchronized void destroy() {
        nDestroyHostSurface();
        super.destroy();
    }

    private static int dumpArgsToFlags(String[] args) {
        // If there's no arguments, eg 'dumpsys gfxinfo', then dump everything.
        // If there's a targetted package, eg 'dumpsys gfxinfo com.android.systemui', then only
        // dump the summary information
        if (args == null || args.length == 0) {
            return FLAG_DUMP_ALL;
        }
        int flags = 0;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "framestats":
                    flags |= FLAG_DUMP_FRAMESTATS;
                    break;
                case "reset":
                    flags |= FLAG_DUMP_RESET;
                    break;
                case "-a": // magic option passed when dumping a bugreport.
                    flags = FLAG_DUMP_ALL;
                    break;
            }
        }
        return flags;
    }

    /** @hide */
    public static void handleDumpGfxInfo(FileDescriptor fd, String[] args) {
        dumpGlobalProfileInfo(fd, dumpArgsToFlags(args));
        WindowManagerGlobal.getInstance().dumpGfxInfo(fd, args);
    }

    /**
     * Outputs extra debugging information in the specified file descriptor.
     */
    void dumpGfxInfo(PrintWriter pw, FileDescriptor fd, String[] args) {
        pw.flush();
        dumpProfileInfo(fd, dumpArgsToFlags(args));
    }


    public void buildLayer(RenderNode node) {}

    // Verbatim AOSP ThreadedRenderer.SimpleRenderer over HardwareRenderer.
    public static class SimpleRenderer extends HardwareRenderer {
        private final float mLightY, mLightZ, mLightRadius;

        public SimpleRenderer(final Context context, final String name, final Surface surface) {
            super();
            setName(name);
            setOpaque(false);
            setSurface(surface);
            final TypedArray a = context.obtainStyledAttributes(null, R.styleable.Lighting, 0, 0);
            mLightY = a.getDimension(R.styleable.Lighting_lightY, 0);
            mLightZ = a.getDimension(R.styleable.Lighting_lightZ, 0);
            mLightRadius = a.getDimension(R.styleable.Lighting_lightRadius, 0);
            final float ambientShadowAlpha = a.getFloat(R.styleable.Lighting_ambientShadowAlpha, 0);
            final float spotShadowAlpha = a.getFloat(R.styleable.Lighting_spotShadowAlpha, 0);
            a.recycle();
            setLightSourceAlpha(ambientShadowAlpha, spotShadowAlpha);
        }

        /**
         * Set the light center.
         */
        public void setLightCenter(final Display display,
                final int windowLeft, final int windowTop) {
            // Adjust light position for window offsets.
            DisplayMetrics displayMetrics = new DisplayMetrics();
            display.getRealMetrics(displayMetrics);
            final float lightX = displayMetrics.widthPixels / 2f - windowLeft;
            final float lightY = mLightY - windowTop;
            // To prevent shadow distortion on larger screens, scale the z position of the light
            // source relative to the smallest screen dimension.
            final float zRatio = Math.min(displayMetrics.widthPixels, displayMetrics.heightPixels)
                    / (450f * displayMetrics.density);
            final float zWeightedAdjustment = (zRatio + 2) / 3f;
            final float lightZ = mLightZ * zWeightedAdjustment;

            setLightSourceGeometry(lightX, lightY, lightZ, mLightRadius);
        }

        public RenderNode getRootNode() {
            return mRootNode;
        }

        /**
         * Draw the surface.
         */
        public void draw(final FrameDrawingCallback callback) {
            final long vsync = AnimationUtils.currentAnimationTimeMillis() * 1000000L;
            if (callback != null) {
                setFrameCallback(callback);
            }
            createRenderRequest()
                    .setVsyncTime(vsync)
                    .syncAndDraw();
        }
    }

    public static Bitmap createHardwareBitmap(RenderNode node, int width, int height) {
        Bitmap bitmap = HardwareRenderer.createHardwareBitmap(node, width, height);
        if (bitmap != null) return bitmap;

        // AOSP's HardwareRenderer JNI implements RenderNode snapshots through
        // AHardwareBuffer only on Android.  The hosted build deliberately
        // returns null from that native method, but callers such as Compose's
        // GraphicsLayer.toImageBitmap() require a real Bitmap.  Preserve the
        // snapshot dimensions with a transparent software bitmap so transient
        // cross-fade overlays can complete instead of wrapping a null bitmap.
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }

    private native void nConfigureHostSurface(int width, int height);
    private native void nDestroyHostSurface();
    private static native void nNotifyHostFrameCommitted();
}