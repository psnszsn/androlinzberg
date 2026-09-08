package dev.artzig

/** Code for the generated, resource-only APK used by webview-ui-test. */
class StockWebViewTestActivity : android.app.Activity() {
    private lateinit var webView: android.webkit.WebView
    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        webView = android.webkit.WebView(this)
        webView.settings.javaScriptEnabled = true
        val inputStatus = android.widget.TextView(this).apply { text = "Checking WebView input" }
        var verified = false
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                if (verified) return
                verified = true
                webView.post {
                    StockWebViewSmoke.verifyUnhandledInput(this@StockWebViewTestActivity) {
                        inputStatus.text = "WebView input verified"
                    }
                }
            }
        }
        setContentView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(webView, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
            addView(inputStatus, android.widget.LinearLayout.LayoutParams(-1, 24))
        })
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
