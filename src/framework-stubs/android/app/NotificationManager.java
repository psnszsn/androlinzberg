/* artzig stub: notifications are ignored on host. */
package android.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NotificationManager {
    public static final int IMPORTANCE_UNSPECIFIED = -1000;
    public static final int IMPORTANCE_NONE = 0;
    public static final int IMPORTANCE_MIN = 1;
    public static final int IMPORTANCE_LOW = 2;
    public static final int IMPORTANCE_DEFAULT = 3;
    public static final int IMPORTANCE_HIGH = 4;
    public static final int IMPORTANCE_MAX = 5;
    public static final String ACTION_INTERRUPTION_FILTER_CHANGED =
            "android.app.action.INTERRUPTION_FILTER_CHANGED";

    private final Map<String, NotificationChannel> mChannels = new HashMap<>();

    public void createNotificationChannel(NotificationChannel channel) {
        mChannels.put(channel.getId(), channel);
    }
    public void createNotificationChannels(List<NotificationChannel> channels) {
        for (NotificationChannel c : channels) createNotificationChannel(c);
    }
    public void createNotificationChannelGroup(NotificationChannelGroup group) {}
    public void createNotificationChannelGroups(List<NotificationChannelGroup> groups) {}
    public NotificationChannel getNotificationChannel(String channelId) { return mChannels.get(channelId); }
    public List<NotificationChannel> getNotificationChannels() { return new ArrayList<>(mChannels.values()); }
    public void deleteNotificationChannel(String channelId) { mChannels.remove(channelId); }
    public void notify(int id, Notification notification) {}
    public void notify(String tag, int id, Notification notification) {}
    public void cancel(int id) {}
    public void cancel(String tag, int id) {}
    public void cancelAll() {}
    public boolean areNotificationsEnabled() { return false; }
    public int getImportance() { return IMPORTANCE_NONE; }
    public boolean isNotificationPolicyAccessGranted() { return false; }
}
