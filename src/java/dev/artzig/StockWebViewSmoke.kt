package dev.artzig

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient

/** Runs with AZK_TEST_APK and the same application identity/native loader as APK launch. */
object StockWebViewSmoke {
    @JvmStatic fun main(args: Array<String>) {
        ApkHost.initHeadless()
        val context = android.app.ActivityThread.currentApplication()
        val service = android.webkit.IWebViewUpdateService.Stub.asInterface(
            android.os.ServiceManager.getService(android.content.Context.WEBVIEW_UPDATE_SERVICE))
        val provider = checkNotNull(service.waitForAndGetProvider().packageInfo)
        check(checkNotNull(provider.signatures).isNotEmpty())
        check(android.webkit.WebViewFactoryProvider.isCompatibleImplementationPackage(provider))
        println("StockWebViewSmoke: provider ${provider.packageName} ${provider.versionName}")
        val events = ArrayList<String>()
        var rendererGone = false
        val view = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) { events += "start" }
                override fun onPageFinished(view: WebView?, url: String?) { events += "finish" }
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    check(!detail.didCrash()) { "Chromium renderer crashed" }
                    rendererGone = true
                    return true
                }
            }
        }
        try {
            check(view.settings.javaScriptEnabled && view.settings.domStorageEnabled)
            view.loadDataWithBaseURL("https://webview.example/", "<html><title>Stock provider</title><body>Hello from Chromium</body></html>", "text/html", "UTF-8", null)
            await { events.contains("finish") }
            var result: String? = null
            view.evaluateJavascript("1 + 1") { result = it }
            await { result != null }
            check(result == "2") { "JavaScript result: $result" }
            check(view.title == "Stock provider")
            val cookies = CookieManager.getInstance()
            var cleared = false
            cookies.removeAllCookies { cleared = true }
            await { cleared }
            var accepted: Boolean? = null
            cookies.setCookie("https://webview.example/path/page", "session=abc; Path=/path; Secure") { accepted = it }
            await { accepted != null }
            check(accepted == true)
            check(cookies.getCookie("https://webview.example/path/next") == "session=abc")
            check(cookies.getCookie("http://webview.example/path/next") == null)
            var removed = false
            cookies.removeSessionCookies { removed = true }
            await { removed }
            check(!cookies.hasCookies())
            // Complete renderer lifecycle while AMS is still alive. Returning
            // first races the launcher's session teardown against Chromium's
            // asynchronous unbind callback.
            val renderer = checkNotNull(view.webViewRenderProcess)
            check(renderer.terminate()) { "Chromium renderer did not accept termination" }
            await { rendererGone }
        } finally { view.destroy() }
        println("StockWebViewSmoke: PASS")
    }
    private fun await(condition: () -> Boolean) {
        BoundedLooper.await("Chromium", 20_000, condition)
    }
    private fun drainMainQueue() {
        BoundedLooper.drain()
    }
    fun verifyUnhandledInput(context: android.app.Activity, completed: () -> Unit) {
        val dialog = android.app.Dialog(context)
        val keys = ArrayList<Pair<Int, Int>>()
        var startWhenReady: (() -> Unit)? = null
        val container = object : android.widget.FrameLayout(context) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus) post { startWhenReady?.invoke() }
            }
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                check(event.flags and KeyEvent.FLAG_FALLBACK != 0)
                keys.add(event.keyCode to event.action)
                return true
            }
        }
        val webView = android.webkit.WebView(context, null)
        fun exercise() {
            try {
                // Real WebView is focusable: a first navigation key in touch mode
                // legitimately acquires focus instead of reaching dispatchKeyEvent.
                check(webView.requestFocusFromTouch())
                val client = WebViewClient()
                fun motion(action: Int, source: Int) {
                    val now = android.os.SystemClock.uptimeMillis()
                    val event = MotionEvent.obtain(now, now, action, 1f, 0f, 0).apply { this.source = source }
                    try { client.onUnhandledInputEvent(webView, event) } finally { event.recycle() }
                }
                motion(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_JOYSTICK)
                check(keys.isEmpty()) { "WebView fallback must be asynchronous" }
                drainMainQueue()
                check(keys == listOf(KeyEvent.KEYCODE_DPAD_RIGHT to KeyEvent.ACTION_DOWN)) { "Synthesized key events: $keys" }
                motion(MotionEvent.ACTION_CANCEL, InputDevice.SOURCE_JOYSTICK)
                drainMainQueue()
                check(keys == listOf(KeyEvent.KEYCODE_DPAD_RIGHT to KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_DPAD_RIGHT to KeyEvent.ACTION_UP))
                motion(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_MOUSE_RELATIVE)
                motion(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_TOUCHSCREEN)
                val now = android.os.SystemClock.uptimeMillis()
                client.onUnhandledKeyEvent(webView, KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_A, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK))
                drainMainQueue()
                check(keys.size == 2) { "relative mouse, pointer input, and fallback keys must not be reinjected" }
                motion(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_JOYSTICK)
                drainMainQueue()
                check(keys.size == 3)
                dialog.dismiss()
                drainMainQueue()
                check(keys.size == 3) { "detached root must cancel synthesized repeats and pending keys" }
                var clientCertCanceled = false
                client.onReceivedClientCertRequest(webView, object : android.webkit.ClientCertRequest() {
                    override fun getKeyTypes(): Array<String> = emptyArray()
                    override fun getPrincipals(): Array<java.security.Principal> = emptyArray()
                    override fun getHost(): String = "example.invalid"
                    override fun getPort(): Int = 443
                    override fun proceed(key: java.security.PrivateKey?, chain: Array<java.security.cert.X509Certificate>?) {
                        error("default client certificate handling must not proceed")
                    }
                    override fun ignore() { error("default client certificate handling must cancel") }
                    override fun cancel() { clientCertCanceled = true }
                })
                check(clientCertCanceled)
            } finally {
                dialog.dismiss()
                webView.destroy()
            }
            completed()
        }
        startWhenReady = {
            // Stock ViewRootImpl drops non-pointer input until WMS grants focus.
            if (container.isLaidOut && container.hasWindowFocus()) {
                startWhenReady = null
                exercise()
            }
        }
        // Return to the regular host loop while the dialog's first traversal
        // runs. Blocking/pumping inside onPageFinished would starve rendering.
        container.addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
            override fun onLayoutChange(view: android.view.View, left: Int, top: Int, right: Int,
                bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                if (right <= left || bottom <= top) return
                container.removeOnLayoutChangeListener(this)
                container.post { startWhenReady?.invoke() }
            }
        })
        container.addView(webView)
        dialog.setContentView(container)
        dialog.show()
        checkNotNull(dialog.window).setLayout(200, 200)
    }
}
