/* artzig stub (auto-generated placeholder; members added on demand). */
package android.view;
public class PixelCopy {
    public static final int SUCCESS = 0, ERROR_UNKNOWN = 1, ERROR_TIMEOUT = 2,
            ERROR_SOURCE_NO_DATA = 3, ERROR_SOURCE_INVALID = 4, ERROR_DESTINATION_INVALID = 5;
    public interface OnPixelCopyFinishedListener { void onPixelCopyFinished(int copyResult); }
    public static void request(Surface source, android.graphics.Rect srcRect,
            android.graphics.Bitmap dest, OnPixelCopyFinishedListener listener, android.os.Handler handler) {
        if (listener != null) listener.onPixelCopyFinished(ERROR_UNKNOWN);
    }
}
