/* artzig shim: playback speed/pitch (speed change itself is phase-2 work). */
package android.media;
public final class PlaybackParams {
    public static final int AUDIO_FALLBACK_MODE_DEFAULT = 0;
    public static final int AUDIO_FALLBACK_MODE_MUTE = 1;
    public static final int AUDIO_FALLBACK_MODE_FAIL = 2;
    private float mSpeed = 1f, mPitch = 1f;
    private int mFallback = AUDIO_FALLBACK_MODE_DEFAULT;
    public PlaybackParams() {}
    public PlaybackParams allowDefaults() { return this; }
    public PlaybackParams setSpeed(float speed) { mSpeed = speed; return this; }
    public float getSpeed() { return mSpeed; }
    public PlaybackParams setPitch(float pitch) { mPitch = pitch; return this; }
    public float getPitch() { return mPitch; }
    public PlaybackParams setAudioFallbackMode(int mode) { mFallback = mode; return this; }
    public int getAudioFallbackMode() { return mFallback; }
}
