package dev.artzig.probe;

import dev.artzig.probe.Choice;
import dev.artzig.probe.IProbeCallback;
import dev.artzig.probe.Kind;
import dev.artzig.probe.Point;
import dev.artzig.probe.Shape;

/** Round-trip contract of the Zig AIDL backend self-test (src/binder/aidl_test.zig). */
interface IProbe {
    const int MAGIC = 42;

    int add(int a, int b);
    String echo(String text);
    @nullable String maybe(@nullable String text);
    Shape transform(in Shape shape, out Shape mirrored, inout Point cursor);
    Choice choose(in Choice choice);
    long[] scale(in long[] values, long factor, out long[] doubled);
    /** Returns callback.ask(question) and then reports it through onEvent. */
    int relay(IProbeCallback callback, int question);
    /** 1 IllegalArgument, 2 ServiceSpecific(77), 3 SecurityException. */
    void fail(int mode);
    ParcelFileDescriptor reopen(in ParcelFileDescriptor descriptor);
    @nullable IBinder same(@nullable IBinder binder);
    Kind next(Kind kind);
    @nullable String[] names(in @nullable String[] names);
}
