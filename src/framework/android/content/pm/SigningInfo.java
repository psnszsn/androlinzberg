/* artzig shim: app signing info (no signatures on host). */
package android.content.pm;
public final class SigningInfo {
    public SigningInfo() {}
    public boolean hasPastSigningCertificates() { return false; }
    public boolean hasMultipleSigners() { return false; }
    public Signature[] getSigningCertificateHistory() { return new Signature[0]; }
    public Signature[] getApkContentsSigners() { return new Signature[0]; }
}
