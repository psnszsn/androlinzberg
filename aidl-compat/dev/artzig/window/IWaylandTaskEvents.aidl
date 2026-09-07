package dev.artzig.window;

/** Private session shell callbacks, delivered on the shell's serial handler. */
oneway interface IWaylandTaskEvents {
    void configure(int width, int height);
    void closeRequested();
}
