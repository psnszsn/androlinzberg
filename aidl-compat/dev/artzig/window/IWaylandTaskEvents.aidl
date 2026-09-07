package dev.artzig.window;

/** Private session shell callbacks, delivered on the shell's serial handler. */
oneway interface IWaylandTaskEvents {
    void configure(int taskId, long generation, int width, int height);
    void closeRequested(int taskId, long generation);
    void keyboardFocus(int taskId, long generation, boolean gained);
    void pointer(int taskId, long generation, int kind, int button, boolean pressed, float x, float y);
    void scroll(int taskId, long generation, float x, float y, float horizontal, float vertical);
    void key(int taskId, long generation, int scanCode, boolean pressed, boolean repeatable);
    void touch(int taskId, long generation, int action, int id, float x, float y, float major, float minor, float orientation);
    void cancelTouch(int taskId, long generation);
    void repeatConfiguration(int taskId, long generation, int rate, int delayMillis);
    void keyboardLayout(int taskId, long generation, int layoutGeneration, in int[] characters);
    void modifiers(int taskId, long generation, int metaState);
}
