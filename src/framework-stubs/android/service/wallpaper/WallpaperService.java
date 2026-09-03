/* artzig stub of AOSP android.service.wallpaper.WallpaperService: the live-wallpaper
 * service base drives its Engine through the window session (IWindowSession relayout,
 * surface transform hints, PixelCopy screenshots), a host seam this desktop runtime does
 * not provide. WallpaperManager and WallpaperInfo cite the service interface and
 * meta-data names; no live wallpaper can run here. */
package android.service.wallpaper;

public abstract class WallpaperService extends android.app.Service {
    public static final String SERVICE_INTERFACE = "android.service.wallpaper.WallpaperService";
    public static final String SERVICE_META_DATA = "android.service.wallpaper";

    /** The rendering engine a live wallpaper subclasses; never instantiated here. */
    public class Engine {
        public Engine() {}
    }

    public abstract Engine onCreateEngine();

    @Override public android.os.IBinder onBind(android.content.Intent intent) { return null; }
}
