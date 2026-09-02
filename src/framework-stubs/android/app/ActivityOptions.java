/* artzig stub (auto-generated placeholder; members added on demand). */
package android.app;
public class ActivityOptions {
    /** AOSP ActivityOptions.BackgroundActivityStartMode and its values. */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface BackgroundActivityStartMode {}
    public static final int MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED = 0;
    public static final int MODE_BACKGROUND_ACTIVITY_START_ALLOWED = 1;
    public static final int MODE_BACKGROUND_ACTIVITY_START_DENIED = 2;
    public static final int MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS = 3;
    public static final int MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE = 4;
    public static final int MODE_BACKGROUND_ACTIVITY_START_COMPAT = -1;
    public static ActivityOptions makeBasic() { return new ActivityOptions(); }
    /** AOSP: options ride a Bundle; this host keeps no launch display/task state in them. */
    public static ActivityOptions fromBundle(android.os.Bundle bOptions) { return bOptions != null ? new ActivityOptions() : null; }
    public static boolean hasLaunchTargetContainer(ActivityOptions options) { return false; }
    public int getLaunchTaskId() { return -1; }
    public int getLaunchDisplayId() { return android.view.Display.INVALID_DISPLAY; }
    public ActivityOptions setLaunchDisplayId(int launchDisplayId) { return this; }
    /** AOSP ActivityOptions.LaunchCookie: an opaque binder token. */
    public static final class LaunchCookie implements android.os.Parcelable {
        public final android.os.IBinder binder;
        public LaunchCookie() { this(new android.os.Binder()); }
        public LaunchCookie(android.os.IBinder binder) { this.binder = binder; }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(android.os.Parcel dest, int flags) { dest.writeStrongBinder(binder); }
        public static final Creator<LaunchCookie> CREATOR = new Creator<LaunchCookie>() {
            @Override public LaunchCookie createFromParcel(android.os.Parcel in) { return new LaunchCookie(in.readStrongBinder()); }
            @Override public LaunchCookie[] newArray(int size) { return new LaunchCookie[size]; }
        };
    }
    /** AOSP ActivityOptions.SceneTransitionInfo (shared-element transition data; none on this host). */
    public static final class SceneTransitionInfo implements android.os.Parcelable {
        public SceneTransitionInfo() {}
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {}
        public static final Creator<SceneTransitionInfo> CREATOR = new Creator<SceneTransitionInfo>() {
            @Override public SceneTransitionInfo createFromParcel(android.os.Parcel in) { return new SceneTransitionInfo(); }
            @Override public SceneTransitionInfo[] newArray(int size) { return new SceneTransitionInfo[size]; }
        };
    }
    public ActivityOptions setPendingIntentBackgroundActivityStartMode(int mode) { return this; }
    public android.os.Bundle toBundle() { return new android.os.Bundle(); }
}
