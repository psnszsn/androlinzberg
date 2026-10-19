package dev.artzig.window;

import android.view.Surface;
import dev.artzig.window.IWaylandTaskEvents;

/** System-UID-only session presentation boundary. IDs include a task generation;
 * Android lifecycle and synthetic task bounds remain with the task organizer. */
interface IWaylandTaskPresenter {
    /** configuration: logical width/height, pixel width/height, generation, serial, scale in 120ths. */
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
     * Kinds: 0 tap, 1 drag, 2 text, 3 key, 4 tap+text, 5 tap+key, 6 wheel
     * scroll of points[2] detents (positive scrolls down) at points[0..1]. A combined
     * action validates once before its own tap changes focus/selection. */
    long injectTestInput(int taskId, long generation, long configurationGeneration, in int[] geometry,
        int kind, in int[] points, String text);
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
    /** SurfaceFlinger's cursor plane: the host composer's CURSOR layer, which is
     * stock PointerController's sprite, as premultiplied ARGB with the hotspot
     * of its CursorInfo layer metadata. Width 0: Android shows no pointer. */
    oneway void setDisplayCursor(int hotspotX, int hotspotY, int width, int height, in int[] pixels);
    /** Production task policy: exit=0, enter=1, toggle current compositor state=2.
     * A request is not an acknowledgement; xdg configure remains authoritative. */
    boolean requestFullscreen(int taskId, long generation, int mode);
    /** Independent of physical seat-device capabilities. */
    boolean textInputAvailable();
    /** UTF-8 byte offsets; appliedSequence acknowledges completed app edits.
     * Empty cursorBounds means unknown; otherwise surface-local x/y/width/height. */
    boolean updateTextInput(int taskId, long generation, long editorGeneration,
        long appliedSequence, boolean enabled, @nullable String surrounding,
        int cursor, int anchor, int hint, int purpose, boolean externalChange,
        in int[] cursorBounds);
    boolean clipboardAvailable();
    /** Bounded UTF-8 representations; all-null clears the native selection. */
    boolean setClipboard(long revision, in @nullable byte[] text, in @nullable byte[] html, in @nullable byte[] uris);
    /** Copy acceptance and actual Android recipient result for one native offer. */
    boolean acceptNativeDrag(long token, boolean accepted);
    boolean finishNativeDrag(long token, boolean consumed);
    /** Snapshot one actual held pointer (kind 0) or raw touch (kind 1) generation. */
    long claimNativeDrag(int taskId, long generation, long configurationGeneration, int kind, int inputId);
    /** Result is [sourceToken, status]: accepted=1, rejected before takeover=2,
     * uncertain=3. The owner dispatches Wayland while this bounded call waits.
     * All-null representations make a private Android-only source. */
    @nullable Surface startNativeDrag(int taskId, long generation, long configurationGeneration,
        int kind, int inputId, long claim, int width, int height, int hotspotX, int hotspotY,
        int bufferWidth, int bufferHeight, in @nullable byte[] text, in @nullable byte[] html,
        in @nullable byte[] uris, out long[] result);
    boolean cancelNativeDragSource(long sourceToken);
    /** Call only after unbinding the private SF output and terminal source event. */
    boolean retireNativeDragIcon(long sourceToken);
}
