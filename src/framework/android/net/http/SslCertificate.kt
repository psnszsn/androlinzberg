package android.net.http

import android.os.Bundle
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hosted adaptation of AOSP SslCertificate. The vendored implementation's UI
 * dependency is available, but its repackaged BouncyCastle X509Name closure is
 * not part of the curated framework build. This version keeps the public data,
 * date, certificate-state, and distinguished-name behavior at that seam.
 */
class SslCertificate private constructor(
    issuedTo: String?,
    issuedBy: String?,
    validNotBefore: Date?,
    validNotAfter: Date?,
    private val x509Certificate: X509Certificate?,
) {
    private val issuedToName = DName(issuedTo.orEmpty())
    private val issuedByName = DName(issuedBy.orEmpty())
    private val validNotBeforeDate = validNotBefore?.let(::copyDate)
    private val validNotAfterDate = validNotAfter?.let(::copyDate)

    constructor(
        issuedTo: String?,
        issuedBy: String?,
        validNotBefore: String?,
        validNotAfter: String?,
    ) : this(
        issuedTo,
        issuedBy,
        parseDate(validNotBefore),
        parseDate(validNotAfter),
        null,
    )

    constructor(
        issuedTo: String?,
        issuedBy: String?,
        validNotBefore: Date?,
        validNotAfter: Date?,
    ) : this(issuedTo, issuedBy, validNotBefore, validNotAfter, null)

    constructor(certificate: X509Certificate) : this(
        certificate.subjectDN.name,
        certificate.issuerDN.name,
        certificate.notBefore,
        certificate.notAfter,
        certificate,
    )

    fun getValidNotBeforeDate(): Date? = validNotBeforeDate?.let(::copyDate)
    fun getValidNotBefore(): String = formatDate(validNotBeforeDate)
    fun getValidNotAfterDate(): Date? = validNotAfterDate?.let(::copyDate)
    fun getValidNotAfter(): String = formatDate(validNotAfterDate)
    fun getIssuedTo(): DName = issuedToName
    fun getIssuedBy(): DName = issuedByName
    fun getX509Certificate(): X509Certificate? = x509Certificate

    override fun toString(): String =
        "Issued to: $issuedToName;\nIssued by: $issuedByName;\n" +
            "Valid not before: ${getValidNotBefore()};\nValid not after: ${getValidNotAfter()}"

    inner class DName(private val distinguishedName: String) {
        private val attributes: Map<String, String> = parseDistinguishedName(distinguishedName)

        fun getDName(): String = distinguishedName
        fun getCName(): String = attributes["CN"].orEmpty()
        fun getOName(): String = attributes["O"].orEmpty()
        fun getUName(): String = attributes["OU"].orEmpty()

        override fun toString(): String = distinguishedName
    }

    companion object {
        private const val ISSUED_TO = "issued-to"
        private const val ISSUED_BY = "issued-by"
        private const val VALID_NOT_BEFORE = "valid-not-before"
        private const val VALID_NOT_AFTER = "valid-not-after"
        private const val X509_CERTIFICATE = "x509-certificate"
        private const val ISO_8601_DATE_FORMAT = "yyyy-MM-dd HH:mm:ssZ"

        @JvmStatic
        fun saveState(certificate: SslCertificate?): Bundle? {
            if (certificate == null) return null
            return Bundle().apply {
                putString(ISSUED_TO, certificate.getIssuedTo().getDName())
                putString(ISSUED_BY, certificate.getIssuedBy().getDName())
                putString(VALID_NOT_BEFORE, certificate.getValidNotBefore())
                putString(VALID_NOT_AFTER, certificate.getValidNotAfter())
                certificate.x509Certificate?.let {
                    runCatching { putByteArray(X509_CERTIFICATE, it.encoded) }
                }
            }
        }

        @JvmStatic
        fun restoreState(bundle: Bundle?): SslCertificate? {
            if (bundle == null) return null
            val certificate = bundle.getByteArray(X509_CERTIFICATE)?.let { encoded ->
                runCatching {
                    CertificateFactory.getInstance("X.509")
                        .generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate
                }.getOrNull()
            }
            return SslCertificate(
                bundle.getString(ISSUED_TO),
                bundle.getString(ISSUED_BY),
                parseDate(bundle.getString(VALID_NOT_BEFORE)),
                parseDate(bundle.getString(VALID_NOT_AFTER)),
                certificate,
            )
        }

        private fun copyDate(date: Date): Date = Date(date.time)

        private fun formatDate(date: Date?): String =
            date?.let { dateFormat().format(it) }.orEmpty()

        private fun parseDate(value: String?): Date? =
            value?.takeIf(String::isNotEmpty)?.let { runCatching { dateFormat().parse(it) }.getOrNull() }

        private fun dateFormat(): SimpleDateFormat =
            SimpleDateFormat(ISO_8601_DATE_FORMAT, Locale.US)

        private fun parseDistinguishedName(value: String): Map<String, String> {
            val result = linkedMapOf<String, String>()
            splitDistinguishedName(value).forEach { part ->
                val separator = part.indexOf('=')
                if (separator > 0) {
                    val key = part.substring(0, separator).trim().uppercase(Locale.US)
                    val parsedValue = part.substring(separator + 1).trim()
                        .removeSurrounding("\"")
                        .replace("\\,", ",")
                        .replace("\\=", "=")
                    result.putIfAbsent(key, parsedValue)
                }
            }
            return result
        }

        private fun splitDistinguishedName(value: String): List<String> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var escaped = false
            value.forEach { character ->
                when {
                    escaped -> {
                        current.append('\\').append(character)
                        escaped = false
                    }
                    character == '\\' -> escaped = true
                    character == ',' -> {
                        parts += current.toString()
                        current.setLength(0)
                    }
                    else -> current.append(character)
                }
            }
            if (escaped) current.append('\\')
            parts += current.toString()
            return parts
        }
    }
}
