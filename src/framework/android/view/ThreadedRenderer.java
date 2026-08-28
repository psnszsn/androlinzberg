package android.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.HardwareRenderer;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;

/** Host HWUI renderer backed by Ganesh/OpenGL and a direct Wayland EGL surface. */
public final class ThreadedRenderer extends HardwareRenderer {
    private static ThreadedRenderer sInstance;

    private int mWidth;
    private int mHeight;
    private final RenderNode mScaledRoot = new RenderNode("artzig-scaled-root");

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
        // The Wayland host publishes synchronized child surfaces with a parent
        // commit immediately after this method returns. HardwareRenderer's
        // default sync may release the UI thread before RenderThread submits
        // the EGL buffer, so use its AOSP wait-for-present contract to make
        // that parent/child ordering deterministic.
        int result = createRenderRequest()
                .setVsyncTime(System.nanoTime())
                .setWaitForPresent(true)
                .syncAndDraw();
        if ((result & SYNC_LOST_SURFACE_REWARD_IF_FOUND) != 0) {
            throw new IllegalStateException("HWUI lost the host render surface: " + result);
        }
    }

    @Override
    public synchronized void destroy() {
        nDestroyHostSurface();
        super.destroy();
    }

    public void buildLayer(RenderNode node) {}

    public static class SimpleRenderer {
        public SimpleRenderer(Context context, String name, Surface surface) {}
        public android.graphics.RecordingCanvas beginRecording(int width, int height) {
            throw android.util.ArtzigTestDiagnostics.unsupported(
                    "android.view.ThreadedRenderer.SimpleRenderer", "beginRecording",
                    "SimpleRenderer is not hosted");
        }
        public void endRecording() {}
        public void renderFrame(Choreographer choreographer) {}
        public void draw(HardwareRenderer.FrameDrawingCallback callback) {}
        public void destroy() {}
        public HardwareRenderer.FrameRenderRequest createRenderRequest() {
            throw android.util.ArtzigTestDiagnostics.unsupported(
                    "android.view.ThreadedRenderer.SimpleRenderer", "createRenderRequest",
                    "SimpleRenderer is not hosted");
        }
        public void setLightSourceAlpha(float a, float b) {}
        public void setLightSourceGeometry(float x, float y, float z, float radius) {}
        public void setSurface(Surface surface) {}
        public void setLightCenter(Display display, int windowLeft, int windowTop) {}
        public RenderNode getRootNode() {
            throw android.util.ArtzigTestDiagnostics.unsupported(
                    "android.view.ThreadedRenderer.SimpleRenderer", "getRootNode",
                    "SimpleRenderer is not hosted");
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
}
