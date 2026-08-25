/* artzig stub: broadcasts arrive with the app layer (M4+). */
package android.content;
public abstract class BroadcastReceiver {
    public abstract void onReceive(Context context, Intent intent);
    public final android.os.Bundle getResultExtras(boolean makeMap) { return new android.os.Bundle(); }
    public final void setResultData(String data) {}
    public final String getResultData() { return null; }
    public final boolean isInitialStickyBroadcast() { return false; }
    public final boolean isOrderedBroadcast() { return false; }
    public final void abortBroadcast() {}
    public final PendingResult goAsync() { return new PendingResult(); }
    public static class PendingResult {
        public void finish() {}
    }
}
