/* artzig stub: audio attributes (no audio backend yet). */
package android.media;

import android.os.Parcel;
import android.os.Parcelable;

public class AudioAttributes implements Parcelable {
    public static final int CONTENT_TYPE_UNKNOWN = 0;
    public static final int CONTENT_TYPE_SPEECH = 1;
    public static final int CONTENT_TYPE_MUSIC = 2;
    public static final int CONTENT_TYPE_MOVIE = 3;
    public static final int CONTENT_TYPE_SONIFICATION = 4;
    public static final int USAGE_UNKNOWN = 0;
    public static final int USAGE_MEDIA = 1;
    public static final int USAGE_VOICE_COMMUNICATION = 2;
    public static final int USAGE_ALARM = 4;
    public static final int USAGE_NOTIFICATION = 5;
    public static final int USAGE_NOTIFICATION_RINGTONE = 6;
    public static final int USAGE_NOTIFICATION_EVENT = 10;
    public static final int USAGE_ASSISTANCE_ACCESSIBILITY = 11;
    public static final int USAGE_ASSISTANCE_NAVIGATION_GUIDANCE = 12;
    public static final int USAGE_ASSISTANCE_SONIFICATION = 13;
    public static final int USAGE_GAME = 14;
    public static final int USAGE_ASSISTANT = 16;
    public static final int USAGE_VOICE_COMMUNICATION_SIGNALLING = 3;
    public static final int USAGE_NOTIFICATION_COMMUNICATION_REQUEST = 7;
    public static final int USAGE_NOTIFICATION_COMMUNICATION_INSTANT = 8;
    public static final int USAGE_NOTIFICATION_COMMUNICATION_DELAYED = 9;
    public static final int FLAG_BYPASS_INTERRUPTION_POLICY = 1 << 6;
    public static final int FLAG_BYPASS_MUTE = 1 << 7;
    /** AOSP AttributeUsage / AttributeContentType annotations (IntDef markers). */
    public @interface AttributeUsage {}
    public @interface AttributeContentType {}
    /** AOSP getAllFlags: every flag, including the hidden ones. */
    public int getAllFlags() { return mFlags; }
    public static String usageToString(int usage) {
        switch (usage) {
            case USAGE_UNKNOWN: return "USAGE_UNKNOWN";
            case USAGE_MEDIA: return "USAGE_MEDIA";
            case USAGE_VOICE_COMMUNICATION: return "USAGE_VOICE_COMMUNICATION";
            case USAGE_VOICE_COMMUNICATION_SIGNALLING: return "USAGE_VOICE_COMMUNICATION_SIGNALLING";
            case USAGE_ALARM: return "USAGE_ALARM";
            case USAGE_NOTIFICATION: return "USAGE_NOTIFICATION";
            case USAGE_NOTIFICATION_RINGTONE: return "USAGE_NOTIFICATION_RINGTONE";
            case USAGE_NOTIFICATION_COMMUNICATION_REQUEST: return "USAGE_NOTIFICATION_COMMUNICATION_REQUEST";
            case USAGE_NOTIFICATION_COMMUNICATION_INSTANT: return "USAGE_NOTIFICATION_COMMUNICATION_INSTANT";
            case USAGE_NOTIFICATION_COMMUNICATION_DELAYED: return "USAGE_NOTIFICATION_COMMUNICATION_DELAYED";
            case USAGE_NOTIFICATION_EVENT: return "USAGE_NOTIFICATION_EVENT";
            case USAGE_ASSISTANCE_ACCESSIBILITY: return "USAGE_ASSISTANCE_ACCESSIBILITY";
            case USAGE_ASSISTANCE_NAVIGATION_GUIDANCE: return "USAGE_ASSISTANCE_NAVIGATION_GUIDANCE";
            case USAGE_ASSISTANCE_SONIFICATION: return "USAGE_ASSISTANCE_SONIFICATION";
            case USAGE_GAME: return "USAGE_GAME";
            case USAGE_ASSISTANT: return "USAGE_ASSISTANT";
            default: return "unknown usage " + usage;
        }
    }
    public static final int FLAG_AUDIBILITY_ENFORCED = 1 << 0;
    public static final int FLAG_HW_AV_SYNC = 1 << 4;
    public static final int FLAG_LOW_LATENCY = 1 << 8;
    public static final int ALLOW_CAPTURE_BY_ALL = 1;
    public static final int ALLOW_CAPTURE_BY_SYSTEM = 2;
    public static final int ALLOW_CAPTURE_BY_NONE = 3;

    private int mUsage = USAGE_UNKNOWN;
    private int mContentType = CONTENT_TYPE_UNKNOWN;
    private int mFlags = 0;

    public int getUsage() { return mUsage; }
    public int getContentType() { return mContentType; }
    public int getFlags() { return mFlags; }
    public int getVolumeControlStream() { return 3; }
    public int getAllowedCapturePolicy() { return ALLOW_CAPTURE_BY_ALL; }
    public static final int SPATIALIZATION_BEHAVIOR_AUTO = 0;
    public static final int SPATIALIZATION_BEHAVIOR_NEVER = 1;
    public int getSpatializationBehavior() { return SPATIALIZATION_BEHAVIOR_AUTO; }
    public boolean areHapticChannelsMuted() { return true; }

    public static class Builder {
        private final AudioAttributes a = new AudioAttributes();
        public Builder() {}
        public Builder(AudioAttributes src) {
            a.mUsage = src.mUsage; a.mContentType = src.mContentType; a.mFlags = src.mFlags;
        }
        public Builder setUsage(int usage) { a.mUsage = usage; return this; }
        public Builder setContentType(int contentType) { a.mContentType = contentType; return this; }
        public Builder setFlags(int flags) { a.mFlags |= flags; return this; }
        public Builder setLegacyStreamType(int streamType) { return this; }
        public Builder setAllowedCapturePolicy(int policy) { return this; }
        public Builder setSpatializationBehavior(int sb) { return this; }
        public Builder setIsContentSpatialized(boolean isSpatialized) { return this; }
        public Builder setHapticChannelsMuted(boolean muted) { return this; }
        public AudioAttributes build() { return a; }
    }

    @Override public int describeContents() { return 0; }
    /** AOSP dumpDebug writes AudioAttributesProto fields; the hosted proto sink is inert. */
    public void dumpDebug(android.util.proto.ProtoOutputStream proto, long fieldId) {}
    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mUsage); dest.writeInt(mContentType); dest.writeInt(mFlags);
    }
    public static final Parcelable.Creator<AudioAttributes> CREATOR = new Parcelable.Creator<AudioAttributes>() {
        @Override public AudioAttributes createFromParcel(Parcel in) {
            AudioAttributes a = new AudioAttributes();
            a.mUsage = in.readInt(); a.mContentType = in.readInt(); a.mFlags = in.readInt();
            return a;
        }
        @Override public AudioAttributes[] newArray(int size) { return new AudioAttributes[size]; }
    };
}
