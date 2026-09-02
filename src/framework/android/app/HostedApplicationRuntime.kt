package android.app

import android.content.ContentProvider
import android.content.Context
import android.content.pm.ProviderInfo
import android.util.Log

/**
 * AOSP ActivityThread.handleBindApplication/installContentProviders ordering
 * for a hosted process. The full implementation depends on AMS/PMS Binder
 * records, so the already-resolved ProviderInfo list is the host seam.
 */
object HostedApplicationRuntime {
    private const val TAG = "HostedApplicationRuntime"
    /** Installed providers with their ProviderInfo (AOSP ActivityThread.installProvider keeps both). */
    private val providers = mutableListOf<Pair<ContentProvider, android.content.pm.ProviderInfo>>()
    private val providersByAuthority = HashMap<String, ContentProvider>()

    /** A provider installed in this process, keyed by one of its authorities. */
    @JvmStatic
    fun localProvider(authority: String): ContentProvider? =
        synchronized(providers) { providersByAuthority[authority] }

    /** Holders for the activity manager, built from the installed providers. */
    @JvmStatic
    fun installedProviderHolders(): List<dev.artzig.system.HostedContentProviderHolder> =
        synchronized(providers) {
            providers.map { (provider, info) ->
                dev.artzig.system.HostedContentProviderHolder(
                    info,
                    provider.getIContentProvider().asBinder(),
                )
            }
        }

    @JvmStatic
    fun bind(
        context: Context,
        classLoader: ClassLoader,
        applicationClassName: String?,
        providerInfos: List<ProviderInfo>,
    ): Application {
        check(ActivityThread.currentApplication() == null) {
            "an Application is already bound to this process"
        }
        val application = if (applicationClassName != null) {
            classLoader.loadClass(applicationClassName)
                .getDeclaredConstructor()
                .newInstance() as Application
        } else {
            Application()
        }
        ActivityThread.setApplication(application)
        application.attach(context)

        synchronized(providers) {
            providers.clear()
            providersByAuthority.clear()
        }
        providerInfos.forEach { info ->
            Log.i(TAG, "installing provider ${info.name} authority=${info.authority}")
            val provider = classLoader.loadClass(info.name)
                .getDeclaredConstructor()
                .newInstance() as ContentProvider
            provider.attachInfo(context, info)
            synchronized(providers) {
                providers += provider to info
                info.authority?.split(";")?.forEach { authority ->
                    providersByAuthority[authority] = provider
                }
            }
        }

        // Publish before onCreate (AOSP installContentProviders ordering); a
        // no-op when the process has not attached to the activity manager yet.
        HostedServiceClient.publishInstalledProviders()

        Log.i(TAG, "Application.onCreate: ${application.javaClass.name}")
        application.onCreate()
        return application
    }

    @JvmStatic
    fun shutdown() {
        val application = ActivityThread.currentApplication()
        if (application != null) application.onTerminate()
        synchronized(providers) {
            providers.clear()
            providersByAuthority.clear()
        }
        ActivityThread.setApplication(null)
    }
}
