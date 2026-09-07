package dev.artzig.window;

import android.view.Surface;
import dev.artzig.window.IWaylandTaskEvents;

/** System-UID-only session presentation boundary. IDs include a task generation;
 * Android lifecycle and synthetic task bounds remain with the task organizer. */
interface IWaylandTaskPresenter {
    Surface createWindow(int taskId, long generation, int width, int height,
        String appId, String title, IWaylandTaskEvents events, out int[] configuration);
    void destroyWindow(int taskId, long generation);
    void setMetadata(int taskId, long generation, String appId, String title);
    void configurationCommitted(int taskId, long generation, int left, int top,
        int width, int height, int outputWidth, int outputHeight);
    boolean setPointerCapture(int taskId, long generation, boolean enabled, int sequence);
    /** Private test pacing: submitted/completed rounds over all mapped tasks.
     * Completion means a Wayland frame callback, not a display scanout timestamp. */
    long[] observeTestFrames(boolean requestFrame);
    /** Private harness geometry: generation, six committed bounds, actual size, fullscreen. */
    long[] testWindow(int taskId);
    /** Positive ordered input sequence; zero is rejection, negative is unknown completion. */
    long injectTestInput(int taskId, long generation, in int[] geometry,
        int kind, in int[] points, String text);
    boolean setPointerIcon(int taskId, long generation, int type);
    /** Establish the session-wide seat owner and return a versioned initial snapshot. */
    int[] connectSeat(IWaylandTaskEvents events);
}
