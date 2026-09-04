/* artzig stub: the real mutable settings record is inseparable from
 * PackageManagerService; PackageStateUnserialized only notifies it of
 * changes. The hosted state record is
 * dev.artzig.system.HostedPackageState. */
package com.android.server.pm;
public class PackageSetting extends com.android.server.utils.WatchableImpl {
    private final com.android.server.pm.pkg.PackageUserStateImpl mUserState =
            new com.android.server.pm.pkg.PackageUserStateImpl();
    private final com.android.server.pm.pkg.PackageStateUnserialized mTransientState =
            new com.android.server.pm.pkg.PackageStateUnserialized(this);
    private int mPrivateFlags;

    public void onChanged() {}
    public com.android.server.pm.pkg.PackageUserStateImpl getOrCreateUserState(int userId) {
        return mUserState;
    }
    public com.android.server.pm.pkg.PackageStateUnserialized getTransientState() {
        return mTransientState;
    }
    public int getPrivateFlags() { return mPrivateFlags; }
    public PackageSetting setPrivateFlags(int privateFlags) {
        mPrivateFlags = privateFlags;
        return this;
    }
    public boolean setMimeGroup(String mimeGroup, android.util.ArraySet<String> mimeTypes) {
        return false;
    }
    public PackageSetting setCategoryOverride(int category) { return this; }
    public PackageSetting setPageSizeAppCompatFlags(int flags) { return this; }
    public PackageSetting setUpdateAvailable(boolean updateAvailable) { return this; }
    public PackageSetting setLoadingProgress(float progress) { return this; }
    public PackageSetting setLoadingCompletedTime(long time) { return this; }
    public PackageSetting setInstallerPackage(String packageName, int uid) { return this; }
    public PackageSetting setUpdateOwnerPackage(String packageName) { return this; }
}
