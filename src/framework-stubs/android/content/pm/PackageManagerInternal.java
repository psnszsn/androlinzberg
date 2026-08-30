/* artzig stub: the subset of android.content.pm.PackageManagerInternal the
 * reused system services consult through LocalServices. The hosted system
 * process registers an implementation backed by the session's package
 * manager. */
package android.content.pm;
public abstract class PackageManagerInternal {
    /** True when {@code packageName} runs as {@code uid} for {@code userId}. */
    public abstract boolean isSameApp(String packageName, long flags, int uid, int userId);
}
