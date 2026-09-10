package dev.artzig

/** Code for the generated, resource-only APK used by webview-ui-test. */
class StockWebViewTestActivity : android.app.Activity() {
    private lateinit var webView: android.webkit.WebView
    private val tracePath = System.getenv("AZK_WEBVIEW_TRACE_OUT")
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
            if (tracePath == null) {
                addView(inputStatus, android.widget.LinearLayout.LayoutParams(-1, 24))
            } else {
                addView(android.widget.LinearLayout(this@StockWebViewTestActivity).apply {
                    addView(inputStatus, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                    addView(android.widget.Button(this@StockWebViewTestActivity).apply {
                        val tracing = android.webkit.TracingController.getInstance()
                        text = if (tracing.isTracing) "Save rendering trace" else "Start rendering trace"
                        setOnClickListener {
                            if (!tracing.isTracing) {
                                tracing.start(android.webkit.TracingConfig.Builder()
                                    .addCategories("media", "viz", "cc", "gpu", "disabled-by-default-viz.debug.overlay_planes")
                                    .setTracingMode(android.webkit.TracingConfig.RECORD_UNTIL_FULL).build())
                                text = "Save rendering trace"
                                return@setOnClickListener
                            }
                            isEnabled = false
                            val file = java.io.FileOutputStream(tracePath)
                            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                            val output = object : java.io.OutputStream() {
                                override fun write(value: Int) = file.write(value)
                                override fun write(bytes: ByteArray, offset: Int, length: Int) = file.write(bytes, offset, length)
                                override fun close() {
                                    try { file.close() } finally { executor.shutdown() }
                                    runOnUiThread { inputStatus.text = "Rendering trace saved" }
                                }
                            }
                            try {
                                check(tracing.stop(output, executor))
                            } catch (failure: Throwable) {
                                try { file.close() } finally { executor.shutdown() }
                                throw failure
                            }
                        }
                    })
                }, android.widget.LinearLayout.LayoutParams(-1, -2))
            }
        })
        val html = assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        webView.loadDataWithBaseURL("https://webview.example/", html, "text/html", "UTF-8", null)
    }
    override fun onDestroy() {
        if (tracePath != null && !isChangingConfigurations && android.webkit.TracingController.getInstance().isTracing) {
            android.webkit.TracingController.getInstance().stop(null, java.util.concurrent.Executor { it.run() })
        }
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
