/* artzig shim. */
package android.os;
public final class PersistableBundle extends BaseBundle implements Parcelable {
    public static final PersistableBundle EMPTY = new PersistableBundle();
    public PersistableBundle() { super(); }
    public PersistableBundle(Bundle b) { super(); }
    public PersistableBundle(PersistableBundle b) { super(b); }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) { dest.writePersistableBundle(this); }
    public static final Creator<PersistableBundle> CREATOR = new Creator<PersistableBundle>() {
        public PersistableBundle createFromParcel(Parcel in) { return in.readPersistableBundle(); }
        public PersistableBundle[] newArray(int size) { return new PersistableBundle[size]; }
    };
    public boolean isParcelled() { return false; }
    public void dumpDebug(android.util.proto.ProtoOutputStream proto, long fieldId) {}
    /** AOSP persists bundles as XML; IntentFilter extras are not persisted on
     * this host (written and read as an empty element). */
    public void saveToXml(org.xmlpull.v1.XmlSerializer out)
            throws java.io.IOException, org.xmlpull.v1.XmlPullParserException {}
    public static PersistableBundle restoreFromXml(org.xmlpull.v1.XmlPullParser in)
            throws java.io.IOException, org.xmlpull.v1.XmlPullParserException {
        return new PersistableBundle();
    }
}
