/* artzig shim: android.provider.FontsContract — downloadable fonts unsupported. */
package android.provider;
import android.graphics.Typeface;
import android.os.Handler;
public class FontsContract {
    public static Typeface getFontSync(FontRequest request) { return null; }
    public static void requestFonts(Object context, FontRequest request, Handler handler,
            Object cancellationSignal, Object callback) {}
}
