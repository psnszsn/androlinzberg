/* artzig stub of AOSP android.telecom.PhoneAccountHandle: the telecom stack
 * (its Log/Session plumbing) does not exist on this host. Value shape only. */
package android.telecom;
import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;
public class PhoneAccountHandle implements Parcelable {
    private final ComponentName mComponentName;
    private final String mId;
    public PhoneAccountHandle(ComponentName componentName, String id) {
        mComponentName = componentName;
        mId = id;
    }
    public ComponentName getComponentName() { return mComponentName; }
    public String getId() { return mId; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel out, int flags) {
        mComponentName.writeToParcel(out, flags);
        out.writeString(mId);
    }
    public static final Creator<PhoneAccountHandle> CREATOR = new Creator<PhoneAccountHandle>() {
        @Override public PhoneAccountHandle createFromParcel(Parcel in) {
            return new PhoneAccountHandle(ComponentName.CREATOR.createFromParcel(in), in.readString());
        }
        @Override public PhoneAccountHandle[] newArray(int size) { return new PhoneAccountHandle[size]; }
    };
}
