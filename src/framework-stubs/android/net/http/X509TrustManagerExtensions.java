/* artzig shim: delegate hostname-aware checks to the plain trust manager (conscrypt is on the bootclasspath). */
package android.net.http;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.X509TrustManager;
public class X509TrustManagerExtensions {
    private final X509TrustManager mTm;
    public X509TrustManagerExtensions(X509TrustManager tm) { mTm = tm; }
    public List<X509Certificate> checkServerTrusted(X509Certificate[] chain, String authType,
            String host) throws CertificateException {
        mTm.checkServerTrusted(chain, authType);
        return Arrays.asList(chain);
    }
    public boolean isUserAddedCertificate(X509Certificate cert) { return false; }
    public boolean isSameTrustConfiguration(String hostname1, String hostname2) { return true; }
}
