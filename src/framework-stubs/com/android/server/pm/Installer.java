/* artzig stub: nothing installs through installd here; only the nested
 * exception type PackageManagerException.from() names. */
package com.android.server.pm;
public final class Installer {
    private Installer() {}
    public static class InstallerException extends Exception {
        public InstallerException(String message) { super(message); }
    }
    public byte[] hashSecondaryDexFile(String dexPath, String packageName, int uid,
            String volumeUuid, int flags) throws InstallerException {
        return new byte[0];
    }
}
