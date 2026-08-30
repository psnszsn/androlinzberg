/* artzig: source of truth for the single logical display (this process's Wayland window). */
package android.view;

public final class ArtzigDisplay {
    private static Display sDisplay;
    private static int sWidth = 1280, sHeight = 720, sDpi = 160;
    private static Runnable sChangeListener;

    private ArtzigDisplay() {}

    public static synchronized Display get() {
        if (sDisplay == null) sDisplay = create(sWidth, sHeight, sDpi);
        return sDisplay;
    }

    public static synchronized void configure(int width, int height, int densityDpi) {
        sWidth = width;
        sHeight = height;
        sDpi = densityDpi;
        sDisplay = create(width, height, densityDpi);
        Runnable listener = sChangeListener;
        if (listener != null) listener.run();
    }

    /** The hosted display service observes window resizes through this. */
    public static synchronized void setChangeListener(Runnable listener) { sChangeListener = listener; }

    /** A fresh DisplayInfo for the current bounds. */
    public static synchronized DisplayInfo displayInfo() {
        return createInfo(sWidth, sHeight, sDpi);
    }

    private static Display create(int w, int h, int dpi) {
        return new Display(null, Display.DEFAULT_DISPLAY, createInfo(w, h, dpi),
                new DisplayAdjustments());
    }

    private static DisplayInfo createInfo(int w, int h, int dpi) {
        DisplayInfo info = new DisplayInfo();
        info.displayId = Display.DEFAULT_DISPLAY;
        info.name = "artzig";
        info.appWidth = w;
        info.appHeight = h;
        info.logicalWidth = w;
        info.logicalHeight = h;
        info.rotation = Surface.ROTATION_0;
        info.modeId = 1;
        info.renderFrameRate = 60f;
        info.defaultModeId = 1;
        info.supportedModes = new Display.Mode[] { new Display.Mode(1, w, h, 60f) };
        info.logicalDensityDpi = dpi;
        info.physicalXDpi = dpi;
        info.physicalYDpi = dpi;
        info.state = Display.STATE_ON;
        return info;
    }
}
