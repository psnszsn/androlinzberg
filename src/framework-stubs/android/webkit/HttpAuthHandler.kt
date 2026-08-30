package android.webkit

open class HttpAuthHandler : android.os.Handler() {
    open fun useHttpAuthUsernamePassword(): Boolean = false
    open fun proceed(username: String?, password: String?) = Unit
    open fun cancel() = Unit
}
