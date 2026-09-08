package dev.artzig

/** Code for the generated, resource-only APK used by webview-ui-test. */
class StockWebViewTestActivity : android.app.Activity() {
    private lateinit var webView: android.webkit.WebView
    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        webView = android.webkit.WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = android.webkit.WebViewClient()
        setContentView(webView)
        webView.loadDataWithBaseURL("https://webview.example/", """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Stock Android WebView</title>
            <style>body { font: 24px sans-serif; padding: 24px; background: white; color: #16324f; }
            button { font: inherit; padding: 16px 24px; }</style></head>
            <body><h1>Stock Android WebView</h1><p>This page is rendered by the bundled Android Chromium provider.</p>
            <button onclick="this.textContent='Count: '+(++count)">Count: 0</button>
            <script>let count = 0;</script></body></html>
        """.trimIndent(), "text/html", "UTF-8", null)
    }
    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
