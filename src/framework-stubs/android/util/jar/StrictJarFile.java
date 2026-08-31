/* artzig stub: v1 (JAR) APK signature verification is unsupported on this
 * host; targetSdk >= 30 requires v2+ anyway, and the verifier only reaches
 * this class for v1-only APKs. */
package android.util.jar;
public final class StrictJarFile {
    public StrictJarFile(String name, boolean verify, boolean signatureSchemeRollbackProtectionsEnforced)
            throws java.io.IOException {
        throw new java.io.IOException("v1-only APK signatures are unsupported on this host: " + name);
    }
    public java.util.Iterator<java.util.zip.ZipEntry> iterator() throws java.io.IOException {
        throw new IllegalStateException();
    }
    public java.util.zip.ZipEntry findEntry(String name) { throw new IllegalStateException(); }
    public java.security.cert.Certificate[][] getCertificateChains(java.util.zip.ZipEntry ze) {
        throw new IllegalStateException();
    }
    public java.io.InputStream getInputStream(java.util.zip.ZipEntry ze) { throw new IllegalStateException(); }
    public void close() throws java.io.IOException {}
}
