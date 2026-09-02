@file:Suppress("OVERRIDE_DEPRECATION")

package android.webkit

import java.net.URI
import java.util.IdentityHashMap
import java.util.Locale

/**
 * Provider-independent host adaptation of AOSP CookieManager. The platform
 * implementation delegates storage to the updatable WebView provider, which
 * is unavailable in-process here, so this class owns the cookie jar used by
 * WebViewClient interception and the Chromium host adapter.
 */
abstract class CookieManager protected constructor() {
    abstract fun setAcceptCookie(accept: Boolean)
    abstract fun acceptCookie(): Boolean
    abstract fun setAcceptThirdPartyCookies(webView: WebView, accept: Boolean)
    abstract fun acceptThirdPartyCookies(webView: WebView): Boolean
    abstract fun setCookie(url: String, value: String)
    abstract fun setCookie(url: String, value: String, callback: ValueCallback<Boolean>?)
    abstract fun getCookie(url: String): String?
    @Deprecated("Use removeSessionCookies")
    abstract fun removeSessionCookie()
    abstract fun removeSessionCookies(callback: ValueCallback<Boolean>?)
    @Deprecated("Use removeAllCookies")
    abstract fun removeAllCookie()
    abstract fun removeAllCookies(callback: ValueCallback<Boolean>?)
    abstract fun hasCookies(): Boolean
    @Deprecated("Expired cookies are removed automatically")
    abstract fun removeExpiredCookie()
    abstract fun flush()

    companion object {
        private val instance: CookieManager = HostedCookieManager()
        @Volatile private var acceptFileSchemeCookies = false

        @JvmStatic fun getInstance(): CookieManager = instance
        @JvmStatic fun allowFileSchemeCookies(): Boolean = acceptFileSchemeCookies
        @JvmStatic fun setAcceptFileSchemeCookies(accept: Boolean) {
            acceptFileSchemeCookies = accept
        }
    }
}

private class HostedCookieManager : CookieManager() {
    private data class Cookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val secure: Boolean,
        val session: Boolean,
    )

    private val cookies = LinkedHashMap<String, Cookie>()
    private val thirdParty = IdentityHashMap<WebView, Boolean>()
    private var accepting = true

    @Synchronized
    override fun setAcceptCookie(accept: Boolean) {
        accepting = accept
    }

    @Synchronized
    override fun acceptCookie(): Boolean = accepting

    @Synchronized
    override fun setAcceptThirdPartyCookies(webView: WebView, accept: Boolean) {
        thirdParty[webView] = accept
    }

    @Synchronized
    override fun acceptThirdPartyCookies(webView: WebView): Boolean =
        thirdParty[webView] ?: false

    override fun setCookie(url: String, value: String) {
        setCookie(url, value, null)
    }

    @Synchronized
    override fun setCookie(url: String, value: String, callback: ValueCallback<Boolean>?) {
        if (!accepting) {
            callback?.onReceiveValue(false)
            return
        }
        val uri = parseUri(url)
        val host = uri?.host?.lowercase(Locale.ROOT)
        if (host == null || (uri.scheme == "file" && !allowFileSchemeCookies())) {
            callback?.onReceiveValue(false)
            return
        }
        val cookieHost: String = host
        val parts = value.split(';')
        val nameValue = parts.firstOrNull()?.trim().orEmpty()
        val separator = nameValue.indexOf('=')
        if (separator <= 0) {
            callback?.onReceiveValue(false)
            return
        }
        val name = nameValue.substring(0, separator).trim()
        val cookieValue = nameValue.substring(separator + 1).trim()
        var domain = cookieHost
        var path = defaultPath(uri.rawPath)
        var secure = false
        var session = true
        var delete = cookieValue.isEmpty()
        for (attribute in parts.drop(1)) {
            val attributeName = attribute.substringBefore('=').trim().lowercase(Locale.ROOT)
            val attributeValue = attribute.substringAfter('=', "").trim()
            when (attributeName) {
                "domain" -> if (attributeValue.isNotEmpty()) {
                    domain = attributeValue.trimStart('.').lowercase(Locale.ROOT)
                }
                "path" -> if (attributeValue.startsWith('/')) path = attributeValue
                "secure" -> secure = true
                "expires" -> session = false
                "max-age" -> {
                    session = false
                    if (attributeValue.toLongOrNull()?.let { it <= 0 } == true) delete = true
                }
            }
        }
        if (!domainMatches(cookieHost, domain)) {
            callback?.onReceiveValue(false)
            return
        }
        val key = "$domain\u0000$path\u0000$name"
        if (delete) cookies.remove(key) else {
            cookies[key] = Cookie(name, cookieValue, domain, path, secure, session)
        }
        callback?.onReceiveValue(true)
    }

    @Synchronized
    override fun getCookie(url: String): String? {
        val uri = parseUri(url) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val secureRequest = uri.scheme.equals("https", true)
        return cookies.values
            .asSequence()
            .filter { domainMatches(host, it.domain) }
            .filter { pathMatches(path, it.path) }
            .filter { !it.secure || secureRequest }
            .sortedByDescending { it.path.length }
            .joinToString("; ") { "${it.name}=${it.value}" }
            .takeIf { it.isNotEmpty() }
    }

    @Synchronized
    override fun removeSessionCookie() {
        removeSessionCookies(null)
    }

    @Synchronized
    override fun removeSessionCookies(callback: ValueCallback<Boolean>?) {
        val removed = cookies.entries.removeAll { it.value.session }
        callback?.onReceiveValue(removed)
    }

    @Synchronized
    override fun removeAllCookie() {
        removeAllCookies(null)
    }

    @Synchronized
    override fun removeAllCookies(callback: ValueCallback<Boolean>?) {
        val removed = cookies.isNotEmpty()
        cookies.clear()
        callback?.onReceiveValue(removed)
    }

    @Synchronized
    override fun hasCookies(): Boolean = cookies.isNotEmpty()

    override fun removeExpiredCookie() = Unit
    override fun flush() = Unit

    private fun parseUri(url: String): URI? = try {
        URI(url)
    } catch (_: Throwable) {
        null
    }

    private fun defaultPath(path: String?): String {
        if (path.isNullOrEmpty() || !path.startsWith('/')) return "/"
        val slash = path.lastIndexOf('/')
        return if (slash <= 0) "/" else path.substring(0, slash)
    }

    private fun domainMatches(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    private fun pathMatches(requestPath: String, cookiePath: String): Boolean =
        requestPath == cookiePath ||
            (requestPath.startsWith(cookiePath) &&
                (cookiePath.endsWith('/') || requestPath.getOrNull(cookiePath.length) == '/'))
}
