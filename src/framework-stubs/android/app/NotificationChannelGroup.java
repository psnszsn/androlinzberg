/* artzig stub of AOSP android.app.NotificationChannelGroup: the notification service is
 * not hosted yet (managers-over-hosted-services milestone); value semantics only. */
package android.app;
public final class NotificationChannelGroup implements android.os.Parcelable {
    private final String mId;
    private CharSequence mName;
    private String mDescription;
    public NotificationChannelGroup(String id, CharSequence name) { mId = id; mName = name; }
    public String getId() { return mId; }
    public CharSequence getName() { return mName; }
    public String getDescription() { return mDescription; }
    public void setDescription(String description) { mDescription = description; }
    public java.util.List<NotificationChannel> getChannels() { return new java.util.ArrayList<>(); }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(android.os.Parcel dest, int flags) {
        dest.writeString(mId);
        android.text.TextUtils.writeToParcel(mName, dest, flags);
        dest.writeString(mDescription);
    }
    public static final Creator<NotificationChannelGroup> CREATOR = new Creator<NotificationChannelGroup>() {
        @Override public NotificationChannelGroup createFromParcel(android.os.Parcel in) {
            NotificationChannelGroup g = new NotificationChannelGroup(in.readString(),
                    android.text.TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in));
            g.mDescription = in.readString();
            return g;
        }
        @Override public NotificationChannelGroup[] newArray(int size) { return new NotificationChannelGroup[size]; }
    };
}
