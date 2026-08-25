/* artzig stub: TextureView is not backed (players use SurfaceView). */
package android.view;

import android.graphics.SurfaceTexture;

public class TextureView extends View {
    public interface SurfaceTextureListener {
        void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height);
        void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height);
        boolean onSurfaceTextureDestroyed(SurfaceTexture surface);
        void onSurfaceTextureUpdated(SurfaceTexture surface);
    }
    public TextureView(android.content.Context context) { super(context); }
    public TextureView(android.content.Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
    }
    public android.graphics.Bitmap getBitmap() { return null; }
    public boolean isAvailable() { return false; }
    public SurfaceTexture getSurfaceTexture() { return null; }
    public void setSurfaceTexture(SurfaceTexture surfaceTexture) {}
    public void setSurfaceTextureListener(SurfaceTextureListener listener) {}
    public SurfaceTextureListener getSurfaceTextureListener() { return null; }
    public void setOpaque(boolean opaque) {}
    public android.graphics.Matrix getTransform(android.graphics.Matrix transform) {
        return transform != null ? transform : new android.graphics.Matrix();
    }
    public void setTransform(android.graphics.Matrix transform) {}
}
