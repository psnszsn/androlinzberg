/* artzig stub: notification channels are recorded but unused on host. */
package android.app;

import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

public final class NotificationChannel implements Parcelable {
    public static final String DEFAULT_CHANNEL_ID = "miscellaneous";

    private final String mId;
    private CharSequence mName;
    private int mImportance;
    private String mDescription;
    private String mGroup;
    private boolean mLights, mVibration, mBadge = true, mBypassDnd;
    private int mLightColor;
    private long[] mVibrationPattern;
    private Uri mSound;
    private AudioAttributes mAudioAttributes;
    private int mLockscreenVisibility = -1000;
    private boolean mAllowBubbles;
    private String mParentChannelId;
    private String mConversationId;

    public NotificationChannel(String id, CharSequence name, int importance) {
        mId = id; mName = name; mImportance = importance;
    }
    public String getId() { return mId; }
    public CharSequence getName() { return mName; }
    public void setName(CharSequence name) { mName = name; }
    public int getImportance() { return mImportance; }
    public void setImportance(int importance) { mImportance = importance; }
    public String getDescription() { return mDescription; }
    public void setDescription(String description) { mDescription = description; }
    public String getGroup() { return mGroup; }
    public void setGroup(String groupId) { mGroup = groupId; }
    public void enableLights(boolean lights) { mLights = lights; }
    public boolean shouldShowLights() { return mLights; }
    public void setLightColor(int argb) { mLightColor = argb; }
    public int getLightColor() { return mLightColor; }
    public void enableVibration(boolean vibration) { mVibration = vibration; }
    public boolean shouldVibrate() { return mVibration; }
    public void setVibrationPattern(long[] vibrationPattern) { mVibrationPattern = vibrationPattern; }
    public long[] getVibrationPattern() { return mVibrationPattern; }
    public void setShowBadge(boolean showBadge) { mBadge = showBadge; }
    public boolean canShowBadge() { return mBadge; }
    public void setBypassDnd(boolean bypassDnd) { mBypassDnd = bypassDnd; }
    public boolean canBypassDnd() { return mBypassDnd; }
    public void setSound(Uri sound, AudioAttributes audioAttributes) { mSound = sound; mAudioAttributes = audioAttributes; }
    public Uri getSound() { return mSound; }
    public AudioAttributes getAudioAttributes() { return mAudioAttributes; }
    public void setLockscreenVisibility(int lockscreenVisibility) { mLockscreenVisibility = lockscreenVisibility; }
    public int getLockscreenVisibility() { return mLockscreenVisibility; }
    public void setAllowBubbles(boolean allowBubbles) { mAllowBubbles = allowBubbles; }
    public boolean canBubble() { return mAllowBubbles; }
    public String getConversationId() { return mConversationId; }
    public String getParentChannelId() { return mParentChannelId; }
    public void setConversationId(String parentChannelId, String conversationId) {
        mParentChannelId = parentChannelId;
        mConversationId = conversationId;
    }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) { dest.writeString(mId); }
    public static final Parcelable.Creator<NotificationChannel> CREATOR = new Parcelable.Creator<NotificationChannel>() {
        @Override public NotificationChannel createFromParcel(Parcel in) {
            return new NotificationChannel(in.readString(), null, 0);
        }
        @Override public NotificationChannel[] newArray(int size) { return new NotificationChannel[size]; }
    };
}
