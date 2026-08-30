package dev.artzig.binder;

/** Callback interface for the hosted Binder integration test. */
interface IBinderTestCallback {
    int ping(int value);
    oneway void note(int seq);
}
