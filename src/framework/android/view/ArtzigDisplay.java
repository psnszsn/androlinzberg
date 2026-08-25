/* artzig: factory for the single logical display. */
package android.view;

public final class ArtzigDisplay {
    private static Display sDisplay;

    private ArtzigDisplay() {}

    public static synchronized Display get() {
        if (sDisplay == null) sDisplay = create(1280, 720, 160);
        return sDisplay;
    }

    public static synchronized void configure(int width, int height, int densityDpi) {
        sDisplay = create(width, height, densityDpi);
    }

    private static Display create(int w, int h, int dpi) {
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
        return new Display(null, Display.DEFAULT_DISPLAY, info,
                new DisplayAdjustments());
    }
}
