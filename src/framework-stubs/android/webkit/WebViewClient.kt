package android.webkit

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.view.KeyEvent

/**
 * AOSP WebViewClient callback defaults for the provider-less host. The
 * callback class itself does not require Chromium; WebView owns the unavailable
 * provider interaction. These defaults follow Android 15 WebViewClient.
 */
open class WebViewClient {
    open fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false

    open fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest,
    ): Boolean = shouldOverrideUrlLoading(view, request.getUrl().toString())

    open fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) = Unit
    open fun onPageFinished(view: WebView?, url: String?) = Unit
    open fun onLoadResource(view: WebView?, url: String?) = Unit
    open fun onPageCommitVisible(view: WebView?, url: String?) = Unit

    open fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? = null

    open fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest,
    ): WebResourceResponse? = shouldInterceptRequest(view, request.getUrl().toString())

    open fun onTooManyRedirects(view: WebView?, cancelMsg: Message, continueMsg: Message?) {
        cancelMsg.sendToTarget()
    }

    open fun onReceivedError(
        view: WebView?,
        errorCode: Int,
        description: String?,
        failingUrl: String?,
    ) = Unit

    open fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (request.isForMainFrame()) {
            onReceivedError(
                view,
                error.getErrorCode(),
                error.getDescription().toString(),
                request.getUrl().toString(),
            )
        }
    }

    open fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?,
    ) = Unit

    open fun onFormResubmission(view: WebView?, dontResend: Message, resend: Message?) {
        dontResend.sendToTarget()
    }

    open fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) = Unit

    open fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
        handler.cancel()
    }

    open fun onReceivedClientCertRequest(view: WebView?, request: ClientCertRequest?) = Unit

    open fun onReceivedHttpAuthRequest(
        view: WebView?,
        handler: HttpAuthHandler,
        host: String?,
        realm: String?,
    ) {
        handler.cancel()
    }

    open fun shouldOverrideKeyEvent(view: WebView?, event: KeyEvent?): Boolean = false
    open fun onUnhandledKeyEvent(view: WebView?, event: KeyEvent?) = Unit
    open fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) = Unit

    open fun onReceivedLoginRequest(
        view: WebView?,
        realm: String?,
        account: String?,
        args: String?,
    ) = Unit

    open fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean = false

    open fun onSafeBrowsingHit(
        view: WebView?,
        request: WebResourceRequest?,
        threatType: Int,
        callback: SafeBrowsingResponse,
    ) {
        callback.showInterstitial(true)
    }

    companion object {
        const val ERROR_UNKNOWN: Int = -1
        const val ERROR_HOST_LOOKUP: Int = -2
        const val ERROR_UNSUPPORTED_AUTH_SCHEME: Int = -3
        const val ERROR_AUTHENTICATION: Int = -4
        const val ERROR_PROXY_AUTHENTICATION: Int = -5
        const val ERROR_CONNECT: Int = -6
        const val ERROR_IO: Int = -7
        const val ERROR_TIMEOUT: Int = -8
        const val ERROR_REDIRECT_LOOP: Int = -9
        const val ERROR_UNSUPPORTED_SCHEME: Int = -10
        const val ERROR_FAILED_SSL_HANDSHAKE: Int = -11
        const val ERROR_BAD_URL: Int = -12
        const val ERROR_FILE: Int = -13
        const val ERROR_FILE_NOT_FOUND: Int = -14
        const val ERROR_TOO_MANY_REQUESTS: Int = -15
        const val ERROR_UNSAFE_RESOURCE: Int = -16

        const val SAFE_BROWSING_THREAT_UNKNOWN: Int = 0
        const val SAFE_BROWSING_THREAT_MALWARE: Int = 1
        const val SAFE_BROWSING_THREAT_PHISHING: Int = 2
        const val SAFE_BROWSING_THREAT_UNWANTED_SOFTWARE: Int = 3
        const val SAFE_BROWSING_THREAT_BILLING: Int = 4
    }
}
