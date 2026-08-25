/* artzig stub (auto-generated placeholder; members added on demand). */
package com.android.internal.compat;
public interface IPlatformCompat extends android.os.IInterface {
    @Override default android.os.IBinder asBinder() { return null; }
    default boolean isChangeEnabled(long changeId, android.content.pm.ApplicationInfo appInfo) throws android.os.RemoteException { return true; }
    default boolean isChangeEnabledByPackageName(long changeId, String packageName, int userId) throws android.os.RemoteException { return true; }
    final class Stub {
        public static IPlatformCompat asInterface(android.os.IBinder b) { return null; }
        private Stub() {}
    }
    default boolean isChangeEnabled(long changeId) throws android.os.RemoteException { return true; }
}
