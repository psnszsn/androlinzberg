/* artzig hosted WebView state/callback adapter. Chromium's WebView provider is not available;
 * public lifecycle and callback behavior follows AOSP WebView while document loading is reduced
 * to an in-process navigation seam. */
package android.webkit;
import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WebView extends android.view.ViewGroup {
    private final WebSettings mSettings;
    private final HostedWebViewProvider mProvider;
    private final Map<String, Object> mJavascriptInterfaces = new HashMap<>();
    private WebViewClient mWebViewClient = new WebViewClient();
    private WebChromeClient mWebChromeClient;
    private String mUrl;
    private boolean mDestroyed;

    public WebView(Context context) { this(context, null); }
    public WebView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public WebView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }
    public WebView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mSettings = new WebSettings(context);
        mProvider = HostedWebViewProviders.create(this);
        setWillNotDraw(false);
    }
    @Deprecated
    public WebView(Context context, AttributeSet attrs, int defStyleAttr,
            boolean privateBrowsing) {
        this(context, attrs, defStyleAttr, 0);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {}
    public float getScale() { return 1.0f; }
    public int getContentHeight() { return 0; }
    public static String findAddress(String addr) { return null; }

    public WebSettings getSettings() { return mSettings; }
    public void setWebViewClient(WebViewClient client) {
        mWebViewClient = client != null ? client : new WebViewClient();
    }
    public WebViewClient getWebViewClient() { return mWebViewClient; }
    public void setWebChromeClient(WebChromeClient client) { mWebChromeClient = client; }
    public WebChromeClient getWebChromeClient() { return mWebChromeClient; }
    public String getUrl() { return mUrl; }

    public void loadUrl(String url) {
        mProvider.loadUrl(url != null ? url : "about:blank", java.util.Collections.emptyMap());
    }
    public void loadUrl(String url, Map<String, String> additionalHttpHeaders) {
        mProvider.loadUrl(url != null ? url : "about:blank",
                additionalHttpHeaders != null ? additionalHttpHeaders
                        : java.util.Collections.emptyMap());
    }
    public void loadData(String data, String mimeType, String encoding) {
        String type = mimeType != null ? mimeType : "text/html";
        String payload = Base64.encodeToString(
                (data != null ? data : "").getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        loadUrl("data:" + type + ";base64," + payload);
    }
    public void loadDataWithBaseURL(String baseUrl, String data, String mimeType,
            String encoding, String historyUrl) {
        // AOSP delegates base-URL document loading to the provider. The hosted
        // provider currently preserves the document and callback behavior; it
        // cannot synthesize Chromium's unreachable internal base-URL loader.
        loadData(data, mimeType, encoding);
    }

    public void evaluateJavascript(String script, ValueCallback<String> resultCallback) {
        mProvider.evaluateJavascript(script != null ? script : "", resultCallback);
    }
    public void addJavascriptInterface(Object object, String name) {
        if (object != null && name != null) mJavascriptInterfaces.put(name, object);
    }
    public void removeJavascriptInterface(String name) { mJavascriptInterfaces.remove(name); }
    public void clearCache(boolean includeDiskFiles) {}
    public void clearHistory() {}
    public void stopLoading() { mProvider.stopLoading(); }
    public void reload() { if (mUrl != null) loadUrl(mUrl); }
    public boolean canGoBack() { return false; }
    public void goBack() {}
    public void onPause() {}
    public void onResume() {}
    public void pauseTimers() {}
    public void resumeTimers() {}
    public void destroy() {
        mDestroyed = true;
        mProvider.destroy();
        mJavascriptInterfaces.clear();
        removeAllViews();
    }

    /* Provider callbacks. Package-private so the host adapter owns callback
     * ordering without exposing another Android-facing API. */
    void hostedOnPageStarted(String url) {
        if (mDestroyed) return;
        mUrl = url;
        mWebViewClient.onPageStarted(this, url, null);
    }
    void hostedOnProgressChanged(int progress) {
        if (!mDestroyed && mWebChromeClient != null) {
            mWebChromeClient.onProgressChanged(this, progress);
        }
    }
    void hostedOnPageCommitVisible(String url) {
        if (mDestroyed) return;
        mUrl = url;
        mWebViewClient.onPageCommitVisible(this, url);
        invalidate();
    }
    void hostedOnPageFinished(String url) {
        if (mDestroyed) return;
        mUrl = url;
        mWebViewClient.onPageFinished(this, url);
        invalidate();
    }
    void hostedOnReceivedError(int errorCode, String description, String failingUrl) {
        if (!mDestroyed) {
            mWebViewClient.onReceivedError(this, errorCode, description, failingUrl);
        }
    }

    public void postVisualStateCallback(long requestId, VisualStateCallback callback) {
        if (callback != null) callback.onComplete(requestId);
    }
    public static abstract class VisualStateCallback {
        public abstract void onComplete(long requestId);
    }
    public static class WebViewTransport {
        private WebView mWebView;
        public synchronized void setWebView(WebView webView) { mWebView = webView; }
        public synchronized WebView getWebView() { return mWebView; }
    }

    public static void setDataDirectorySuffix(String suffix) {}
    public static ClassLoader getWebViewClassLoader() { return WebView.class.getClassLoader(); }
    public Looper getWebViewLooper() { return Looper.myLooper(); }
    public static android.content.pm.PackageInfo getCurrentWebViewPackage() { return null; }
    public static Uri getSafeBrowsingPrivacyPolicyUrl() {
        return Uri.parse("https://www.google.com/chrome/browser/privacy/whitepaper.html#safe-browsing");
    }
    public static void startSafeBrowsing(Context context, ValueCallback<Boolean> callback) {
        if (callback != null) callback.onReceiveValue(Boolean.TRUE);
    }
    @Deprecated
    public static void setSafeBrowsingWhitelist(List<String> hosts,
            ValueCallback<Boolean> callback) {
        if (callback != null) callback.onReceiveValue(Boolean.TRUE);
    }
}
