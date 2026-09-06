/* artzig stub: no virtual devices on this host. The reused ClipboardService
 * null-checks its VirtualDeviceManagerInternal and takes the default-device
 * path everywhere. */
package android.companion.virtual;
public class VirtualDeviceManager {
    public static final String PERSISTENT_DEVICE_ID_DEFAULT =
            "default:" + android.content.Context.DEVICE_ID_DEFAULT;
    public static final String ACTION_VIRTUAL_DEVICE_REMOVED =
            "android.companion.virtual.action.VIRTUAL_DEVICE_REMOVED";
    public static final String EXTRA_VIRTUAL_DEVICE_ID =
            "android.companion.virtual.extra.VIRTUAL_DEVICE_ID";
    public interface VirtualDeviceListener {
        default void onVirtualDeviceCreated(int deviceId) {}
        default void onVirtualDeviceClosed(int deviceId) {}
    }
    /** AOSP getAudioPlaybackSessionId: no virtual-device service is hosted. */
    public int getAudioPlaybackSessionId(int deviceId) {
        return android.media.AudioManager.AUDIO_SESSION_ID_GENERATE;
    }
    public boolean isValidVirtualDeviceId(int deviceId) { return false; }
    public int getDevicePolicy(int deviceId, int policyType) { return 0; }
    public int getDeviceIdForDisplayId(int displayId) { return 0; }
    public void registerVirtualDeviceListener(java.util.concurrent.Executor executor,
            VirtualDeviceListener listener) {}
    public void unregisterVirtualDeviceListener(VirtualDeviceListener listener) {}
// Pinned VirtualDeviceManager.getAudioRecordingSessionId: absent service returns SESSION_ID_GENERATE.
    public int getAudioRecordingSessionId(int deviceId) { return 0; }
}
