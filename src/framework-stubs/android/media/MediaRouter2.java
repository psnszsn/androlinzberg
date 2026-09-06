/* artzig stub: one fixed system routing controller for desktop audio. */
package android.media;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

public final class MediaRouter2 {
    private static final MediaRouter2 INSTANCE = new MediaRouter2();
    private final MediaRoute2Info mBuiltInRoute = dev.artzig.system.HostedMediaRoutes.builtInAudio;
    private final RoutingController mSystemController = new RoutingController(this);

    private MediaRouter2() {}

    public static MediaRouter2 getInstance(android.content.Context context) { return INSTANCE; }
    public void registerRouteCallback(Executor executor, RouteCallback callback,
            RouteDiscoveryPreference preference) {}
    public void unregisterRouteCallback(RouteCallback callback) {}
    public void registerControllerCallback(Executor executor, ControllerCallback callback) {}
    public void unregisterControllerCallback(ControllerCallback callback) {}
    public List<MediaRoute2Info> getRoutes() { return Collections.singletonList(mBuiltInRoute); }
    public RoutingController getSystemController() { return mSystemController; }
    public List<RoutingController> getControllers() {
        return Collections.singletonList(mSystemController);
    }

    public abstract static class RouteCallback {
        public void onRoutesAdded(List<MediaRoute2Info> routes) {}
        public void onRoutesRemoved(List<MediaRoute2Info> routes) {}
        public void onRoutesChanged(List<MediaRoute2Info> routes) {}
        public void onRoutesUpdated(List<MediaRoute2Info> routes) {}
    }

    public abstract static class ControllerCallback {
        public void onControllerUpdated(RoutingController controller) {}
    }

    public final class RoutingController {
        RoutingController(MediaRouter2 router) {}
        public String getId() { return "artzig-system-controller"; }
        public android.os.Bundle getControlHints() { return android.os.Bundle.EMPTY; }
        public List<MediaRoute2Info> getSelectedRoutes() {
            return Collections.singletonList(mBuiltInRoute);
        }
        public List<MediaRoute2Info> getSelectableRoutes() { return Collections.emptyList(); }
        public List<MediaRoute2Info> getDeselectableRoutes() { return Collections.emptyList(); }
        public List<MediaRoute2Info> getTransferableRoutes() { return Collections.emptyList(); }
        public boolean wasTransferInitiatedBySelf() { return true; }
        public int getVolumeHandling() { return MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE; }
        public int getVolumeMax() { return 100; }
        public int getVolume() { return 100; }
        public boolean isReleased() { return false; }
        public void selectRoute(MediaRoute2Info route) {}
        public void deselectRoute(MediaRoute2Info route) {}
        public void setVolume(int volume) {}
        public void release() {}
    }
}
