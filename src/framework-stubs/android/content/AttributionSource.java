/* artzig stub. */
package android.content;

/** Minimal representation used by framework clients for call attribution.
 * Parcelable so it can ride in Bundles; the hosted provider transport never
 * trusts a parceled instance (the broker uid is authoritative). */
public class AttributionSource implements android.os.Parcelable {
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

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(android.os.Parcel dest, int flags) {
        dest.writeInt(uid);
        dest.writeInt(pid);
        dest.writeString(packageName);
        dest.writeString(attributionTag);
    }
    public static final Creator<AttributionSource> CREATOR = new Creator<AttributionSource>() {
        @Override public AttributionSource createFromParcel(android.os.Parcel source) {
            return new AttributionSource(source.readInt(), source.readInt(),
                    source.readString(), source.readString());
        }
        @Override public AttributionSource[] newArray(int size) { return new AttributionSource[size]; }
    };
}
