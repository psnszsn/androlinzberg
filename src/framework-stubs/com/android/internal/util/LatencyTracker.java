/* artzig stub (auto-generated placeholder; members added on demand). */
package com.android.internal.util;
public class LatencyTracker {
    public static final int ACTION_SHOW_SELECTION_TOOLBAR = 17;
    public static final int ACTION_REQUEST_IME_SHOWN = 18, ACTION_REQUEST_IME_HIDDEN = 19;
    private static final LatencyTracker sInstance = new LatencyTracker();
    public static LatencyTracker getInstance(android.content.Context context) { return sInstance; }
    public void onActionStart(int action) {}
    public void onActionStart(int action, String tag) {}
    public void onActionEnd(int action) {}
    public void onActionCancel(int action) {}
}
