/* artzig stub (auto-generated placeholder; members added on demand). */
package android.app;
public class ActivityOptions {
    /** Pinned AOSP ActivityOptions animation ABI used by stock TransitionInfo.AnimationOptions.
     * The full class still crosses hosted SplashScreen/window-transition seams, so only these
     * value constants are carried until that client closure can replace this adaptation. */
    public static final int ANIM_CUSTOM = 1;
    public static final int ANIM_SCALE_UP = 2;
    public static final int ANIM_THUMBNAIL_SCALE_UP = 3;
    public static final int ANIM_THUMBNAIL_SCALE_DOWN = 4;
    public static final int ANIM_SCENE_TRANSITION = 5;
    public static final int ANIM_CLIP_REVEAL = 11;
    public static final int ANIM_OPEN_CROSS_PROFILE_APPS = 12;
    public static final int ANIM_FROM_STYLE = 14;

    /** AOSP ActivityOptions.BackgroundActivityStartMode and its values. */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface BackgroundActivityStartMode {}
    public static final int MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED = 0;
    public static final int MODE_BACKGROUND_ACTIVITY_START_ALLOWED = 1;
    public static final int MODE_BACKGROUND_ACTIVITY_START_DENIED = 2;
    public static final int MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS = 3;
    public static final int MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE = 4;
    public static final int MODE_BACKGROUND_ACTIVITY_START_COMPAT = -1;
    public ActivityOptions() {}
    /** AOSP: options ride a Bundle; nothing in it is interpreted on this host. */
    public ActivityOptions(android.os.Bundle opts) {}
    public static ActivityOptions makeBasic() { return new ActivityOptions(); }
    /** No window animations on the host: the transition variants are plain options. */
    public static ActivityOptions makeCustomAnimation(android.content.Context context, int enterResId, int exitResId) { return new ActivityOptions(); }
    @SafeVarargs
    public static ActivityOptions makeSceneTransitionAnimation(Activity activity, android.util.Pair<android.view.View, String>... sharedElements) { return new ActivityOptions(); }
    public ActivityOptions setPendingIntentLaunchFlags(int flags) { return this; }
    public ActivityOptions setCallerDisplayId(int callerDisplayId) { return this; }
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
