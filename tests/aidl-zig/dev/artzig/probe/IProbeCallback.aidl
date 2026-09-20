package dev.artzig.probe;

interface IProbeCallback {
    int ask(int question);
    oneway void onEvent(int id, String text);
}
