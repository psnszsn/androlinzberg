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
