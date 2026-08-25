/* artzig stub: DRM is not supported. */
package android.media;
public final class MediaCrypto {
    public MediaCrypto(java.util.UUID uuid, byte[] initData) throws MediaCryptoException {
        throw new MediaCryptoException("DRM not supported");
    }
    public static boolean isCryptoSchemeSupported(java.util.UUID uuid) { return false; }
    public boolean requiresSecureDecoderComponent(String mime) { return false; }
    public void release() {}
}
