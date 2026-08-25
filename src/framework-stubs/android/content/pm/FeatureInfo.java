/* artzig stub. */
package android.content.pm;
import android.os.Parcel;
import android.os.Parcelable;
public class FeatureInfo implements Parcelable {
    public String name;
    public int version;
    public int reqGlEsVersion;
    public int flags;
    public FeatureInfo() {}
    public String getGlEsVersion() { return "3.2"; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int parcelableFlags) { dest.writeString(name); }
    public static final Parcelable.Creator<FeatureInfo> CREATOR = new Parcelable.Creator<FeatureInfo>() {
        @Override public FeatureInfo createFromParcel(Parcel source) {
            FeatureInfo fi = new FeatureInfo();
            fi.name = source.readString();
            return fi;
        }
        @Override public FeatureInfo[] newArray(int size) { return new FeatureInfo[size]; }
    };
}
