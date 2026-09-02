/* artzig shim. */
package android.window;
public class SplashScreenView extends android.view.View {
    public SplashScreenView(android.content.Context context) { super(context); }
    public void remove() {}
    /** AOSP SplashScreenView.SplashScreenViewParcelable (no starting-window transfer on this host). */
    public static class SplashScreenViewParcelable implements android.os.Parcelable {
        public SplashScreenViewParcelable() {}
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {}
        public static final Creator<SplashScreenViewParcelable> CREATOR = new Creator<SplashScreenViewParcelable>() {
            @Override public SplashScreenViewParcelable createFromParcel(android.os.Parcel in) { return new SplashScreenViewParcelable(); }
            @Override public SplashScreenViewParcelable[] newArray(int size) { return new SplashScreenViewParcelable[size]; }
        };
    }
}
