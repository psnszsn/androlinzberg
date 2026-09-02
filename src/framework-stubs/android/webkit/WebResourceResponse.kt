@file:Suppress("DEPRECATION")

package android.webkit

import java.io.InputStream
import java.io.StringBufferInputStream

/**
 * AOSP WebResourceResponse data object. This is independent of a WebView
 * provider, so the hosted framework preserves its validation and mutation
 * behavior directly.
 */
class WebResourceResponse(
    private var mimeType: String?,
    private var encoding: String?,
    data: InputStream?,
) {
    private var immutable: Boolean = false
    private var statusCode: Int = 0
    private var reasonPhrase: String? = null
    private var responseHeaders: Map<String, String>? = null
    private var inputStream: InputStream? = null

    init {
        setData(data)
    }

    constructor(
        mimeType: String?,
        encoding: String?,
        statusCode: Int,
        reasonPhrase: String,
        responseHeaders: Map<String, String>?,
        data: InputStream?,
    ) : this(mimeType, encoding, data) {
        setStatusCodeAndReasonPhrase(statusCode, reasonPhrase)
        setResponseHeaders(responseHeaders)
    }

    fun setMimeType(value: String?) {
        checkMutable()
        mimeType = value
    }

    fun getMimeType(): String? = mimeType

    fun setEncoding(value: String?) {
        checkMutable()
        encoding = value
    }

    fun getEncoding(): String? = encoding

    fun setStatusCodeAndReasonPhrase(statusCode: Int, reasonPhrase: String) {
        checkMutable()
        require(statusCode >= 100) { "statusCode can't be less than 100." }
        require(statusCode <= 599) { "statusCode can't be greater than 599." }
        require(statusCode !in 300..399) {
            "statusCode can't be in the [300, 399] range."
        }
        require(reasonPhrase.isNotBlank()) { "reasonPhrase can't be empty." }
        require(reasonPhrase.all { it.code <= 0x7f }) {
            "reasonPhrase can't contain non-ASCII characters."
        }
        this.statusCode = statusCode
        this.reasonPhrase = reasonPhrase
    }

    fun getStatusCode(): Int = statusCode

    fun getReasonPhrase(): String? = reasonPhrase

    fun setResponseHeaders(headers: Map<String, String>?) {
        checkMutable()
        responseHeaders = headers
    }

    fun getResponseHeaders(): Map<String, String>? = responseHeaders

    fun setData(data: InputStream?) {
        checkMutable()
        require(data !is StringBufferInputStream) {
            "StringBufferInputStream is deprecated and must not be passed to a WebResourceResponse"
        }
        inputStream = data
    }

    fun getData(): InputStream? = inputStream

    private fun checkMutable() {
        check(!immutable) { "This WebResourceResponse instance is immutable" }
    }
}
