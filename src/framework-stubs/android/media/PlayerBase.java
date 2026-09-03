/* artzig stub of AOSP android.media.PlayerBase: the real base class registers every player
 * with IAudioService (AudioPlaybackConfiguration, VolumeShaper, IPlayer — frameworks/av AIDL
 * outside the include roots); the hosted AudioTrack is the PipeWire seam. Notification needs
 * only the stream-type deprecation helper. Pending the audio family (managers over hosted
 * services, phase 3). */
package android.media;

import android.util.Log;

public abstract class PlayerBase {
    /** AudioSystem.STREAM_ACCESSIBILITY (the hosted AudioManager stub does not declare it). */
    private static final int STREAM_ACCESSIBILITY = 10;

    /** AOSP PlayerBase.deprecateStreamTypeForPlayback, verbatim. */
    public static void deprecateStreamTypeForPlayback(int streamType, String className,
            String opName) throws IllegalArgumentException {
        // STREAM_ACCESSIBILITY was introduced at the same time the use of stream types
        // for audio playback was deprecated, so it is not allowed at all to use it
        if (streamType == STREAM_ACCESSIBILITY) {
            throw new IllegalArgumentException("Use of STREAM_ACCESSIBILITY is reserved for "
                    + "volume control");
        }
        Log.w(className, "Use of stream types is deprecated for operations other than "
                + "volume control");
        Log.w(className, "See the documentation of " + opName + " for what to use instead with "
                + "android.media.AudioAttributes to qualify your playback use case");
    }
}
