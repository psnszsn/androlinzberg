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
    // Pinned AudioAttributes.getBundle/Builder.addBundle/build and parcel layout.
    // The full class still needs the AudioSystem/service dependency closure.
    private int mSource = -1;
    private java.util.HashSet<String> mTags = new java.util.HashSet<>();
    private String mFormattedTags = "";
    private android.os.Bundle mBundle;
    public static final int FLATTEN_TAGS = 0x1;
    private static final int ATTR_PARCEL_IS_NULL_BUNDLE = -1977;
    private static final int ATTR_PARCEL_IS_VALID_BUNDLE = 1980;

    public android.os.Bundle getBundle() {
        return mBundle == null ? null : new android.os.Bundle(mBundle);
    }

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
            a.mSource = src.mSource;
            a.mTags = new java.util.HashSet<>(src.mTags);
            a.mFormattedTags = src.mFormattedTags;
            // The pinned copy builder does not inherit the source bundle.
        }
        public Builder addBundle(android.os.Bundle bundle) {
            if (bundle == null) throw new IllegalArgumentException("Illegal null bundle");
            if (a.mBundle == null) a.mBundle = new android.os.Bundle(bundle);
            else a.mBundle.putAll(bundle);
            return this;
        }
        public Builder setUsage(int usage) { a.mUsage = usage; return this; }
        public Builder setContentType(int contentType) { a.mContentType = contentType; return this; }
        public Builder setFlags(int flags) { a.mFlags |= flags; return this; }
        public Builder setLegacyStreamType(int streamType) { return this; }
        public Builder setAllowedCapturePolicy(int policy) { return this; }
        public Builder setSpatializationBehavior(int sb) { return this; }
        public Builder setIsContentSpatialized(boolean isSpatialized) { return this; }
        public Builder setHapticChannelsMuted(boolean muted) { return this; }
        public AudioAttributes build() {
            AudioAttributes result = new AudioAttributes();
            result.mUsage = a.mUsage;
            result.mContentType = a.mContentType;
            result.mFlags = a.mFlags;
            result.mSource = a.mSource;
            result.mTags = new java.util.HashSet<>(a.mTags);
            result.mFormattedTags = android.text.TextUtils.join(";", result.mTags);
            if (a.mBundle != null) result.mBundle = new android.os.Bundle(a.mBundle);
            return result;
        }
    }

    @Override public int describeContents() { return 0; }
    /** AOSP dumpDebug writes AudioAttributesProto fields; the hosted proto sink is inert. */
    public void dumpDebug(android.util.proto.ProtoOutputStream proto, long fieldId) {}
    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mUsage); dest.writeInt(mContentType); dest.writeInt(mSource);
        dest.writeInt(mFlags); dest.writeInt(flags & FLATTEN_TAGS);
        if ((flags & FLATTEN_TAGS) == 0) dest.writeStringArray(mTags.toArray(new String[0]));
        else dest.writeString(mFormattedTags);
        if (mBundle == null) dest.writeInt(ATTR_PARCEL_IS_NULL_BUNDLE);
        else {
            dest.writeInt(ATTR_PARCEL_IS_VALID_BUNDLE);
            dest.writeBundle(mBundle);
        }
    }
    public static final Parcelable.Creator<AudioAttributes> CREATOR = new Parcelable.Creator<AudioAttributes>() {
        @Override public AudioAttributes createFromParcel(Parcel in) {
            AudioAttributes a = new AudioAttributes();
            a.mUsage = in.readInt(); a.mContentType = in.readInt(); a.mSource = in.readInt();
            a.mFlags = in.readInt();
            if ((in.readInt() & FLATTEN_TAGS) == FLATTEN_TAGS) {
                a.mFormattedTags = new String(in.readString());
                a.mTags.add(a.mFormattedTags);
            } else {
                String[] tags = in.readStringArray();
                for (int i = tags.length - 1; i >= 0; i--) a.mTags.add(tags[i]);
                a.mFormattedTags = android.text.TextUtils.join(";", a.mTags);
            }
            switch (in.readInt()) {
                case ATTR_PARCEL_IS_NULL_BUNDLE: break;
                case ATTR_PARCEL_IS_VALID_BUNDLE:
                    a.mBundle = new android.os.Bundle(in.readBundle());
                    break;
                default:
                    android.util.Log.e("AudioAttributes", "Illegal value unmarshalling AudioAttributes, can't initialize bundle");
            }
            return a;
        }
        @Override public AudioAttributes[] newArray(int size) { return new AudioAttributes[size]; }
    };
}
