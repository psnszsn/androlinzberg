package android.webkit

abstract class SafeBrowsingResponse {
    abstract fun showInterstitial(allowReporting: Boolean)
    abstract fun proceed(report: Boolean)
    abstract fun backToSafety(report: Boolean)
}
