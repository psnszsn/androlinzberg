/* artzig shim: SurfaceTexture without a GL consumer (used only as a token by
 * ExoPlayer's PlaceholderSurface; frames never flow through it). */
package android.graphics;

public class SurfaceTexture {
    public interface OnFrameAvailableListener {
        void onFrameAvailable(SurfaceTexture surfaceTexture);
    }
    public static class OutOfResourcesException extends RuntimeException {
        public OutOfResourcesException() {}
        public OutOfResourcesException(String name) { super(name); }
    }
    public SurfaceTexture(int texName) {}
    public SurfaceTexture(int texName, boolean singleBufferMode) {}
    public SurfaceTexture(boolean singleBufferMode) {}
    public void setOnFrameAvailableListener(OnFrameAvailableListener listener) {}
    public void setOnFrameAvailableListener(OnFrameAvailableListener listener, android.os.Handler handler) {}
    public void setDefaultBufferSize(int width, int height) {}
    public void updateTexImage() {}
    public void detachFromGLContext() {}
    public void attachToGLContext(int texName) {}
    public void releaseTexImage() {}
    public void release() {}
    public boolean isReleased() { return false; }
    public long getTimestamp() { return 0; }
    public void getTransformMatrix(float[] mtx) {
        if (mtx != null && mtx.length >= 16) {
            java.util.Arrays.fill(mtx, 0f);
            mtx[0] = 1f; mtx[5] = 1f; mtx[10] = 1f; mtx[15] = 1f;
        }
    }
}
