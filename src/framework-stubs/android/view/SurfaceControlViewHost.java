/* artzig stub. */
package android.view;
public class SurfaceControlViewHost {
    public static final class SurfacePackage implements android.os.Parcelable {
        public SurfaceControl getSurfaceControl() { return null; }
        public void release() {}
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {}
        public static final Creator<SurfacePackage> CREATOR = new Creator<SurfacePackage>() {
            public SurfacePackage createFromParcel(android.os.Parcel in) { return null; }
            public SurfacePackage[] newArray(int size) { return new SurfacePackage[size]; }
        };
    }
    public View getView() { return null; }
    public void release() {}
}
