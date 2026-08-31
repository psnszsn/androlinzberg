/* artzig stub: no device policy; every drawable resolves to the caller's
 * default supplier. */
package android.app.admin;
import android.graphics.drawable.Drawable;
import java.util.function.Supplier;
public class DevicePolicyResourcesManager {
    public Drawable getDrawable(String drawableId, String drawableStyle,
            Supplier<Drawable> defaultDrawableLoader) {
        return defaultDrawableLoader.get();
    }
    public Drawable getDrawableForDensity(String drawableId, String drawableStyle,
            int density, Supplier<Drawable> defaultDrawableLoader) {
        return defaultDrawableLoader.get();
    }
    public String getString(String stringId, Supplier<String> defaultStringLoader) {
        return defaultStringLoader.get();
    }
}
