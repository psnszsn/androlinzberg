/* artzig stub: spatial audio is not available. */
package android.media;

import java.util.concurrent.Executor;

public class Spatializer {
    public static final int SPATIALIZER_IMMERSIVE_LEVEL_NONE = 0;
    public static final int SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL = 1;

    public interface OnSpatializerStateChangedListener {
        void onSpatializerEnabledChanged(Spatializer spat, boolean enabled);
        void onSpatializerAvailableChanged(Spatializer spat, boolean available);
    }

    public boolean isEnabled() { return false; }
    public boolean isAvailable() { return false; }
    public int getImmersiveAudioLevel() { return SPATIALIZER_IMMERSIVE_LEVEL_NONE; }
    public boolean canBeSpatialized(AudioAttributes attributes, AudioFormat format) { return false; }
    public void addOnSpatializerStateChangedListener(Executor executor,
            OnSpatializerStateChangedListener listener) {}
    public void addOnSpatializerStateChangedListener(OnSpatializerStateChangedListener listener) {}
    public void removeOnSpatializerStateChangedListener(OnSpatializerStateChangedListener listener) {}
}
