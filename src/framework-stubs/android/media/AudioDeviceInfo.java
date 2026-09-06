/* artzig stub: one built-in speaker device. */
package android.media;
public final class AudioDeviceInfo {
    public static final int TYPE_UNKNOWN = 0;
    public static final int TYPE_BUILTIN_EARPIECE = 1;
    public static final int TYPE_BUILTIN_SPEAKER = 2;
    public static final int TYPE_WIRED_HEADSET = 3;
    public static final int TYPE_WIRED_HEADPHONES = 4;
    public static final int TYPE_LINE_ANALOG = 5;
    public static final int TYPE_LINE_DIGITAL = 6;
    public static final int TYPE_BLUETOOTH_SCO = 7;
    public static final int TYPE_BLUETOOTH_A2DP = 8;
    public static final int TYPE_HDMI = 9;
    public static final int TYPE_HDMI_ARC = 10;
    public static final int TYPE_USB_DEVICE = 11;
    public static final int TYPE_USB_ACCESSORY = 12;
    public static final int TYPE_DOCK = 13;
    public static final int TYPE_AUX_LINE = 19;
    public static final int TYPE_USB_HEADSET = 22;
    public static final int TYPE_HEARING_AID = 23;
    public static final int TYPE_BLE_HEADSET = 26;
    public static final int TYPE_BLE_SPEAKER = 27;
    public static final int TYPE_HDMI_EARC = 29;
    public static final int TYPE_BLE_BROADCAST = 30;
    public static final int TYPE_MULTICHANNEL_GROUP = 32;
    AudioDeviceInfo() {}
    public int getId() { return 1; }
    public int getType() { return TYPE_BUILTIN_SPEAKER; }
    public CharSequence getProductName() { return "artzig"; }
    public boolean isSink() { return true; }
    public boolean isSource() { return false; }
    public int[] getSampleRates() { return new int[] { 44100, 48000 }; }
    public int[] getChannelCounts() { return new int[] { 1, 2 }; }
    public int[] getChannelMasks() { return new int[] { AudioFormat.CHANNEL_OUT_STEREO }; }
    public int[] getEncodings() { return new int[] { AudioFormat.ENCODING_PCM_16BIT }; }
}
