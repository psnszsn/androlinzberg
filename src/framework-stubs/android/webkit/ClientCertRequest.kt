package android.webkit

import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate

abstract class ClientCertRequest {
    abstract fun getKeyTypes(): Array<String>
    abstract fun getPrincipals(): Array<Principal>
    abstract fun getHost(): String
    abstract fun getPort(): Int
    abstract fun proceed(privateKey: PrivateKey?, chain: Array<X509Certificate>?)
    abstract fun ignore()
    abstract fun cancel()
}
