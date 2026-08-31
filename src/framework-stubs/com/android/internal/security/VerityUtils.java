/* artzig stub: no fs-verity on this host. */
package com.android.internal.security;
public abstract class VerityUtils {
    public static byte[] getFsverityDigest(String filePath) { return null; }
    public static byte[] generateFsVerityDigest(long fileSize, android.os.incremental.V4Signature.HashingInfo hashingInfo)
            throws java.security.DigestException, java.security.NoSuchAlgorithmException {
        throw new java.security.NoSuchAlgorithmException("no fs-verity on this host");
    }
}
