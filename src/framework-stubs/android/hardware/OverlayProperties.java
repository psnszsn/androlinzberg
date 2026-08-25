/* artzig stub. */
package android.hardware;
public final class OverlayProperties  implements android.os.Parcelable {
    private OverlayProperties() {}
    public static OverlayProperties getDefault() { return new OverlayProperties(); }
    public boolean isCombinationSupported(int dataspace, int format) { return false; }
    public boolean isMixedColorSpacesSupported() { return false; }
    public LutProperties[] getLutProperties() { return new LutProperties[0]; }
    public static final class LutProperties {}
    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel dest, int flags) {}
    public static final android.os.Parcelable.Creator<OverlayProperties> CREATOR =
            new android.os.Parcelable.Creator<OverlayProperties>() {
        public OverlayProperties createFromParcel(android.os.Parcel in) { return new OverlayProperties(); }
        public OverlayProperties[] newArray(int size) { return new OverlayProperties[size]; }
    };
}
