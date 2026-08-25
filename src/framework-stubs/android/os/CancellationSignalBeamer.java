/* artzig stub (auto-generated placeholder; members added on demand). */
package android.os;
public class CancellationSignalBeamer {
    public static class Receiver {
        public Receiver(boolean cancelOnSenderDeath) {}
        public android.os.IBinder beamFromScope$stub() { return null; }
        public CancellationSignal unbeam(android.os.IBinder token) { return null; }
        public void forget(android.os.IBinder token) { }
        public void cancel(android.os.IBinder token) { }
    }
    public abstract static class Sender {
        public static android.os.IBinder beamFromScope(CancellationSignal signal) { return null; }
        public android.os.IBinder beam(CancellationSignal signal) { return null; }
    }
}
