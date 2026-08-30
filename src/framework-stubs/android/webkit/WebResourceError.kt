package android.webkit

abstract class WebResourceError {
    abstract fun getErrorCode(): Int
    abstract fun getDescription(): CharSequence
}
