/* artzig stub. */
package android.app;
public final class NotificationChannelGroup {
    private final String mId;
    private CharSequence mName;
    private String mDescription;
    public NotificationChannelGroup(String id, CharSequence name) { mId = id; mName = name; }
    public String getId() { return mId; }
    public CharSequence getName() { return mName; }
    public String getDescription() { return mDescription; }
    public void setDescription(String description) { mDescription = description; }
    public java.util.List<NotificationChannel> getChannels() { return new java.util.ArrayList<>(); }
}
