/* artzig stub: local media controller over the stub MediaSession. */
package android.media.session;

import android.content.Context;
import android.media.MediaMetadata;
import android.os.Bundle;
import android.os.Handler;

public final class MediaController {
    public MediaController() {}
    public MediaController(Context context, MediaSession.Token token) {}

    public TransportControls getTransportControls() { return new TransportControls(); }
    public void registerCallback(Callback callback) {}
    public void registerCallback(Callback callback, Handler handler) {}
    public void unregisterCallback(Callback callback) {}
    public PlaybackState getPlaybackState() { return null; }
    public MediaMetadata getMetadata() { return null; }
    public java.util.List<MediaSession.QueueItem> getQueue() { return null; }
    public CharSequence getQueueTitle() { return null; }
    public Bundle getExtras() { return null; }
    public int getRatingType() { return 0; }
    public long getFlags() { return 0; }
    public PlaybackInfo getPlaybackInfo() { return new PlaybackInfo(); }
    public android.app.PendingIntent getSessionActivity() { return null; }
    public MediaSession.Token getSessionToken() { return new MediaSession.Token(); }
    public String getPackageName() { return "org.schabi.newpipe"; }
    public Bundle getSessionInfo() { return new Bundle(); }
    public String getTag() { return "artzig"; }
    public boolean dispatchMediaButtonEvent(android.view.KeyEvent keyEvent) { return false; }
    public void sendCommand(String command, Bundle args, android.os.ResultReceiver cb) {}
    public void setVolumeTo(int value, int flags) {}
    public void adjustVolume(int direction, int flags) {}

    public static final class PlaybackInfo {
        public static final int PLAYBACK_TYPE_LOCAL = 1;
        public static final int PLAYBACK_TYPE_REMOTE = 2;
        public int getPlaybackType() { return PLAYBACK_TYPE_LOCAL; }
        public int getVolumeControl() { return 2; }
        public int getMaxVolume() { return 100; }
        public int getCurrentVolume() { return 100; }
        public android.media.AudioAttributes getAudioAttributes() {
            return new android.media.AudioAttributes.Builder().build();
        }
    }

    public abstract static class Callback {
        public Callback() {}
        public void onSessionDestroyed() {}
        public void onSessionEvent(String event, Bundle extras) {}
        public void onPlaybackStateChanged(PlaybackState state) {}
        public void onMetadataChanged(MediaMetadata metadata) {}
        public void onQueueChanged(java.util.List<MediaSession.QueueItem> queue) {}
        public void onQueueTitleChanged(CharSequence title) {}
        public void onExtrasChanged(Bundle extras) {}
        public void onAudioInfoChanged(PlaybackInfo info) {}
    }

    public static final class TransportControls {
        TransportControls() {}
        public void prepare() {}
        public void play() {}
        public void pause() {}
        public void stop() {}
        public void seekTo(long pos) {}
        public void fastForward() {}
        public void rewind() {}
        public void skipToNext() {}
        public void skipToPrevious() {}
        public void setPlaybackSpeed(float speed) {}
        public void setRating(android.media.Rating rating) {}
        public void playFromMediaId(String mediaId, Bundle extras) {}
        public void playFromSearch(String query, Bundle extras) {}
        public void playFromUri(android.net.Uri uri, Bundle extras) {}
        public void prepareFromMediaId(String mediaId, Bundle extras) {}
        public void prepareFromSearch(String query, Bundle extras) {}
        public void prepareFromUri(android.net.Uri uri, Bundle extras) {}
        public void skipToQueueItem(long id) {}
        public void sendCustomAction(PlaybackState.CustomAction customAction, Bundle args) {}
        public void sendCustomAction(String action, Bundle args) {}
    }
}
