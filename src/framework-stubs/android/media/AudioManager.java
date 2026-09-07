/* Hosted AudioManager adaptation. The port cache, device conversion, recording callback,
 * stream classification and policy registration below follow pinned AOSP AudioManager.
 * The full class still reaches MediaExtractor/CAS and the unavailable legacy media services;
 * audio formats and device values now use stock sources with the host boundary in JNI. */
package android.media;
import android.annotation.*;
import android.content.Context;
import android.os.*;
import android.util.*;
import android.text.TextUtils;
import android.media.audiopolicy.*;
import android.media.projection.MediaProjection;
import java.util.*;
public class AudioManager {
    // Pinned AudioManager broadcast names used by the stock legacy router.
    public static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";
    public static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    public static final String EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    public static final String EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE";

    /** Pinned AudioSystem.AUDIO_SESSION_ALLOCATE value. */
    public static final int AUDIO_SESSION_ID_GENERATE = 0;
    /** AOSP IntDef markers for the volume flag parameters. */
    public @interface SystemVolumeFlags {}
    public @interface Flags {}

    // Pinned AudioManager annotation for virtual-device sound requests.
    @IntDef(prefix = { "FX_" }, value = {
            FX_KEY_CLICK,
            FX_FOCUS_NAVIGATION_UP,
            FX_FOCUS_NAVIGATION_DOWN,
            FX_FOCUS_NAVIGATION_LEFT,
            FX_FOCUS_NAVIGATION_RIGHT,
            FX_KEYPRESS_STANDARD,
            FX_KEYPRESS_SPACEBAR,
            FX_KEYPRESS_DELETE,
            FX_KEYPRESS_RETURN,
            FX_KEYPRESS_INVALID,
            FX_BACK
    })
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface SystemSoundEffect {}

    public static final String ACTION_HEADSET_PLUG = "android.intent.action.HEADSET_PLUG";
    public static final int PLAYBACK_OFFLOAD_NOT_SUPPORTED = 0;
    public static final int PLAYBACK_OFFLOAD_SUPPORTED = 1;
    public static final int PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED = 2;

    public Spatializer getSpatializer() { return new Spatializer(this); }
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
    // Pinned AudioManager focus flags consumed by stock AudioFocusRequest.
    public static final int AUDIOFOCUS_FLAG_DELAY_OK = 1 << 0;
    public static final int AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS = 1 << 1;
    public static final int AUDIOFOCUS_FLAG_LOCK = 1 << 2;
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
        return getDevicesStatic(flags);
    }
    public void registerAudioDeviceCallback(AudioDeviceCallback callback, android.os.Handler handler) {}
    public void unregisterAudioDeviceCallback(AudioDeviceCallback callback) {}

    /**
     * Hosted adaptation of AOSP AudioManager.getAudioDevicesForAttributes. Android obtains the
     * routed devices from AudioService; the host has no AudioService and exposes its single
     * PipeWire-backed output as the routed device instead.
     */
    public java.util.List<AudioDeviceInfo> getAudioDevicesForAttributes(
            AudioAttributes attributes) {
        java.util.Objects.requireNonNull(attributes);
        AudioDeviceInfo[] outputs = getDevices(GET_DEVICES_OUTPUTS);
        if (outputs.length == 0) return java.util.Collections.emptyList();
        return java.util.Collections.unmodifiableList(java.util.Arrays.asList(outputs));
    }

    /**
     * AOSP AudioManager.getDirectProfilesForAttributes delegates to AudioSystem. Artzig's host
     * audio seam accepts decoded PCM and provides no hardware/direct compressed-audio path.
     */
    public java.util.List getDirectProfilesForAttributes(AudioAttributes attributes) {
        java.util.Objects.requireNonNull(attributes);
        return java.util.Collections.emptyList();
    }

    /** Follow AOSP's offload query contract; the host has no hardware offload path. */
    public static boolean isOffloadedPlaybackSupported(
            AudioFormat format, AudioAttributes attributes) {
        if (format == null) throw new NullPointerException("Illegal null AudioFormat");
        if (attributes == null) throw new NullPointerException("Illegal null AudioAttributes");
        return false;
    }

    /** Follow AOSP's offload query contract; the host has no hardware offload path. */
    public static int getPlaybackOffloadSupport(
            AudioFormat format, AudioAttributes attributes) {
        if (format == null) throw new NullPointerException("Illegal null AudioFormat");
        if (attributes == null) throw new NullPointerException("Illegal null AudioAttributes");
        return PLAYBACK_OFFLOAD_NOT_SUPPORTED;
    }

    /** The host does not expose vendor AudioSystem key/value parameters. */
    public String getParameters(String keys) {
        return "";
    }
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
    public boolean isVolumeFixed() { return true; }
    public void dispatchMediaKeyEvent(android.view.KeyEvent keyEvent) {}
    public void preDispatchKeyEvent(android.view.KeyEvent event, int stream) {}
    public interface OnModeChangedListener { void onModeChanged(int mode); }
    public void addOnModeChangedListener(java.util.concurrent.Executor executor, OnModeChangedListener listener) {}
    public void removeOnModeChangedListener(OnModeChangedListener listener) {}
    public int getRingerMode() { return RINGER_MODE_NORMAL; }
    /** Broadcast actions BroadcastStickyCache keys on (AOSP values). */
    public static final String ACTION_HDMI_AUDIO_PLUG = "android.media.action.HDMI_AUDIO_PLUG";
    public static final String ACTION_SCO_AUDIO_STATE_CHANGED = "android.media.SCO_AUDIO_STATE_CHANGED";
    public static final String ACTION_SCO_AUDIO_STATE_UPDATED = "android.media.ACTION_SCO_AUDIO_STATE_UPDATED";
    public static final String INTERNAL_RINGER_MODE_CHANGED_ACTION = "android.media.INTERNAL_RINGER_MODE_CHANGED_ACTION";
    public static final String RINGER_MODE_CHANGED_ACTION = "android.media.RINGER_MODE_CHANGED";
    public static boolean isInputDevice(int device)
    {
        return AudioSystem.isInputDevice(device);
    }
    public static boolean isPublicStreamType(int streamType) {
        switch (streamType) {
            case STREAM_VOICE_CALL:
            case STREAM_SYSTEM:
            case STREAM_RING:
            case STREAM_MUSIC:
            case STREAM_ALARM:
            case STREAM_NOTIFICATION:
            case STREAM_DTMF:
            case STREAM_ACCESSIBILITY:
                return true;
            default:
                return false;
        }
    }
    static IAudioService getService()
    {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService(Context.AUDIO_SERVICE);
        sService = IAudioService.Stub.asInterface(b);
        return sService;
    }
    // Verbatim pinned AudioManager public policy entry points.
    public int registerAudioPolicy(@NonNull AudioPolicy policy) {
        return registerAudioPolicyStatic(policy);
    }

    public void unregisterAudioPolicy(@NonNull AudioPolicy policy) {
        com.android.internal.util.Preconditions.checkNotNull(policy, "Illegal null AudioPolicy argument");
        final IAudioService service = getService();
        try {
            policy.invalidateCaptorsAndInjectors();
            service.unregisterAudioPolicy(policy.cb());
            policy.reset();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    static int registerAudioPolicyStatic(@NonNull AudioPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("Illegal null AudioPolicy argument");
        }
        final IAudioService service = getService();
        try {
            MediaProjection projection = policy.getMediaProjection();
            String regId = service.registerAudioPolicy(policy.getConfig(), policy.cb(),
                    policy.hasFocusListener(), policy.isFocusPolicy(), policy.isTestFocusPolicy(),
                    policy.isVolumeController(),
                    projection == null ? null : projection.getProjection(),
                    policy.getAttributionSource());
            if (regId == null) {
                return ERROR;
            } else {
                policy.setRegistration(regId);
            }
            // successful registration
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return SUCCESS;
    }
    static void unregisterAudioPolicyAsyncStatic(@NonNull AudioPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("Illegal null AudioPolicy argument");
        }
        final IAudioService service = getService();
        try {
            service.unregisterAudioPolicyAsync(policy.cb());
            policy.reset();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
    public static int listAudioPatches(ArrayList<AudioPatch> patches) {
        return updateAudioPortCache(null, patches, null);
    }
    static int resetAudioPortGeneration() {
        int generation;
        synchronized (sAudioPortGenerationLock) {
            generation = sAudioPortGeneration;
            sAudioPortGeneration = AUDIOPORT_GENERATION_INIT;
        }
        return generation;
    }
    static int updateAudioPortCache(ArrayList<AudioPort> ports, ArrayList<AudioPatch> patches,
                                    ArrayList<AudioPort> previousPorts) {
        sAudioPortEventHandler.init();
        synchronized (sAudioPortGenerationLock) {

            if (sAudioPortGeneration == AUDIOPORT_GENERATION_INIT) {
                int[] patchGeneration = new int[1];
                int[] portGeneration = new int[1];
                int status;
                ArrayList<AudioPort> newPorts = new ArrayList<AudioPort>();
                ArrayList<AudioPatch> newPatches = new ArrayList<AudioPatch>();

                do {
                    newPorts.clear();
                    status = AudioSystem.listAudioPorts(newPorts, portGeneration);
                    if (status != SUCCESS) {
                        Log.w(TAG, "updateAudioPortCache: listAudioPorts failed");
                        return status;
                    }
                    newPatches.clear();
                    status = AudioSystem.listAudioPatches(newPatches, patchGeneration);
                    if (status != SUCCESS) {
                        Log.w(TAG, "updateAudioPortCache: listAudioPatches failed");
                        return status;
                    }
                    // Loop until patch generation is the same as port generation unless audio ports
                    // and audio patches are not null.
                } while (patchGeneration[0] != portGeneration[0]
                        && (ports == null || patches == null));
                // If the patch generation doesn't equal port generation, return ERROR here in case
                // of mismatch between audio ports and audio patches.
                if (patchGeneration[0] != portGeneration[0]) {
                    return ERROR;
                }

                for (int i = 0; i < newPatches.size(); i++) {
                    for (int j = 0; j < newPatches.get(i).sources().length; j++) {
                        AudioPortConfig portCfg = updatePortConfig(newPatches.get(i).sources()[j],
                                                                   newPorts);
                        newPatches.get(i).sources()[j] = portCfg;
                    }
                    for (int j = 0; j < newPatches.get(i).sinks().length; j++) {
                        AudioPortConfig portCfg = updatePortConfig(newPatches.get(i).sinks()[j],
                                                                   newPorts);
                        newPatches.get(i).sinks()[j] = portCfg;
                    }
                }
                for (Iterator<AudioPatch> i = newPatches.iterator(); i.hasNext(); ) {
                    AudioPatch newPatch = i.next();
                    boolean hasInvalidPort = false;
                    for (AudioPortConfig portCfg : newPatch.sources()) {
                        if (portCfg == null) {
                            hasInvalidPort = true;
                            break;
                        }
                    }
                    for (AudioPortConfig portCfg : newPatch.sinks()) {
                        if (portCfg == null) {
                            hasInvalidPort = true;
                            break;
                        }
                    }
                    if (hasInvalidPort) {
                        // Temporarily remove patches with invalid ports. One who created the patch
                        // is responsible for dealing with the port change.
                        i.remove();
                    }
                }

                sPreviousAudioPortsCached = sAudioPortsCached;
                sAudioPortsCached = newPorts;
                sAudioPatchesCached = newPatches;
                sAudioPortGeneration = portGeneration[0];
            }
            if (ports != null) {
                ports.clear();
                ports.addAll(sAudioPortsCached);
            }
            if (patches != null) {
                patches.clear();
                patches.addAll(sAudioPatchesCached);
            }
            if (previousPorts != null) {
                previousPorts.clear();
                previousPorts.addAll(sPreviousAudioPortsCached);
            }
        }
        return SUCCESS;
    }
    static AudioPortConfig updatePortConfig(AudioPortConfig portCfg, ArrayList<AudioPort> ports) {
        AudioPort port = portCfg.port();
        int k;
        for (k = 0; k < ports.size(); k++) {
            // compare handles because the port returned by JNI is not of the correct
            // subclass
            if (ports.get(k).handle().equals(port.handle())) {
                port = ports.get(k);
                break;
            }
        }
        if (k == ports.size()) {
            // This can happen in case of stale audio patch referring to a removed device and is
            // handled by the caller.
            return null;
        }
        AudioGainConfig gainCfg = portCfg.gain();
        if (gainCfg != null) {
            AudioGain gain = port.gain(gainCfg.index());
            gainCfg = gain.buildConfig(gainCfg.mode(),
                                       gainCfg.channelMask(),
                                       gainCfg.values(),
                                       gainCfg.rampDurationMs());
        }
        return port.buildConfig(portCfg.samplingRate(),
                                                 portCfg.channelMask(),
                                                 portCfg.format(),
                                                 gainCfg);
    }
    public static int listAudioDevicePorts(ArrayList<AudioDevicePort> devices) {
        if (devices == null) {
            return ERROR_BAD_VALUE;
        }
        ArrayList<AudioPort> ports = new ArrayList<AudioPort>();
        int status = updateAudioPortCache(ports, null, null);
        if (status == SUCCESS) {
            filterDevicePorts(ports, devices);
        }
        return status;
    }
    private static void filterDevicePorts(ArrayList<AudioPort> ports,
                                          ArrayList<AudioDevicePort> devices) {
        devices.clear();
        for (int i = 0; i < ports.size(); i++) {
            if (ports.get(i) instanceof AudioDevicePort) {
                devices.add((AudioDevicePort)ports.get(i));
            }
        }
    }
    private static boolean checkFlags(AudioDevicePort port, int flags) {
        return port.role() == AudioPort.ROLE_SINK && (flags & GET_DEVICES_OUTPUTS) != 0 ||
               port.role() == AudioPort.ROLE_SOURCE && (flags & GET_DEVICES_INPUTS) != 0;
    }
    private static boolean checkTypes(AudioDevicePort port) {
        return AudioDeviceInfo.convertInternalDeviceToDeviceType(port.type()) !=
                    AudioDeviceInfo.TYPE_UNKNOWN;
    }
    private static AudioDeviceInfo[]
        infoListFromPortList(ArrayList<AudioDevicePort> ports, int flags) {

        // figure out how many AudioDeviceInfo we need space for...
        int numRecs = 0;
        for (AudioDevicePort port : ports) {
            if (checkTypes(port) && checkFlags(port, flags)) {
                numRecs++;
            }
        }

        // Now load them up...
        AudioDeviceInfo[] deviceList = new AudioDeviceInfo[numRecs];
        int slot = 0;
        for (AudioDevicePort port : ports) {
            if (checkTypes(port) && checkFlags(port, flags)) {
                deviceList[slot++] = new AudioDeviceInfo(port);
            }
        }

        return deviceList;
    }
    public static AudioDeviceInfo[] getDevicesStatic(int flags) {
        ArrayList<AudioDevicePort> ports = new ArrayList<AudioDevicePort>();
        int status = AudioManager.listAudioDevicePorts(ports);
        if (status != AudioManager.SUCCESS) {
            // fail and bail!
            return new AudioDeviceInfo[0];  // Always return an array.
        }

        return infoListFromPortList(ports, flags);
    }
    public static AudioDeviceInfo getDeviceForPortId(int portId, int flags) {
        if (portId == 0) {
            return null;
        }
        AudioDeviceInfo[] devices = getDevicesStatic(flags);
        for (AudioDeviceInfo device : devices) {
            if (device.getId() == portId) {
                return device;
            }
        }
        return null;
    }
    public static void setPortIdForMicrophones(ArrayList<MicrophoneInfo> microphones) {
        AudioDeviceInfo[] devices = getDevicesStatic(AudioManager.GET_DEVICES_INPUTS);
        for (int i = microphones.size() - 1; i >= 0; i--) {
            boolean foundPortId = false;
            for (AudioDeviceInfo device : devices) {
                if (device.getPort().type() == microphones.get(i).getInternalDeviceType()
                        && TextUtils.equals(device.getAddress(), microphones.get(i).getAddress())) {
                    microphones.get(i).setId(device.getId());
                    foundPortId = true;
                    break;
                }
            }
            if (!foundPortId) {
                Log.i(TAG, "Failed to find port id for device with type:"
                        + microphones.get(i).getType() + " address:"
                        + microphones.get(i).getAddress());
                microphones.remove(i);
            }
        }
    }
    public static MicrophoneInfo microphoneInfoFromAudioDeviceInfo(AudioDeviceInfo deviceInfo) {
        @AudioDeviceInfo.AudioDeviceType int deviceType = deviceInfo.getType();
        int micLocation = (deviceType == AudioDeviceInfo.TYPE_BUILTIN_MIC
                || deviceType == AudioDeviceInfo.TYPE_TELEPHONY) ? MicrophoneInfo.LOCATION_MAINBODY
                : deviceType == AudioDeviceInfo.TYPE_UNKNOWN ? MicrophoneInfo.LOCATION_UNKNOWN
                        : MicrophoneInfo.LOCATION_PERIPHERAL;
        MicrophoneInfo microphone = new MicrophoneInfo(
                deviceInfo.getPort().name() + deviceInfo.getId(),
                deviceInfo.getPort().type(), deviceInfo.getAddress(), micLocation,
                MicrophoneInfo.GROUP_UNKNOWN, MicrophoneInfo.INDEX_IN_THE_GROUP_UNKNOWN,
                MicrophoneInfo.POSITION_UNKNOWN, MicrophoneInfo.ORIENTATION_UNKNOWN,
                new ArrayList<Pair<Float, Float>>(), new ArrayList<Pair<Integer, Integer>>(),
                MicrophoneInfo.SENSITIVITY_UNKNOWN, MicrophoneInfo.SPL_UNKNOWN,
                MicrophoneInfo.SPL_UNKNOWN, MicrophoneInfo.DIRECTIONALITY_UNKNOWN);
        microphone.setId(deviceInfo.getId());
        return microphone;
    }
    public static abstract class AudioRecordingCallback {
        /**
         * Called whenever the device recording configuration has changed.
         * @param configs list containing the results of
         *      {@link AudioManager#getActiveRecordingConfigurations()}.
         */
        public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {}
    }
    public interface OnAudioPortUpdateListener {
        /**
         * Callback method called upon audio port list update.
         * @param portList the updated list of audio ports
         */
        public void onAudioPortListUpdate(AudioPort[] portList);

        /**
         * Callback method called upon audio patch list update.
         * @param patchList the updated list of audio patches
         */
        public void onAudioPatchListUpdate(AudioPatch[] patchList);

        /**
         * Callback method called when the mediaserver dies
         */
        public void onServiceDied();
    }
    public static final int STREAM_ACCESSIBILITY = AudioSystem.STREAM_ACCESSIBILITY;
    public static final int STREAM_VOICE_CALL = AudioSystem.STREAM_VOICE_CALL;
    public static final int STREAM_ALARM = AudioSystem.STREAM_ALARM;
    public static final int STREAM_NOTIFICATION = AudioSystem.STREAM_NOTIFICATION;
    public static final int STREAM_DTMF = AudioSystem.STREAM_DTMF;
    public static final int SUCCESS = AudioSystem.SUCCESS;
    public static final int ERROR = AudioSystem.ERROR;
    public static final int ERROR_INVALID_OPERATION = AudioSystem.INVALID_OPERATION;
    public static final int ERROR_BAD_VALUE = AudioSystem.BAD_VALUE;
    public static final int DEVICE_OUT_SPEAKER = AudioSystem.DEVICE_OUT_SPEAKER;
    public static final int CALL_REDIRECT_NONE = 0;
    public static final int CALL_REDIRECT_PSTN = 1;
    public static final int CALL_REDIRECT_VOIP = 2;
    public @interface PublicStreamTypes {}
    public @interface VolumeAdjustment {}
    public @interface CallRedirectionMode {}
    private static final String TAG = "AudioManager";
    private static IAudioService sService;
    private static final AudioPortEventHandler sAudioPortEventHandler = new AudioPortEventHandler();
    private static final int AUDIOPORT_GENERATION_INIT = 0;
    private static Object sAudioPortGenerationLock = new Object();
    private static int sAudioPortGeneration = AUDIOPORT_GENERATION_INIT;
    private static ArrayList<AudioPort> sAudioPortsCached = new ArrayList<AudioPort>();
    private static ArrayList<AudioPort> sPreviousAudioPortsCached = new ArrayList<AudioPort>();
    private static ArrayList<AudioPatch> sAudioPatchesCached = new ArrayList<AudioPatch>();

}
