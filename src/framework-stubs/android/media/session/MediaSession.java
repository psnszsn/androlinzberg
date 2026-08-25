/* artzig stub: in-process media session (no session service on host). */
package android.media.session;

import android.app.PendingIntent;
import android.content.Context;
import android.media.MediaMetadata;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;

public final class MediaSession {
    public static final int FLAG_HANDLES_MEDIA_BUTTONS = 1;
    public static final int FLAG_HANDLES_TRANSPORT_CONTROLS = 2;

    private final Token mToken = new Token();
    private boolean mActive;

    public MediaSession(Context context, String tag) {}
    public MediaSession(Context context, String tag, Bundle sessionInfo) {}

    public void setCallback(Callback callback) {}
    public void setCallback(Callback callback, Handler handler) {}
    public void setSessionActivity(PendingIntent pi) {}
    public void setMediaButtonReceiver(PendingIntent mbr) {}
    public void setFlags(int flags) {}
    public void setActive(boolean active) { mActive = active; }
    public boolean isActive() { return mActive; }
    public void release() {}
    public Token getSessionToken() { return mToken; }
    public MediaController getController() { return new MediaController(); }
    public void setPlaybackState(PlaybackState state) {}
    public void setMetadata(MediaMetadata metadata) {}
    public void setQueue(java.util.List<QueueItem> queue) {}
    public void setQueueTitle(CharSequence title) {}
    public void setExtras(Bundle extras) {}
    public void setRatingType(int type) {}
    public void setPlaybackToLocal(android.media.AudioAttributes attributes) {}
    public void setPlaybackToRemote(Object volumeProvider) {}
    public void sendSessionEvent(String event, Bundle extras) {}
    public MediaSessionManager.RemoteUserInfo getCurrentControllerInfo() {
        return new MediaSessionManager.RemoteUserInfo("org.schabi.newpipe", 0, 0);
    }

    public static final class Token implements Parcelable {
        public Token() {}
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel dest, int flags) {}
        public static final Parcelable.Creator<Token> CREATOR = new Parcelable.Creator<Token>() {
            @Override public Token createFromParcel(Parcel in) { return new Token(); }
            @Override public Token[] newArray(int size) { return new Token[size]; }
        };
    }

    public abstract static class Callback {
        public Callback() {}
        public void onCommand(String command, Bundle args, Object cb) {}
        public boolean onMediaButtonEvent(android.content.Intent mediaButtonIntent) { return false; }
        public void onPlay() {}
        public void onPause() {}
        public void onStop() {}
        public void onSkipToNext() {}
        public void onSkipToPrevious() {}
        public void onFastForward() {}
        public void onRewind() {}
        public void onSeekTo(long pos) {}
        public void onSetPlaybackSpeed(float speed) {}
        public void onSetRating(android.media.Rating rating) {}
        public void onCustomAction(String action, Bundle extras) {}
        public void onPlayFromMediaId(String mediaId, Bundle extras) {}
        public void onPlayFromSearch(String query, Bundle extras) {}
        public void onPlayFromUri(android.net.Uri uri, Bundle extras) {}
        public void onPrepare() {}
        public void onPrepareFromMediaId(String mediaId, Bundle extras) {}
        public void onPrepareFromSearch(String query, Bundle extras) {}
        public void onPrepareFromUri(android.net.Uri uri, Bundle extras) {}
        public void onSkipToQueueItem(long id) {}
    }

    public static final class QueueItem implements Parcelable {
        public static final int UNKNOWN_ID = -1;
        private final android.media.MediaDescription mDescription;
        private final long mId;
        public QueueItem(android.media.MediaDescription description, long id) {
            mDescription = description; mId = id;
        }
        public android.media.MediaDescription getDescription() { return mDescription; }
        public long getQueueId() { return mId; }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel dest, int flags) {}
        public static final Parcelable.Creator<QueueItem> CREATOR = new Parcelable.Creator<QueueItem>() {
            @Override public QueueItem createFromParcel(Parcel in) { return new QueueItem(null, 0); }
            @Override public QueueItem[] newArray(int size) { return new QueueItem[size]; }
        };
    }
}
