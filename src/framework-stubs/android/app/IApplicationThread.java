/* artzig stub: app-thread binder — in-process only. */
package android.app;
public interface IApplicationThread extends android.os.IInterface {
    @Override default android.os.IBinder asBinder() { return null; }
    final class Stub {
        public static IApplicationThread asInterface(android.os.IBinder b) { return null; }
        private Stub() {}
    }
}
