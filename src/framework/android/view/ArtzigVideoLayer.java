/* artzig: bridge from Surface/SurfaceView to the Wayland subsurface video
 * layers (natives registered by the Zig launcher). */
package android.view;

import java.nio.ByteBuffer;
import java.util.WeakHashMap;

public final class ArtzigVideoLayer {
    private static final class Layer {
        final int id;
        int width;
        int height;

        Layer(int id) { this.id = id; }
    }

    private static final WeakHashMap<Surface, Layer> sBySurface = new WeakHashMap<>();

    private ArtzigVideoLayer() {}

    private static native int nativeCreate();
    private static native void nativeSetGeometry(int id,
            int x, int y, int w, int h,
            int visibleX, int visibleY, int visibleW, int visibleH);
    private static native void nativePush(int id, ByteBuffer rgbx, int w, int h);
    private static native void nativePushHardware(int id, long frame);
    private static native void nativeReleaseHardware(long frame);
    private static native void nativePushSoftware(int id, long frame);
    private static native void nativeReleaseSoftware(long frame);
    private static native void nativeDestroy(int id);

    /** Creates a layer and a Surface bound to it. */
    public static Surface createSurface() {
        int id = nativeCreate();
        Surface s = new ArtzigSurface();
        if (id > 0) {
            synchronized (sBySurface) { sBySurface.put(s, new Layer(id)); }
        }
        return s;
    }

    public static int idFor(Surface s) {
        if (s == null) return 0;
        synchronized (sBySurface) {
            Layer layer = sBySurface.get(s);
            return layer != null ? layer.id : 0;
        }
    }

    /** Current full layer size, used to avoid converting hidden source pixels. */
    public static int targetWidth(Surface s) {
        synchronized (sBySurface) {
            Layer layer = sBySurface.get(s);
            return layer != null ? layer.width : 0;
        }
    }

    public static int targetHeight(Surface s) {
        synchronized (sBySurface) {
            Layer layer = sBySurface.get(s);
            return layer != null ? layer.height : 0;
        }
    }

    public static void setGeometry(Surface s,
            int x, int y, int w, int h,
            int visibleX, int visibleY, int visibleW, int visibleH) {
        int id = 0;
        synchronized (sBySurface) {
            Layer layer = sBySurface.get(s);
            if (layer != null) {
                id = layer.id;
                layer.width = Math.max(0, w);
                layer.height = Math.max(0, h);
            }
        }
        if (id > 0) {
            nativeSetGeometry(id, x, y, w, h,
                    visibleX, visibleY, visibleW, visibleH);
        }
    }

    public static void setRect(Surface s, int x, int y, int w, int h) {
        setGeometry(s, x, y, w, h, x, y, w, h);
    }

    public static void push(Surface s, ByteBuffer rgbx, int w, int h) {
        int id = idFor(s);
        if (id > 0) nativePush(id, rgbx, w, h);
    }

    /** Transfers ownership of a decoded DMA-BUF frame to the video layer. */
    public static void pushHardware(Surface s, long frame) {
        int id = idFor(s);
        if (id > 0) nativePushHardware(id, frame);
        else nativeReleaseHardware(frame);
    }

    public static void releaseHardware(long frame) {
        if (frame != 0) nativeReleaseHardware(frame);
    }

    /** Transfers ownership of a decoded CPU frame without another JNI copy. */
    public static void pushSoftware(Surface s, long frame) {
        int id = idFor(s);
        if (id > 0) nativePushSoftware(id, frame);
        else nativeReleaseSoftware(frame);
    }

    public static void releaseSoftware(long frame) {
        if (frame != 0) nativeReleaseSoftware(frame);
    }

    public static void destroy(Surface s) {
        int id = idFor(s);
        if (id > 0) {
            nativeDestroy(id);
            synchronized (sBySurface) { sBySurface.remove(s); }
        }
    }
}
