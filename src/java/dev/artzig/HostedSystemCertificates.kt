package dev.artzig

import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale

/** Supplies the host trust bundle as Android's system partition certificate directory.
 * Source note: SystemCertificateSource/DirectoryCertificateSource expect individual X.509
 * files named with Conscrypt's X509_NAME_hash_old subject hash and a collision index.
 * The native launcher supplies a private ANDROID_ROOT overlay; the stock source, user
 * removal policy, app trust anchors, pinning and chain validation remain unchanged. */
internal object HostedSystemCertificates {
    private var installed = false

    @Synchronized fun install() {
        if (installed || !java.lang.Boolean.getBoolean("artzig.system.root.overlay")) return
        val directory = File(checkNotNull(System.getenv("ANDROID_ROOT")), "etc/security/cacerts")
        check(directory.isDirectory && directory.list()!!.isEmpty()) { "System CA directory is not empty" }
        val bundle = File(checkNotNull(System.getProperty("artzig.ca.bundle")))
        // Linux bundles may have certificate labels/comments between PEM blocks; Conscrypt's
        // generateCertificates stops at those labels. Parse each block with its stock factory.
        val factory = CertificateFactory.getInstance("X.509")
        val certificates = ArrayList<X509Certificate>()
        var pem: StringBuilder? = null
        bundle.useLines(Charsets.US_ASCII) { lines ->
            lines.forEach { line ->
                if (line == "-----BEGIN CERTIFICATE-----") {
                    check(pem == null) { "Nested certificate in host CA bundle: $bundle" }
                    pem = StringBuilder()
                }
                pem?.let { block ->
                    block.append(line).append('\n')
                    if (line == "-----END CERTIFICATE-----") {
                        certificates += factory.generateCertificate(
                            block.toString().byteInputStream(Charsets.US_ASCII),
                        ) as X509Certificate
                        pem = null
                    }
                }
            }
        }
        check(pem == null) { "Incomplete certificate in host CA bundle: $bundle" }
        check(certificates.isNotEmpty()) { "Host CA bundle has no certificates: $bundle" }
        val indexes = HashMap<String, Int>()
        for (certificate in certificates) {
            val x509 = certificate
            val digest = MessageDigest.getInstance("MD5").digest(x509.subjectX500Principal.encoded)
            val hash = (digest[0].toInt() and 255) or ((digest[1].toInt() and 255) shl 8) or
                ((digest[2].toInt() and 255) shl 16) or ((digest[3].toInt() and 255) shl 24)
            val name = String.format(Locale.ROOT, "%08x", hash)
            val index = indexes[name] ?: 0
            indexes[name] = index + 1
            File(directory, "$name.$index").writeBytes(x509.encoded)
        }
        installed = true
    }
}
