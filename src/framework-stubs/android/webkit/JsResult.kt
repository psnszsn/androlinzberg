package android.webkit

open class JsResult internal constructor() {
    private var confirmed = false
    private var completed = false

    open fun confirm() {
        confirmed = true
        completed = true
    }

    open fun cancel() {
        confirmed = false
        completed = true
    }

    internal fun isConfirmed(): Boolean = completed && confirmed
}
