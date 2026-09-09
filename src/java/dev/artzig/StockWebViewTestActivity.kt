package dev.artzig

/** Code for the generated, resource-only APK used by webview-ui-test. */
class StockWebViewTestActivity : android.app.Activity() {
    private lateinit var webView: android.webkit.WebView
    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        webView = android.webkit.WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
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
        val html = assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        webView.loadDataWithBaseURL("https://webview.example/", html, "text/html", "UTF-8", null)
    }
    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
