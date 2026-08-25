package dev.artzig;

import android.graphics.Bitmap;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.hardware.HardwareBuffer;
import android.util.Log;
import android.view.ThreadedRenderer;

/** Regression probe for the host's GBM-backed Bitmap/HardwareBuffer contract. */
public final class HardwareBitmapProbe {
    private static final String TAG = "HardwareBitmapProbe";
    private static final RenderNode ROOT = new RenderNode("hardware-bitmap-probe");
    private static boolean sChecked;
    private static int sFrames;

    private HardwareBitmapProbe() {}

    public static boolean wantsFullscreen() {
        String mode = System.getenv("AZK_PROBE_FULLSCREEN");
        return "1".equals(mode) || ("toggle".equals(mode) && sFrames == 1);
    }

    public static void drawHardware(int logicalWidth, int logicalHeight,
            int bufferWidth, int bufferHeight, int scale120) {
        sFrames++;
        ROOT.setPosition(0, 0, bufferWidth, bufferHeight);
        RecordingCanvas canvas = ROOT.beginRecording(bufferWidth, bufferHeight);
        canvas.drawColor(0xff20242b);
        ROOT.endRecording();
        ThreadedRenderer.getInstance().render(ROOT, bufferWidth, bufferHeight);

        if (sChecked) return;
        final long usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE |
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT;
        if (!HardwareBuffer.isSupported(8, 8, HardwareBuffer.RGBA_8888, 1, usage)) {
            throw new AssertionError("GBM RGBA render target is unsupported");
        }
        HardwareBuffer allocated = HardwareBuffer.create(
                8, 8, HardwareBuffer.RGBA_8888, 1, usage);
        if (allocated.getWidth() != 8 || allocated.getHeight() != 8 || allocated.getId() == 0) {
            throw new AssertionError("invalid allocated HardwareBuffer");
        }
        allocated.close();
        if (!allocated.isClosed()) throw new AssertionError("HardwareBuffer close failed");

        Bitmap raster = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        Bitmap hardware = raster.copy(Bitmap.Config.HARDWARE, false);
        if (hardware == null) throw new AssertionError("hardware bitmap allocation failed");
        HardwareBuffer buffer = hardware.getHardwareBuffer();
        if (buffer == null) throw new AssertionError("hardware bitmap returned a null buffer");
        if (buffer.getFormat() != HardwareBuffer.RGBA_8888) {
            throw new AssertionError("unexpected buffer format " + buffer.getFormat());
        }
        if (buffer.getWidth() != 16 || buffer.getHeight() != 16 ||
                (buffer.getUsage() & usage) != usage || buffer.getId() == 0) {
            throw new AssertionError("hardware bitmap did not expose its GBM allocation");
        }
        buffer.close();
        sChecked = true;
        Log.i(TAG, "PASS: Config.HARDWARE -> GBM/dma-buf HardwareBuffer.RGBA_8888");
    }

    public static void motion(int action, float x, float y) {}
    public static void scroll(float x, float y, float horizontal, float vertical) {}
    public static void back() {}
    public static void key(int action, int keyCode, int unicode) {}
    public static int hover(float x, float y) { return 1000; }
}
