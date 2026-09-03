/* artzig stub of AOSP android.media.MediaCommunicationManager: the manager lives in the Media
 * module tree (packages/modules/Media), which is not vendored, and there is no
 * media_communication service. MediaSessionManager holds it for MediaSession2 notifications;
 * the registry never creates one (getSystemService returns null, the AOSP absence path). */
package android.media;

public class MediaCommunicationManager {
    private MediaCommunicationManager() {}
    public void notifySession2Created(Session2Token token) {}
    /** MediaSessionLegacyHelper routes media keys here first; no MediaSession2 can take them. */
    public void dispatchMediaKeyEvent(android.view.KeyEvent keyEvent, boolean needWakeLock) {}
    /** No MediaSession2 exists in a hosted session. */
    public java.util.List<Session2Token> getSession2Tokens() { return new java.util.ArrayList<>(); }
    /** With no hosted media-communication service, no remote caller is trusted. */
    public boolean isTrustedForMediaControl(
            android.media.session.MediaSessionManager.RemoteUserInfo userInfo) { return false; }
}
