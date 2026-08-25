/* artzig stub (auto-generated placeholder; members added on demand). */
package android.media;
public class AudioManager {
    public Spatializer getSpatializer() { return new Spatializer(); }
    private static int sNextAudioSession = 1000;
    public int generateAudioSessionId() { synchronized (AudioManager.class) { return sNextAudioSession++; } }
    public static final int AUDIOFOCUS_GAIN = 1;
    public static final int AUDIOFOCUS_GAIN_TRANSIENT = 2;
    public static final int AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK = 3;
    public static final int AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE = 4;
    public static final int AUDIOFOCUS_LOSS = -1;
    public static final int AUDIOFOCUS_LOSS_TRANSIENT = -2;
    public static final int AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK = -3;
    public static final int AUDIOFOCUS_REQUEST_FAILED = 0;
    public static final int AUDIOFOCUS_REQUEST_GRANTED = 1;
    public static final int AUDIOFOCUS_REQUEST_DELAYED = 2;
    public static final int ADJUST_LOWER = -1, ADJUST_SAME = 0, ADJUST_RAISE = 1;
    public static final int FLAG_SHOW_UI = 1;
    public static final String PROPERTY_OUTPUT_SAMPLE_RATE = "android.media.property.OUTPUT_SAMPLE_RATE";
    public static final String PROPERTY_OUTPUT_FRAMES_PER_BUFFER = "android.media.property.OUTPUT_FRAMES_PER_BUFFER";
    public interface OnAudioFocusChangeListener { void onAudioFocusChange(int focusChange); }
    public int requestAudioFocus(OnAudioFocusChangeListener l, int streamType, int durationHint) {
        return AUDIOFOCUS_REQUEST_GRANTED;
    }
    public int requestAudioFocus(AudioFocusRequest focusRequest) { return AUDIOFOCUS_REQUEST_GRANTED; }
    public int abandonAudioFocus(OnAudioFocusChangeListener l) { return AUDIOFOCUS_REQUEST_GRANTED; }
    public int abandonAudioFocusRequest(AudioFocusRequest focusRequest) { return AUDIOFOCUS_REQUEST_GRANTED; }
    public int getStreamVolume(int streamType) { return 10; }
    public int getStreamMaxVolume(int streamType) { return 15; }
    public int getStreamMinVolume(int streamType) { return 0; }
    public void setStreamVolume(int streamType, int index, int flags) {}
    public void adjustStreamVolume(int streamType, int direction, int flags) {}
    public boolean isStreamMute(int streamType) { return false; }
    public boolean isMusicActive() { return false; }
    public boolean isSpeakerphoneOn() { return true; }
    public boolean isBluetoothA2dpOn() { return false; }
    public boolean isWiredHeadsetOn() { return false; }
    public boolean isBluetoothScoAvailableOffCall() { return false; }
    public String getProperty(String key) {
        if (PROPERTY_OUTPUT_SAMPLE_RATE.equals(key)) return "48000";
        if (PROPERTY_OUTPUT_FRAMES_PER_BUFFER.equals(key)) return "512";
        return null;
    }
    public static final int GET_DEVICES_OUTPUTS = 2;
    public static final int GET_DEVICES_INPUTS = 1;
    public AudioDeviceInfo[] getDevices(int flags) {
        return (flags & GET_DEVICES_OUTPUTS) != 0
                ? new AudioDeviceInfo[] { new AudioDeviceInfo() } : new AudioDeviceInfo[0];
    }
    public void registerAudioDeviceCallback(AudioDeviceCallback callback, android.os.Handler handler) {}
    public void unregisterAudioDeviceCallback(AudioDeviceCallback callback) {}
    public static final int FX_KEY_CLICK = 0, FX_FOCUS_NAVIGATION_UP = 1, FX_FOCUS_NAVIGATION_DOWN = 2,
            FX_FOCUS_NAVIGATION_LEFT = 3, FX_FOCUS_NAVIGATION_RIGHT = 4, FX_KEYPRESS_STANDARD = 5,
            FX_KEYPRESS_SPACEBAR = 6, FX_KEYPRESS_DELETE = 7, FX_KEYPRESS_RETURN = 8,
            FX_KEYPRESS_INVALID = 9, FX_BACK = 10;
    public static final int NUM_NAVIGATION_REPEAT_SOUND_EFFECTS = 3;
    public static int getNavigationRepeatSoundEffectId(int n) { return -1; }
    public static int getNthNavigationRepeatSoundEffect(int n) { return -1; }
    public void playSoundEffect(int effectType) {}
    public void playSoundEffect(int effectType, float volume) {}
    public static final int RINGER_MODE_SILENT = 0, RINGER_MODE_VIBRATE = 1, RINGER_MODE_NORMAL = 2;
    public static final int USE_DEFAULT_STREAM_TYPE = Integer.MIN_VALUE;
    public static final int MODE_NORMAL = 0, MODE_RINGTONE = 1, MODE_IN_CALL = 2, MODE_IN_COMMUNICATION = 3;
    public static final int STREAM_MUSIC = 3, STREAM_RING = 2, STREAM_SYSTEM = 1;
    public int getMode() { return MODE_NORMAL; }
    public void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags) {}
    public void handleKeyDown(android.view.KeyEvent event, int stream) {}
    public void handleKeyUp(android.view.KeyEvent event, int stream) {}
    public boolean isVolumeFixed() { return false; }
    public void dispatchMediaKeyEvent(android.view.KeyEvent keyEvent) {}
    public void preDispatchKeyEvent(android.view.KeyEvent event, int stream) {}
    public interface OnModeChangedListener { void onModeChanged(int mode); }
    public void addOnModeChangedListener(java.util.concurrent.Executor executor, OnModeChangedListener listener) {}
    public void removeOnModeChangedListener(OnModeChangedListener listener) {}
    public int getRingerMode() { return RINGER_MODE_NORMAL; }
}
