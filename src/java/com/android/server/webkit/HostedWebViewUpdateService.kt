package com.android.server.webkit

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.webkit.*
import com.android.internal.util.XmlUtils
import dev.artzig.system.HostedPermissions

/**
 * Source: WebViewUpdateService's Binder facade and SystemImpl. The original
 * Impl2 owns selection, signature/version validation, repairs and preparation
 * ordering. SystemImpl cannot be used directly: the exec-based WebView controller
 * needs its selected provider at the hosted launch boundary, and this host has
 * no shared RELRO creator or PinnerService. Chromium loads its regular library
 * in each exec child; the controller runs the original provider preload.
 */
class HostedWebViewUpdateService(
    private val context: Context,
    private val permissions: HostedPermissions,
    private val providerChanged: (ApplicationInfo) -> Unit,
) : IWebViewUpdateService.Stub(), SystemInterface {
    private val providers = readProviders()
    private val impl = WebViewUpdateServiceImpl2(this)

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val name = intent.data?.schemeSpecificPart ?: return
                val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                val state = when (intent.action) {
                    Intent.ACTION_PACKAGE_ADDED -> if (replacing) 2 else 1
                    Intent.ACTION_PACKAGE_REMOVED -> if (replacing) return else 3
                    Intent.ACTION_PACKAGE_CHANGED -> {
                        if (intent.getStringArrayExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST)?.contains(name) != true) return
                        0
                    }
                    else -> return
                }
                impl.packageStateChanged(name, state, intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL))
            }
        }, filter)
        // WebViewUpdateService.onStart allows isolated renderer/RELRO callers.
        android.os.ServiceManager.addService(Context.WEBVIEW_UPDATE_SERVICE, this, true)
        impl.prepareWebViewInSystemServer()
    }

    override fun notifyRelroCreationCompleted() {
        if (Binder.getCallingUid() != Process.SHARED_RELRO_UID && Binder.getCallingUid() != Process.SYSTEM_UID) return
        val identity = Binder.clearCallingIdentity()
        try { impl.notifyRelroCreationCompleted() } finally { Binder.restoreCallingIdentity(identity) }
    }
    override fun waitForAndGetProvider(): WebViewProviderResponse {
        check(Binder.getCallingPid() != Process.myPid()) { "Cannot create a WebView from the SystemServer" }
        val callingUid = Binder.getCallingUid()
        val response = impl.waitForAndGetProvider()
        response.packageInfo?.let { grantVisibilityToCaller(it.packageName, callingUid) }
        return response
    }

    /** WebViewUpdateService.grantVisibilityToCaller: an app that loads WebView
     * sees its provider package (implicit, direct package visibility). */
    private fun grantVisibilityToCaller(webViewPackageName: String, callingUid: Int) {
        val packages = com.android.server.LocalServices.getService(android.content.pm.PackageManagerInternal::class.java)
        val webviewUid = packages.getPackageUid(webViewPackageName, 0L, UserHandle.getUserId(callingUid))
        packages.grantImplicitAccess(UserHandle.getUserId(callingUid), null, UserHandle.getAppId(callingUid), webviewUid, true)
    }
    override fun changeProviderAndSetting(newProvider: String?): String {
        permissions.enforce(android.Manifest.permission.WRITE_SECURE_SETTINGS, "changeProviderAndSetting")
        val identity = Binder.clearCallingIdentity()
        return try { impl.changeProviderAndSetting(newProvider) } finally { Binder.restoreCallingIdentity(identity) }
    }
    override fun getValidWebViewPackages(): Array<WebViewProviderInfo> = impl.validWebViewPackages
    override fun getAllWebViewPackages(): Array<WebViewProviderInfo> = impl.webViewPackages
    override fun getCurrentWebViewPackageName(): String? = impl.currentWebViewPackage?.packageName
    override fun getCurrentWebViewPackage(): PackageInfo? = impl.currentWebViewPackage
    override fun getDefaultWebViewPackage(): WebViewProviderInfo = impl.defaultWebViewPackage

    override fun getWebViewPackages(): Array<WebViewProviderInfo> = providers.clone()
    override fun onWebViewProviderChanged(packageInfo: PackageInfo): Int {
        providerChanged(checkNotNull(packageInfo.applicationInfo))
        WebViewZygote::class.java.getDeclaredMethod("onWebViewProviderChanged", PackageInfo::class.java)
            .apply { isAccessible = true }.invoke(null, packageInfo)
        return 0 // No RELRO creators were started.
    }
    override fun getFactoryPackageVersion(packageName: String): Long =
        context.packageManager.getPackageInfo(packageName, PackageManager.MATCH_FACTORY_ONLY).longVersionCode
    override fun getUserChosenWebViewProvider(): String? =
        Settings.Global.getString(context.contentResolver, Settings.Global.WEBVIEW_PROVIDER)
    override fun updateUserSetting(newProviderName: String?) {
        Settings.Global.putString(context.contentResolver, Settings.Global.WEBVIEW_PROVIDER, newProviderName ?: "")
    }
    override fun killPackageDependents(packageName: String) {
        android.app.ActivityManager.getService().killPackageDependents(packageName, UserHandle.USER_ALL)
    }
    override fun enablePackageForAllUsers(packageName: String, enable: Boolean) {
        context.packageManager.setApplicationEnabledSetting(packageName,
            if (enable) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)
    }
    override fun installExistingPackageForAllUsers(packageName: String) {
        // The host has one user, and every image package is already installed for it.
        // Never report a successful repair of an absent package.
        val info = context.packageManager.getPackageInfo(packageName, 0)
        check(checkNotNull(info.applicationInfo).flags and ApplicationInfo.FLAG_INSTALLED != 0) { "Image package is not installed: $packageName" }
    }
    override fun systemIsDebuggable(): Boolean = Build.isDebuggable()
    override fun getPackageInfoForProvider(configInfo: WebViewProviderInfo): PackageInfo =
        context.packageManager.getPackageInfo(configInfo.packageName, PACKAGE_FLAGS)
    override fun isCompatibleImplementationPackage(packageInfo: PackageInfo): Boolean =
        WebViewFactoryProvider.isCompatibleImplementationPackage(packageInfo)
    override fun getPackageInfoForProviderAllUsers(configInfo: WebViewProviderInfo): List<UserPackage> =
        UserPackage.getPackageInfosAllUsers(context, configInfo.packageName, PACKAGE_FLAGS)
    override fun ensureZygoteStarted() { WebViewZygote.getProcess() }
    override fun pinWebviewIfRequired(appInfo: ApplicationInfo) {} // No configured pin quota.

    private fun readProviders(): Array<WebViewProviderInfo> {
        val result = ArrayList<WebViewProviderInfo>()
        context.resources.getXml(com.android.internal.R.xml.config_webview_packages).use { parser ->
            XmlUtils.beginDocument(parser, "webviewproviders")
            while (true) {
                XmlUtils.nextElement(parser)
                val element = parser.name ?: break
                if (element != "webviewprovider") continue
                val name = checkNotNull(parser.getAttributeValue(null, "packageName")) { "WebView provider missing package name" }
                val description = checkNotNull(parser.getAttributeValue(null, "description")) { "WebView provider missing description" }
                val available = parser.getAttributeValue(null, "availableByDefault") == "true"
                val fallback = parser.getAttributeValue(null, "isFallback") == "true"
                val signatures = ArrayList<String>()
                val depth = parser.depth
                while (XmlUtils.nextElementWithin(parser, depth)) {
                    if (parser.name == "signature") signatures.add(parser.nextText())
                }
                check(!fallback || available) { "Fallback WebView provider must be available by default" }
                result.add(WebViewProviderInfo(name, description, available, fallback, signatures.toTypedArray()))
            }
        }
        check(result.count { it.isFallback } <= 1) { "Multiple fallback WebView providers" }
        check(result.any { it.availableByDefault }) { "No default WebView provider" }
        return result.toTypedArray()
    }
    companion object {
        private const val PACKAGE_FLAGS = PackageManager.GET_META_DATA or PackageManager.GET_SIGNATURES or
            PackageManager.GET_SHARED_LIBRARY_FILES or PackageManager.MATCH_ANY_USER
    }
}
