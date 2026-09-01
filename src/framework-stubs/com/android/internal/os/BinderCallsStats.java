/* artzig stub of AOSP com.android.internal.os.BinderCallsStats.
 * The real class is binder-call telemetry that reads Settings through a
 * ContentObserver and dumps BinderLatencyProto; neither the settings provider nor
 * that proto exists on this host. Only the nested CallStat type is referenced
 * (BinderInternal.CallStatsObserver), so this carries just that shape. */
package com.android.internal.os;
public class BinderCallsStats {
    public static class CallStat {
        public int callingUid;
        public Class<? extends android.os.Binder> binderClass;
        public int transactionCode;
        public long callCount;
        public long recordedCallCount;
        public long cpuTimeMicros;
        public long latencyMicros;
        public long maxLatencyMicros;
        public long exceptionCount;
        public long maxRequestSizeBytes;
        public long maxReplySizeBytes;
        public long incrementalCallCount;
    }
    private BinderCallsStats() {}
}
