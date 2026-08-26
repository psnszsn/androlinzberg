/* artzig stub (auto-generated placeholder; members added on demand). */
package android.app;
public class PendingIntent {
    public PendingIntent() {}
    public PendingIntent(android.os.IBinder target) {}
    public PendingIntent(android.content.IntentSender sender) {}
    public static final int FLAG_ONE_SHOT = 1 << 30, FLAG_NO_CREATE = 1 << 29,
            FLAG_CANCEL_CURRENT = 1 << 28, FLAG_UPDATE_CURRENT = 1 << 27,
            FLAG_IMMUTABLE = 1 << 26, FLAG_MUTABLE = 1 << 25;
    public static PendingIntent getActivity(android.content.Context context, int requestCode,
            android.content.Intent intent, int flags) { return new PendingIntent(); }
    public static PendingIntent getService(android.content.Context context, int requestCode,
            android.content.Intent intent, int flags) { return new PendingIntent(); }
    public static PendingIntent getBroadcast(android.content.Context context, int requestCode,
            android.content.Intent intent, int flags) { return new PendingIntent(); }
    public static PendingIntent getForegroundService(android.content.Context context, int requestCode,
            android.content.Intent intent, int flags) { return new PendingIntent(); }
    public static PendingIntent getActivity(android.content.Context context, int requestCode,
            android.content.Intent intent, int flags, android.os.Bundle options) { return new PendingIntent(); }
    public static PendingIntent getActivities(android.content.Context context, int requestCode,
            android.content.Intent[] intents, int flags) { return new PendingIntent(); }
    public static PendingIntent getActivities(android.content.Context context, int requestCode,
            android.content.Intent[] intents, int flags, android.os.Bundle options) {
        return new PendingIntent();
    }
    public boolean isImmutable() { return true; }
    public String getCreatorPackage() { return "org.schabi.newpipe"; }
    public android.content.IntentSender getIntentSender() { return new android.content.IntentSender(); }
    public static class CanceledException extends android.util.AndroidException {
        public CanceledException() {}
        public CanceledException(String name) { super(name); }
        public CanceledException(Exception cause) { super(cause); }
    }
    public void send() throws CanceledException {}
    public void send(android.os.Bundle options) throws CanceledException {}
    public void send(android.content.Context context, int code, android.content.Intent intent) throws CanceledException {}
    public boolean isActivity() { return false; }
    public void cancel() {}
    public android.os.IBinder getTarget$stub() { return null; }
}
