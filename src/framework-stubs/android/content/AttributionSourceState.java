/* artzig stub: android.content.AttributionSourceState — the parcel form of
 * AttributionSource (the real one is aidl-generated; android.permission is
 * not vendored). Identity on this host is the broker uid, so the state only
 * round-trips uid/package/tag. */
package android.content;
import android.os.Parcel;
import android.os.Parcelable;
public class AttributionSourceState implements Parcelable {
    public int uid = -1;
    public int pid = -1;
    public String packageName;
    public String attributionTag;
    public AttributionSourceState() {}
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(uid);
        dest.writeInt(pid);
        dest.writeString(packageName);
        dest.writeString(attributionTag);
    }
    public static final Creator<AttributionSourceState> CREATOR = new Creator<AttributionSourceState>() {
        @Override public AttributionSourceState createFromParcel(Parcel source) {
            AttributionSourceState state = new AttributionSourceState();
            state.uid = source.readInt();
            state.pid = source.readInt();
            state.packageName = source.readString();
            state.attributionTag = source.readString();
            return state;
        }
        @Override public AttributionSourceState[] newArray(int size) { return new AttributionSourceState[size]; }
    };
}
