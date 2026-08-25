/* artzig stub: media browsing service base (never bound by a system browser). */
package android.service.media;

import android.app.Service;
import android.content.Intent;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.IBinder;

import java.util.List;

public abstract class MediaBrowserService extends Service {
    public static final String SERVICE_INTERFACE = "android.media.browse.MediaBrowserService";

    private MediaSession.Token mSessionToken;

    public static class BrowserRoot {
        public static final String EXTRA_RECENT = "android.service.media.extra.RECENT";
        public static final String EXTRA_OFFLINE = "android.service.media.extra.OFFLINE";
        public static final String EXTRA_SUGGESTED = "android.service.media.extra.SUGGESTED";
        private final String mRootId;
        private final Bundle mExtras;
        public BrowserRoot(String rootId, Bundle extras) {
            mRootId = rootId;
            mExtras = extras;
        }
        public String getRootId() { return mRootId; }
        public Bundle getExtras() { return mExtras; }
    }

    public static class Result<T> {
        Result() {}
        public void sendResult(T result) {}
        public void detach() {}
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    public abstract BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints);
    public abstract void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result);
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result, Bundle options) {
        onLoadChildren(parentId, result);
    }
    public void onLoadItem(String itemId, Result<MediaBrowser.MediaItem> result) { result.sendResult(null); }

    public void setSessionToken(MediaSession.Token token) { mSessionToken = token; }
    public MediaSession.Token getSessionToken() { return mSessionToken; }
    public Bundle getBrowserRootHints() { return new Bundle(); }
    public android.media.session.MediaSessionManager.RemoteUserInfo getCurrentBrowserInfo() {
        return new android.media.session.MediaSessionManager.RemoteUserInfo("org.schabi.newpipe", 0, 0);
    }
    public void notifyChildrenChanged(String parentId) {}
    public void notifyChildrenChanged(String parentId, Bundle options) {}
}
