/* artzig shim: android.provider.FontRequest. */
package android.provider;
import java.util.Collections;
import java.util.List;
public final class FontRequest {
    private final String mProviderAuthority, mProviderPackage, mQuery;
    private final List<List<byte[]>> mCertificates;
    public FontRequest(String providerAuthority, String providerPackage, String query) {
        this(providerAuthority, providerPackage, query, Collections.emptyList());
    }
    public FontRequest(String providerAuthority, String providerPackage, String query,
            List<List<byte[]>> certificates) {
        mProviderAuthority = providerAuthority; mProviderPackage = providerPackage;
        mQuery = query; mCertificates = certificates;
    }
    public String getProviderAuthority() { return mProviderAuthority; }
    public String getProviderPackage() { return mProviderPackage; }
    public String getQuery() { return mQuery; }
    public List<List<byte[]>> getCertificates() { return mCertificates; }
}
