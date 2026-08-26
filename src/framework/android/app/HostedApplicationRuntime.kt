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
    private val providers = mutableListOf<ContentProvider>()

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

        providers.clear()
        providerInfos.forEach { info ->
            Log.i(TAG, "installing provider ${info.name} authority=${info.authority}")
            val provider = classLoader.loadClass(info.name)
                .getDeclaredConstructor()
                .newInstance() as ContentProvider
            provider.attachInfo(context, info)
            providers += provider
        }

        Log.i(TAG, "Application.onCreate: ${application.javaClass.name}")
        application.onCreate()
        return application
    }

    @JvmStatic
    fun shutdown() {
        val application = ActivityThread.currentApplication()
        if (application != null) application.onTerminate()
        providers.clear()
        ActivityThread.setApplication(null)
    }
}
