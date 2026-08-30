package android.net.http

import java.security.cert.X509Certificate

/** AOSP SslError bit-set behavior over the hosted SslCertificate adaptation. */
class SslError(
    error: Int,
    private val certificate: SslCertificate,
    private val url: String,
) {
    private var errors: Int = 0

    init {
        addError(error)
    }

    constructor(error: Int, certificate: SslCertificate) : this(error, certificate, "")
    constructor(error: Int, certificate: X509Certificate) : this(error, SslCertificate(certificate), "")
    constructor(error: Int, certificate: X509Certificate, url: String) : this(
        error,
        SslCertificate(certificate),
        url,
    )

    fun getCertificate(): SslCertificate = certificate
    fun getUrl(): String = url

    fun addError(error: Int): Boolean {
        if (error !in 0 until SSL_MAX_ERROR) return false
        errors = errors or (1 shl error)
        return true
    }

    fun hasError(error: Int): Boolean =
        error in 0 until SSL_MAX_ERROR && errors and (1 shl error) != 0

    fun getPrimaryError(): Int {
        for (error in SSL_MAX_ERROR - 1 downTo 0) {
            if (hasError(error)) return error
        }
        return -1
    }

    override fun toString(): String =
        "primary error: ${getPrimaryError()} certificate: $certificate on URL: $url"

    companion object {
        const val SSL_NOTYETVALID: Int = 0
        const val SSL_EXPIRED: Int = 1
        const val SSL_IDMISMATCH: Int = 2
        const val SSL_UNTRUSTED: Int = 3
        const val SSL_DATE_INVALID: Int = 4
        const val SSL_INVALID: Int = 5
        const val SSL_MAX_ERROR: Int = 6
    }
}
