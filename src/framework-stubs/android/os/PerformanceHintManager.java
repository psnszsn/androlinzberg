/* artzig stub. */
package android.os;
public final class PerformanceHintManager {
    public Session createHintSession(int[] tids, long initialTargetWorkDurationNanos) { return null; }
    public static class Session implements AutoCloseable {
        public static final int CPU_LOAD_UP = 0, CPU_LOAD_DOWN = 1, CPU_LOAD_RESET = 2, CPU_LOAD_RESUME = 3;
        public void sendHint(int hint) {}
        public void updateTargetWorkDuration(long targetDurationNanos) {}
        public void reportActualWorkDuration(long actualDurationNanos) {}
        public void setThreads(int[] tids) {}
        @Override public void close() {}
    }
}
