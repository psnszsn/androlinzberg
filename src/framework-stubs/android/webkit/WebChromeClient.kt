package android.webkit

import android.os.Message

open class WebChromeClient {
    open fun onProgressChanged(view: WebView?, newProgress: Int) = Unit
    open fun onReceivedTitle(view: WebView?, title: String?) = Unit
    open fun onCloseWindow(window: WebView?) = Unit
    open fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = false
    open fun onJsAlert(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?,
    ): Boolean = false
    open fun onJsConfirm(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?,
    ): Boolean = false
    open fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?,
    ): Boolean = false
}
