package android.webkit

import android.content.Context

/** Stateful public WebSettings contract for the hosted WebView adapter. */
open class WebSettings internal constructor(context: Context) {
    private var javaScriptEnabled = false
    private var javaScriptCanOpenWindowsAutomatically = false
    private var domStorageEnabled = false
    private var allowContentAccess = true
    private var allowFileAccess = true
    private var blockNetworkImage = false
    private var blockNetworkLoads = false
    private var cacheMode = LOAD_DEFAULT
    private var geolocationEnabled = true
    private var loadWithOverviewMode = false
    private var supportMultipleWindows = false
    private var useWideViewPort = false
    private var safeBrowsingEnabled = true
    private var offscreenPreRaster = false
    private var disabledActionModeMenuItems = MENU_ITEM_NONE
    private var forceDark = FORCE_DARK_AUTO
    private var userAgentString = getDefaultUserAgent(context)

    open fun setJavaScriptEnabled(value: Boolean) { javaScriptEnabled = value }
    open fun getJavaScriptEnabled(): Boolean = javaScriptEnabled
    open fun setJavaScriptCanOpenWindowsAutomatically(value: Boolean) {
        javaScriptCanOpenWindowsAutomatically = value
    }
    open fun getJavaScriptCanOpenWindowsAutomatically(): Boolean =
        javaScriptCanOpenWindowsAutomatically
    open fun setDomStorageEnabled(value: Boolean) { domStorageEnabled = value }
    open fun getDomStorageEnabled(): Boolean = domStorageEnabled
    open fun setAllowContentAccess(value: Boolean) { allowContentAccess = value }
    open fun getAllowContentAccess(): Boolean = allowContentAccess
    open fun setAllowFileAccess(value: Boolean) { allowFileAccess = value }
    open fun getAllowFileAccess(): Boolean = allowFileAccess
    open fun setBlockNetworkImage(value: Boolean) { blockNetworkImage = value }
    open fun getBlockNetworkImage(): Boolean = blockNetworkImage
    open fun setBlockNetworkLoads(value: Boolean) { blockNetworkLoads = value }
    open fun getBlockNetworkLoads(): Boolean = blockNetworkLoads
    open fun setCacheMode(value: Int) { cacheMode = value }
    open fun getCacheMode(): Int = cacheMode
    open fun setGeolocationEnabled(value: Boolean) { geolocationEnabled = value }
    open fun getGeolocationEnabled(): Boolean = geolocationEnabled
    open fun setLoadWithOverviewMode(value: Boolean) { loadWithOverviewMode = value }
    open fun getLoadWithOverviewMode(): Boolean = loadWithOverviewMode
    open fun setSupportMultipleWindows(value: Boolean) { supportMultipleWindows = value }
    open fun supportMultipleWindows(): Boolean = supportMultipleWindows
    open fun setUseWideViewPort(value: Boolean) { useWideViewPort = value }
    open fun getUseWideViewPort(): Boolean = useWideViewPort
    open fun setSafeBrowsingEnabled(value: Boolean) { safeBrowsingEnabled = value }
    open fun getSafeBrowsingEnabled(): Boolean = safeBrowsingEnabled
    open fun setOffscreenPreRaster(value: Boolean) { offscreenPreRaster = value }
    open fun getOffscreenPreRaster(): Boolean = offscreenPreRaster
    open fun setDisabledActionModeMenuItems(value: Int) { disabledActionModeMenuItems = value }
    open fun getDisabledActionModeMenuItems(): Int = disabledActionModeMenuItems
    open fun setForceDark(value: Int) { forceDark = value }
    open fun getForceDark(): Int = forceDark
    open fun setUserAgentString(value: String?) {
        userAgentString = value ?: DEFAULT_USER_AGENT
    }
    open fun getUserAgentString(): String = userAgentString

    companion object {
        const val LOAD_DEFAULT = -1
        const val LOAD_NORMAL = 0
        const val LOAD_CACHE_ELSE_NETWORK = 1
        const val LOAD_NO_CACHE = 2
        const val LOAD_CACHE_ONLY = 3
        const val MENU_ITEM_NONE = 0
        const val MENU_ITEM_SHARE = 1
        const val MENU_ITEM_WEB_SEARCH = 2
        const val MENU_ITEM_PROCESS_TEXT = 4
        const val FORCE_DARK_OFF = 0
        const val FORCE_DARK_AUTO = 1
        const val FORCE_DARK_ON = 2
        // AOSP WebSettings delegates this value to the installed WebView
        // provider. Preserve its Android/mobile identity even though the host
        // provider itself is a desktop Chromium process; sites use this value
        // to select the Android document and redirect behavior.
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; artzig Build/AP3A.241105.008; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"

        @JvmStatic
        fun getDefaultUserAgent(context: Context): String = DEFAULT_USER_AGENT
    }
}
