/* artzig shim */
package android.content.pm;
public class Signature {
    private final byte[] mBytes;
    public Signature(byte[] signature) { mBytes = signature.clone(); }
    public Signature(String text) { mBytes = new byte[0]; }
    public byte[] toByteArray() { return mBytes.clone(); }
    public char[] toChars() { return new char[0]; }
    public String toCharsString() { return ""; }
    @Override public boolean equals(Object obj) {
        return obj instanceof Signature && java.util.Arrays.equals(mBytes, ((Signature) obj).mBytes);
    }
    @Override public int hashCode() { return java.util.Arrays.hashCode(mBytes); }
}
