/* artzig stub: focus is always granted. */
package android.media;
public final class AudioFocusRequest {
    private AudioFocusRequest() {}
    public AudioAttributes getAudioAttributes() { return new AudioAttributes.Builder().build(); }
    public int getFocusGain() { return AudioManager.AUDIOFOCUS_GAIN; }
    public static final class Builder {
        public Builder(int focusGain) {}
        public Builder(AudioFocusRequest requestToCopy) {}
        public Builder setAudioAttributes(AudioAttributes attributes) { return this; }
        public Builder setOnAudioFocusChangeListener(AudioManager.OnAudioFocusChangeListener listener) { return this; }
        public Builder setOnAudioFocusChangeListener(AudioManager.OnAudioFocusChangeListener listener,
                android.os.Handler handler) { return this; }
        public Builder setAcceptsDelayedFocusGain(boolean acceptsDelayedFocusGain) { return this; }
        public Builder setWillPauseWhenDucked(boolean pauseOnDuck) { return this; }
        public Builder setForceDucking(boolean forceDucking) { return this; }
        public AudioFocusRequest build() {
            try {
                java.lang.reflect.Constructor<AudioFocusRequest> c =
                        AudioFocusRequest.class.getDeclaredConstructor();
                c.setAccessible(true);
                return c.newInstance();
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }
}
