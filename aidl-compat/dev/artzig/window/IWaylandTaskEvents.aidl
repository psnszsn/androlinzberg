package dev.artzig.window;

/** Private session shell callbacks, delivered on the shell's serial handler. */
oneway interface IWaylandTaskEvents {
    void configure(int width, int height);
    void closeRequested();
    void keyboardFocus(boolean gained);
    void pointer(int kind, int button, boolean pressed, float x, float y);
    void scroll(float x, float y, float horizontal, float vertical);
    void key(int scanCode, boolean pressed);
    void touch(int action, int id, float x, float y, float major, float minor, float orientation);
    void cancelTouch();
    void repeatConfiguration(int rate, int delayMillis);
}
