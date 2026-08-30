package android.webkit

import android.net.Uri

/** Public AOSP request contract passed to WebViewClient callbacks. */
interface WebResourceRequest {
    fun getUrl(): Uri
    fun isForMainFrame(): Boolean
    fun isRedirect(): Boolean
    fun hasGesture(): Boolean
    fun getMethod(): String
    fun getRequestHeaders(): Map<String, String>
}
