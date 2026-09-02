@file:Suppress("DEPRECATION")

package android.webkit

open class SslErrorHandler : android.os.Handler() {
    open fun proceed() = Unit
    open fun cancel() = Unit
}
