/* artzig shim. */
package android.os;
public final class PersistableBundle extends BaseBundle implements Parcelable {
    public static final PersistableBundle EMPTY = new PersistableBundle();
    public PersistableBundle() { super(); }
    public PersistableBundle(Bundle b) { super(); }
    public PersistableBundle(PersistableBundle b) { super(b); }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) { dest.writeInt(0); }
    public static final Creator<PersistableBundle> CREATOR = new Creator<PersistableBundle>() {
        public PersistableBundle createFromParcel(Parcel in) { in.readInt(); return new PersistableBundle(); }
        public PersistableBundle[] newArray(int size) { return new PersistableBundle[size]; }
    };
    public boolean isParcelled() { return false; }
    public void dumpDebug(android.util.proto.ProtoOutputStream proto, long fieldId) {}
}
