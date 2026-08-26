/* artzig stub: notifications are ignored on host; Builder records nothing. */
package android.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

public class Notification implements Parcelable {
    public static final AudioAttributes AUDIO_ATTRIBUTES_DEFAULT = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build();
    public static final int DEFAULT_ALL = ~0;
    public static final int DEFAULT_SOUND = 1;
    public static final int DEFAULT_VIBRATE = 2;
    public static final int DEFAULT_LIGHTS = 4;
    public static final int FLAG_SHOW_LIGHTS = 0x1;
    public static final int FLAG_ONGOING_EVENT = 0x2;
    public static final int FLAG_INSISTENT = 0x4;
    public static final int FLAG_ONLY_ALERT_ONCE = 0x8;
    public static final int FLAG_AUTO_CANCEL = 0x10;
    public static final int FLAG_NO_CLEAR = 0x20;
    public static final int FLAG_FOREGROUND_SERVICE = 0x40;
    public static final int FLAG_GROUP_SUMMARY = 0x200;
    public static final int PRIORITY_DEFAULT = 0;
    public static final int PRIORITY_LOW = -1;
    public static final int PRIORITY_MIN = -2;
    public static final int PRIORITY_HIGH = 1;
    public static final int PRIORITY_MAX = 2;
    public static final int VISIBILITY_PUBLIC = 1;
    public static final int VISIBILITY_PRIVATE = 0;
    public static final int VISIBILITY_SECRET = -1;
    public static final String CATEGORY_PROGRESS = "progress";
    public static final String CATEGORY_SERVICE = "service";
    public static final String CATEGORY_TRANSPORT = "transport";
    public static final String CATEGORY_ERROR = "err";

    public int icon;
    public long when;
    public int flags;
    public int defaults;
    public int priority;
    public int number;
    public int visibility;
    public String category;
    public CharSequence tickerText;
    public PendingIntent contentIntent;
    public PendingIntent deleteIntent;
    public Uri sound;
    public long[] vibrate;
    public int ledARGB, ledOnMS, ledOffMS;
    public int audioStreamType = -1;
    public int iconLevel;
    public PendingIntent fullScreenIntent;
    public CharSequence[] textLines;
    public String sortKey;
    public android.os.Bundle publicVersion;
    public android.media.AudioAttributes audioAttributes = AUDIO_ATTRIBUTES_DEFAULT;
    public android.graphics.Bitmap largeIcon;
    public android.widget.RemoteViews contentView, bigContentView, headsUpContentView;
    public String category2;
    public Bundle extras = new Bundle();
    public Action[] actions;

    public Notification() {}
    public String getChannelId() { return null; }
    public String getGroup() { return null; }

    public abstract static class Style {
        public Style() {}
        public void setBuilder(Builder builder) {}
        public Notification build() { return new Notification(); }
    }
    public static class BigTextStyle extends Style {
        public BigTextStyle() {}
        public BigTextStyle bigText(CharSequence cs) { return this; }
        public BigTextStyle setBigContentTitle(CharSequence title) { return this; }
        public BigTextStyle setSummaryText(CharSequence cs) { return this; }
    }
    public static class BigPictureStyle extends Style {
        public BigPictureStyle() {}
        public BigPictureStyle bigPicture(Bitmap b) { return this; }
        public BigPictureStyle bigLargeIcon(Bitmap b) { return this; }
        public BigPictureStyle setBigContentTitle(CharSequence title) { return this; }
        public BigPictureStyle setSummaryText(CharSequence cs) { return this; }
    }
    public static class InboxStyle extends Style {
        public InboxStyle() {}
        public InboxStyle addLine(CharSequence cs) { return this; }
        public InboxStyle setBigContentTitle(CharSequence title) { return this; }
        public InboxStyle setSummaryText(CharSequence cs) { return this; }
    }
    public static class DecoratedCustomViewStyle extends Style {
        public DecoratedCustomViewStyle() {}
    }
    public static class MediaStyle extends Style {
        public MediaStyle() {}
        public MediaStyle setShowActionsInCompactView(int... actions) { return this; }
        public MediaStyle setMediaSession(android.media.session.MediaSession.Token token) { return this; }
    }

    public static final class BubbleMetadata implements Parcelable {
        private BubbleMetadata() {}
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel dest, int flags) {}
        public static final Parcelable.Creator<BubbleMetadata> CREATOR = new Parcelable.Creator<BubbleMetadata>() {
            @Override public BubbleMetadata createFromParcel(Parcel in) { return new BubbleMetadata(); }
            @Override public BubbleMetadata[] newArray(int size) { return new BubbleMetadata[size]; }
        };
        public static final class Builder {
            public Builder(PendingIntent intent, android.graphics.drawable.Icon icon) {}
            public Builder(String shortcutId) {}
            public Builder setDesiredHeight(int height) { return this; }
            public Builder setAutoExpandBubble(boolean shouldExpand) { return this; }
            public Builder setSuppressNotification(boolean shouldSuppressNotif) { return this; }
            public BubbleMetadata build() { return new BubbleMetadata(); }
        }
    }

    public static class Action implements Parcelable {
        public int icon;
        public CharSequence title;
        public PendingIntent actionIntent;
        private boolean mAuthenticationRequired;
        public Action(int icon, CharSequence title, PendingIntent intent) {
            this.icon = icon; this.title = title; this.actionIntent = intent;
        }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel dest, int flags) {}
        public Action(android.graphics.drawable.Icon icon, CharSequence title, PendingIntent intent) {
            this(0, title, intent);
        }
        public boolean isAuthenticationRequired() { return mAuthenticationRequired; }
        public static final class Builder {
            private final CharSequence mTitle;
            private final PendingIntent mIntent;
            private boolean mAuthenticationRequired;
            public Builder(int icon, CharSequence title, PendingIntent intent) { mTitle = title; mIntent = intent; }
            public Builder(android.graphics.drawable.Icon icon, CharSequence title, PendingIntent intent) { mTitle = title; mIntent = intent; }
            public Builder addExtras(android.os.Bundle extras) { return this; }
            public Builder addRemoteInput(Object remoteInput) { return this; }
            public Builder setAllowGeneratedReplies(boolean allow) { return this; }
            public Builder setSemanticAction(int semanticAction) { return this; }
            public Builder setContextual(boolean isContextual) { return this; }
            public Builder setAuthenticationRequired(boolean authenticationRequired) {
                mAuthenticationRequired = authenticationRequired;
                return this;
            }
            public Action build() {
                Action action = new Action(0, mTitle, mIntent);
                action.mAuthenticationRequired = mAuthenticationRequired;
                return action;
            }
        }
        public static final Parcelable.Creator<Action> CREATOR = new Parcelable.Creator<Action>() {
            @Override public Action createFromParcel(Parcel in) { return new Action(0, null, null); }
            @Override public Action[] newArray(int size) { return new Action[size]; }
        };
    }

    public static class Builder {
        private final Notification n = new Notification();
        public Builder(Context context) {}
        public Builder(Context context, String channelId) {}
        public Builder setSmallIcon(int icon) { n.icon = icon; return this; }
        public Builder setSmallIcon(Object icon) { return this; }
        public Builder setSmallIcon(int icon, int level) { n.icon = icon; n.iconLevel = level; return this; }
        public Builder setChannelId(String channelId) { return this; }
        public Builder setContent(android.widget.RemoteViews views) { return this; }
        public Builder setTicker(CharSequence tickerText, android.widget.RemoteViews views) { n.tickerText = tickerText; return this; }
        public Builder setSound(Uri sound, int streamType) { n.sound = sound; return this; }
        public Builder setSound(Uri sound, android.media.AudioAttributes attributes) { n.sound = sound; return this; }
        public Builder addPerson(String uri) { return this; }
        public Builder setColorized(boolean colorize) { return this; }
        public Builder setSettingsText(CharSequence text) { return this; }
        public Builder setLocusId(Object locusId) { return this; }
        public Builder setFlag(int mask, boolean value) { return this; }
        public Builder setContentInfo(CharSequence info) { return this; }
        public Builder setUsesChronometer(boolean b) { return this; }
        public Builder setChronometerCountDown(boolean countDown) { return this; }
        public Builder setFullScreenIntent(PendingIntent intent, boolean highPriority) { return this; }
        public Builder setSortKey(String sortKey) { return this; }
        public Builder setBadgeIconType(int icon) { return this; }
        public Builder setShortcutId(String shortcutId) { return this; }
        public Builder setTimeoutAfter(long durationMs) { return this; }
        public Builder setGroupAlertBehavior(int groupAlertBehavior) { return this; }
        public Builder setBubbleMetadata(BubbleMetadata data) { return this; }
        public Builder setAllowSystemGeneratedContextualActions(boolean allowed) { return this; }
        public Builder setPublicVersion(Notification n2) { return this; }
        public Builder setRemoteInputHistory(CharSequence[] text) { return this; }
        public Builder setCustomContentView(android.widget.RemoteViews contentView) { return this; }
        public Builder setCustomBigContentView(android.widget.RemoteViews contentView) { return this; }
        public Builder setCustomHeadsUpContentView(android.widget.RemoteViews contentView) { return this; }
        public Builder setForegroundServiceBehavior(int behavior) { return this; }
        public Builder setContentTitle(CharSequence title) { return this; }
        public Builder setContentText(CharSequence text) { return this; }
        public Builder setSubText(CharSequence text) { return this; }
        public Builder setTicker(CharSequence tickerText) { n.tickerText = tickerText; return this; }
        public Builder setWhen(long when) { n.when = when; return this; }
        public Builder setShowWhen(boolean show) { return this; }
        public Builder setContentIntent(PendingIntent intent) { n.contentIntent = intent; return this; }
        public Builder setDeleteIntent(PendingIntent intent) { n.deleteIntent = intent; return this; }
        public Builder setLargeIcon(Bitmap b) { return this; }
        public Builder setLargeIcon(android.graphics.drawable.Icon icon) { return this; }
        public Builder setSmallIcon(android.graphics.drawable.Icon icon) { return this; }
        public Builder setAutoCancel(boolean autoCancel) { return this; }
        public Builder setOngoing(boolean ongoing) { return this; }
        public Builder setOnlyAlertOnce(boolean onlyAlertOnce) { return this; }
        public Builder setPriority(int pri) { n.priority = pri; return this; }
        public Builder setCategory(String category) { n.category = category; return this; }
        public Builder setDefaults(int defaults) { n.defaults = defaults; return this; }
        public Builder setVisibility(int visibility) { n.visibility = visibility; return this; }
        public Builder setProgress(int max, int progress, boolean indeterminate) { return this; }
        public Builder setGroup(String groupKey) { return this; }
        public Builder setGroupSummary(boolean isGroupSummary) { return this; }
        public Builder setSound(Uri sound) { n.sound = sound; return this; }
        public Builder setVibrate(long[] pattern) { n.vibrate = pattern; return this; }
        public Builder setLights(int argb, int onMs, int offMs) { return this; }
        public Builder setNumber(int number) { n.number = number; return this; }
        public Builder setColor(int argb) { return this; }
        public Builder setLocalOnly(boolean localOnly) { return this; }
        public Builder setStyle(Style style) { return this; }
        public Builder addAction(int icon, CharSequence title, PendingIntent intent) {
            return this;
        }
        public Builder addAction(Action action) { return this; }
        public Builder addExtras(Bundle extras) { return this; }
        public Builder setExtras(Bundle extras) { return this; }
        public Bundle getExtras() { return n.extras; }
        public Notification build() { return n; }
        public Notification getNotification() { return n; }
    }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {}
    public static final Parcelable.Creator<Notification> CREATOR = new Parcelable.Creator<Notification>() {
        @Override public Notification createFromParcel(Parcel in) { return new Notification(); }
        @Override public Notification[] newArray(int size) { return new Notification[size]; }
    };
}
