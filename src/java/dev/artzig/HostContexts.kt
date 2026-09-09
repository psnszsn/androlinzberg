package dev.artzig

import android.app.ActivityThread
import android.app.ResourcesManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.os.Environment
import android.os.Process

/**
 * Host bootstrap of a process's ActivityThread and system context.
 *
 * Source note: AOSP `ActivityThread.systemMain()` plus what SystemServer and
 * the zygote hand a process before its first Context: the system assets
 * (framework-res.apk), the "android" ApplicationInfo
 * (`installSystemApplicationInfo`), and the display the ResourcesManager
 * derives its metrics from. DisplayManagerGlobal queries the session display;
 * ATMS supplies application/task configuration through stock transactions.
 */
object HostContexts {
    init {
        dev.artzig.window.HostedWindowLogging.initialize()
    }

    private var processServiceCacheInitialized = false
    private var systemInfoInstalled = false

    /** The process's system context; display geometry belongs to the session service. */
    @JvmStatic
    fun systemContext(frameworkRes: String, width: Int, height: Int, scale120: Int, themeResId: Int): Context {
        val thread = bootstrap(frameworkRes)
        val context: Context = thread.systemContext
        if (themeResId != 0) context.setTheme(themeResId)
        return context
    }

    /** Prepare assets, module initializers and local display services before thread attachment. */
    @JvmStatic
    fun prepareApplicationEnvironment(frameworkRes: String) {
        // UI hosts enter through JNI init rather than RuntimeBootstrap.main.
        HostedSystemCertificates.install()
        AssetManager.createSystemAssetsInZygoteLocked(false, frameworkRes)
        // ZygoteInit preloads the system font map before any process draws text.
        android.graphics.Typeface.loadPreinstalledSystemFontMap()
        // AMS supplies a full boot configuration on Android, including the system locales.
        // The desktop inherits its initial locale from the host VM instead of Android settings.
        val resources = ResourcesManager.getInstance()
        val initial = Configuration(resources.configuration)
        if (initial.locales.isEmpty) {
            initial.setLocales(android.os.LocaleList.getDefault())
            resources.applyConfigurationToResources(initial, null)
        }
        synchronized(this) {
            if (!processServiceCacheInitialized) {
                ActivityThread.initializeMainlineModules()
                // AOSP ActivityThread.ApplicationThread.bindApplication seeds
                // process-specific Binder references through this stock API.
                android.os.ServiceManager.initServiceCache(emptyMap<String, android.os.IBinder>())
                processServiceCacheInitialized = true
            }
        }
    }

    @JvmStatic
    fun bootstrap(frameworkRes: String): ActivityThread {
        prepareApplicationEnvironment(frameworkRes)
        val thread = ActivityThread.currentActivityThread() ?: ActivityThread.systemMain()
        HostedActivityProcess.initializeMainHandler(thread)
        if (systemInfoInstalled) return thread

        val info = ApplicationInfo()
        info.packageName = "android"
        info.processName = "system"
        info.uid = Process.SYSTEM_UID
        info.sourceDir = frameworkRes
        info.publicSourceDir = frameworkRes
        info.flags = ApplicationInfo.FLAG_SYSTEM
        info.targetSdkVersion = android.os.Build.VERSION.SDK_INT
        val dataDir = Environment.getDataSystemDirectory()
        if (!dataDir.isDirectory) dataDir.mkdirs()
        info.dataDir = dataDir.absolutePath
        info.credentialProtectedDataDir = info.dataDir
        info.deviceProtectedDataDir = info.dataDir
        thread.installSystemApplicationInfo(info, ClassLoader.getSystemClassLoader())
        systemInfoInstalled = true
        thread.handleConfigurationChanged(Configuration(ResourcesManager.getInstance().configuration), Context.DEVICE_ID_DEFAULT)
        return thread
    }

}
