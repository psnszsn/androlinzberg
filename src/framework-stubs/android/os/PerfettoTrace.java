/* artzig stub of AOSP android.os.PerfettoTrace: the real class drives the
 * Perfetto SDK through native track-event bindings (libperfetto), which do not
 * exist on this host. MessageQueue/Message emit flow, instant and counter
 * events through it; here they build against a fluent no-op so the queue's
 * accounting paths compile and cost nothing. */
package android.os;
public final class PerfettoTrace {
    private PerfettoTrace() {}
    public static final class Category {
        public Category(String name) {}
        public Category register() { return this; }
        public Category unregister() { return this; }
        public boolean isEnabled() { return false; }
    }
    public static final Category MQ_CATEGORY = new Category("mq");
    private static final java.util.concurrent.atomic.AtomicInteger sFlowId =
            new java.util.concurrent.atomic.AtomicInteger();
    /** A process-unique flow id, as the real one hands out. */
    public static int getFlowId() { return sFlowId.incrementAndGet(); }
    /** Fluent no-op standing in for PerfettoTrackEventExtra.Builder. */
    public static final class Builder {
        public Builder setFlow(long id) { return this; }
        public Builder setTerminatingFlow(long id) { return this; }
        public Builder beginProto() { return this; }
        public Builder endProto() { return this; }
        public Builder beginNested(int fieldId) { return this; }
        public Builder endNested() { return this; }
        public Builder addField(int fieldId, String value) { return this; }
        public Builder addField(int fieldId, long value) { return this; }
        public Builder addField(int fieldId, double value) { return this; }
        public Builder addArg(String name, String value) { return this; }
        public Builder addArg(String name, long value) { return this; }
        public Builder usingThreadCounterTrack(long tid, String name) { return this; }
        public Builder usingProcessCounterTrack(String name) { return this; }
        public void emit() {}
    }
    public static Builder instant(Category category, String eventName) { return new Builder(); }
    public static Builder begin(Category category, String eventName) { return new Builder(); }
    public static Builder end(Category category) { return new Builder(); }
    public static Builder counter(Category category, long value) { return new Builder(); }
    public static Builder counter(Category category, double value) { return new Builder(); }
}
