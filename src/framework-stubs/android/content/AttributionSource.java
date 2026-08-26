/* artzig stub. */
package android.content;

/** Minimal in-process representation used by framework clients for call attribution. */
public class AttributionSource {
    private final int uid;
    private final int pid;
    private final String packageName;
    private final String attributionTag;
    private final AttributionSource next;

    public AttributionSource(int uid, String packageName, String attributionTag) {
        this(uid, android.os.Process.INVALID_PID, packageName, attributionTag, null);
    }

    public AttributionSource(int uid, int pid, String packageName, String attributionTag) {
        this(uid, pid, packageName, attributionTag, null);
    }

    public AttributionSource(AttributionSource current, AttributionSource next) {
        this(current.uid, current.pid, current.packageName, current.attributionTag, next);
    }

    private AttributionSource(int uid, int pid, String packageName, String attributionTag,
            AttributionSource next) {
        this.uid = uid;
        this.pid = pid;
        this.packageName = packageName;
        this.attributionTag = attributionTag;
        this.next = next;
    }

    public int getUid() { return uid; }
    public int getPid() { return pid; }
    public String getPackageName() { return packageName; }
    public String getAttributionTag() { return attributionTag; }
    public AttributionSource getNext() { return next; }
    public AttributionSource withNext(AttributionSource next) {
        return new AttributionSource(this, next);
    }
}
