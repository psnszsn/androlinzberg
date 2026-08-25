/* artzig stub (auto-generated placeholder; members added on demand). */
package android.hardware.lights;
public class Light  implements android.os.Parcelable {
    public int getId() { return 0; }
    public int getType() { return 0; }
    public int getOrdinal() { return 0; }    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel dest, int flags) {}
    public static final android.os.Parcelable.Creator<Light> CREATOR =
            new android.os.Parcelable.Creator<Light>() {
        public Light createFromParcel(android.os.Parcel in) { return new Light(); }
        public Light[] newArray(int size) { return new Light[size]; }
    };
}
