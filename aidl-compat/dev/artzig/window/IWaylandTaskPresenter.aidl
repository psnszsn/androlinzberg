package dev.artzig.window;

import android.view.Surface;
import dev.artzig.window.IWaylandTaskEvents;

/** System-UID-only session presentation boundary. IDs include a task generation;
 * Android lifecycle and synthetic task bounds remain with the task organizer. */
interface IWaylandTaskPresenter {
    Surface createWindow(int taskId, long generation, int width, int height,
        String appId, String title, IWaylandTaskEvents events, out long[] configuration);
    void destroyWindow(int taskId, long generation);
    void setMetadata(int taskId, long generation, String appId, String title);
    void configurationCommitted(int taskId, long generation, int left, int top,
        int width, int height, int outputWidth, int outputHeight,
        long outputGeneration, long configurationGeneration);
    boolean setPointerCapture(int taskId, long generation, boolean enabled, int sequence);
    /** Private test pacing: requested/received callbacks across mapped tasks.
     * Covered tasks may stop callbacks without blocking visible task progress.
     * Completion means a Wayland frame callback, not a display scanout timestamp. */
    long[] observeTestFrames(boolean requestFrame);
    /** Private harness geometry: task generation, six bounds, logical size, fullscreen,
     * requested/active configuration generations, active/presented output generations, pixel size. */
    long[] testWindow(int taskId);
    /** Positive ordered input sequence; zero is rejection, negative is unknown completion.
     * Kinds: 0 tap, 1 drag, 2 text, 3 key, 4 tap+text, 5 tap+key. A combined
     * action validates once before its own tap changes focus/selection. */
    long injectTestInput(int taskId, long generation, long configurationGeneration, in int[] geometry,
        int kind, in int[] points, String text);
    boolean setPointerIcon(int taskId, long generation, int type);
    /** Establish the session-wide seat owner and return a versioned initial snapshot. */
    int[] connectSeat(IWaylandTaskEvents events);
    /** One pending producer per task, installed in the synchronized SF transaction. */
    Surface prepareOutput(int taskId, long generation, long outputGeneration,
        long configurationGeneration, int bufferWidth, int bufferHeight);
    /** Inspection-only real xdg-shell request: restore=0, fullscreen=1, maximize=2. */
    boolean testWindowState(int taskId, long generation, int state);
    /** Inspection-only bounded consumer delay; the current compositor image remains visible. */
    boolean testHoldOutput(int taskId, long generation, boolean hold);
    /** Inspection-only accounting: tasks, queues, Wayland imports, graphics buffers, descriptors. */
    long[] testResources();
    /** Immutable premultiplied ARGB cursor frames; caller and pointer-window ownership are checked. */
    boolean setCursorImage(int taskId, long generation, int width, int height,
        int hotspotX, int hotspotY, int frameMillis, in int[] pixels);
    /** Tablet cursors use that tool's current proximity serial and source generation. */
    boolean setTabletPointerIcon(int taskId, long generation, int deviceId, int deviceGeneration, int type);
    boolean setTabletCursorImage(int taskId, long generation, int deviceId, int deviceGeneration,
        int width, int height, int hotspotX, int hotspotY, int frameMillis, in int[] pixels);
    /** Production task policy: exit=0, enter=1, toggle current compositor state=2.
     * A request is not an acknowledgement; xdg configure remains authoritative. */
    boolean requestFullscreen(int taskId, long generation, int mode);
}
