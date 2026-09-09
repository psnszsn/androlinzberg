package dev.artzig.binder;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import dev.artzig.binder.IBinderTestCallback;

/** Service interface for the hosted Binder integration test. */
interface IBinderTestService {
    int add(int a, int b);
    String echo(String value);
    Bundle roundTripBundle(in Bundle value);
    /** Invokes callback.ping(value) synchronously and returns its result plus one. */
    int callBack(IBinderTestCallback callback, int value);
    void registerCallback(IBinderTestCallback callback);
    IBinderTestCallback getCallback();
    IBinder identity(IBinder binder);
    oneway void noteSeq(int seq);
    /** Waits until `expected` oneway notes arrived and returns them in receipt order. */
    int[] drainSeqs(int expected);
    void throwIllegalArgument(String message);
    void writeToPipe(in ParcelFileDescriptor fd, String text);
    int selfPid();
    int callerPid();
    int callerUid();
    void exit();
    /** Queues ordered notes on many distinct local objects in the caller. */
    void queueNotes(in Bundle callbacks, int count);
}
