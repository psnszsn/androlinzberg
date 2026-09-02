/* artzig stub of AOSP android.app.WallpaperManager: no wallpaper service on this
 * host; ContextImpl's deprecated wallpaper shortcuts link against these. */
package android.app;
public class WallpaperManager {
    public static WallpaperManager getInstance(android.content.Context context) { return new WallpaperManager(); }
    public android.graphics.drawable.Drawable getDrawable() { return null; }
    public android.graphics.drawable.Drawable peekDrawable() { return null; }
    public int getDesiredMinimumWidth() { return 0; }
    public int getDesiredMinimumHeight() { return 0; }
    public void setBitmap(android.graphics.Bitmap bitmap) throws java.io.IOException {}
    public void setStream(java.io.InputStream bitmapData) throws java.io.IOException {}
    public void clear() throws java.io.IOException {}
}
