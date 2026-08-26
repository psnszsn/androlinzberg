/* artzig stub: the desktop's fixed built-in audio output. */
package android.media;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class MediaRoute2Info implements android.os.Parcelable {
    public static final int CONNECTION_STATE_DISCONNECTED = 0;
    public static final int CONNECTION_STATE_CONNECTING = 1;
    public static final int CONNECTION_STATE_CONNECTED = 2;
    public static final int PLAYBACK_VOLUME_FIXED = 0;
    public static final int PLAYBACK_VOLUME_VARIABLE = 1;
    public static final int SUITABILITY_STATUS_SUITABLE_FOR_DEFAULT_TRANSFER = 0;
    public static final int SUITABILITY_STATUS_SUITABLE_FOR_MANUAL_TRANSFER = 1;
    public static final int SUITABILITY_STATUS_NOT_SUITABLE_FOR_TRANSFER = 2;
    public static final int TYPE_UNKNOWN = 0;
    public static final int TYPE_BUILTIN_SPEAKER = 2;
    public static final String FEATURE_LIVE_AUDIO = "android.media.route.feature.LIVE_AUDIO";
    public static final String FEATURE_LIVE_VIDEO = "android.media.route.feature.LIVE_VIDEO";
    public static final String FEATURE_REMOTE_PLAYBACK = "android.media.route.feature.REMOTE_PLAYBACK";
    public static final String FEATURE_REMOTE_AUDIO_PLAYBACK =
            "android.media.route.feature.REMOTE_AUDIO_PLAYBACK";
    public static final String FEATURE_REMOTE_VIDEO_PLAYBACK =
            "android.media.route.feature.REMOTE_VIDEO_PLAYBACK";

    MediaRoute2Info() {}

    public String getId() { return "artzig-built-in-audio"; }
    public CharSequence getName() { return "Built-in audio"; }
    public List<String> getFeatures() { return Collections.singletonList(FEATURE_LIVE_AUDIO); }
    public int getType() { return TYPE_BUILTIN_SPEAKER; }
    public boolean isSystemRoute() { return true; }
    public android.net.Uri getIconUri() { return null; }
    public CharSequence getDescription() { return "Desktop audio output"; }
    public int getConnectionState() { return CONNECTION_STATE_CONNECTED; }
    public String getClientPackageName() { return null; }
    public int getVolumeHandling() { return PLAYBACK_VOLUME_VARIABLE; }
    public int getVolumeMax() { return 100; }
    public int getVolume() { return 100; }
    public Set<String> getDeduplicationIds() { return Collections.emptySet(); }
    public android.os.Bundle getExtras() { return android.os.Bundle.EMPTY; }
    public int getSuitabilityStatus() {
        return SUITABILITY_STATUS_SUITABLE_FOR_DEFAULT_TRANSFER;
    }
    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel dest, int flags) {}
    @Override public String toString() { return getName().toString(); }

    public static final android.os.Parcelable.Creator<MediaRoute2Info> CREATOR =
            new android.os.Parcelable.Creator<MediaRoute2Info>() {
        public MediaRoute2Info createFromParcel(android.os.Parcel in) {
            return new MediaRoute2Info();
        }
        public MediaRoute2Info[] newArray(int size) { return new MediaRoute2Info[size]; }
    };
}
