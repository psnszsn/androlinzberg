/* artzig stub: remote transition binder — unused on host. */
package android.window;
public interface IRemoteTransition extends android.os.IInterface {
    @Override default android.os.IBinder asBinder() { return null; }
    abstract class Stub extends android.os.Binder implements IRemoteTransition {
        public static IRemoteTransition asInterface(android.os.IBinder obj) { return null; }
        @Override public android.os.IBinder asBinder() { return this; }
    }
}
