package dev.artzig

import android.app.ActivityThread
import android.app.ResourcesManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.os.Environment
import android.os.Process
import android.view.ArtzigDisplay

/**
 * Host bootstrap of a process's ActivityThread and system context.
 *
 * Source note: AOSP `ActivityThread.systemMain()` plus what SystemServer and
 * the zygote hand a process before its first Context: the system assets
 * (framework-res.apk), the "android" ApplicationInfo
 * (`installSystemApplicationInfo`), and the display the ResourcesManager
 * derives its metrics from. On this host the display is the desktop window:
 * `width` x `height` at `scale120` / 120 density, served by the process-local
 * display service the unchanged AOSP DisplayManagerGlobal client talks to.
 */
object HostContexts {
    init {
        android.view.HostedWindowLogging.initialize()
    }

    private var processServiceCacheInitialized = false
    private var systemInfoInstalled = false
    private var windowService: dev.artzig.system.HostedWindowManagerService? = null

    /** The process's system context over the given window geometry (theme applied when not 0). */
    @JvmStatic
    fun systemContext(frameworkRes: String, width: Int, height: Int, scale120: Int, themeResId: Int): Context {
        val thread = bootstrap(frameworkRes)
        configureDisplay(width, height, scale120)
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
                android.os.ServiceManager.initServiceCache(
                    if (dev.artzig.window.WindowBackend.stock) emptyMap<String, android.os.IBinder>()
                    else hashMapOf<String, android.os.IBinder>(
                        Context.WINDOW_SERVICE to dev.artzig.system.HostedWindowManagerService().also { windowService = it },
                        Context.DISPLAY_SERVICE to dev.artzig.system.HostedDisplayManagerService(),
                        Context.INPUT_SERVICE to dev.artzig.system.HostedInputManagerService(),
                    ),
                )
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

    /** Propagate the host display configuration through stock window-context transactions. */
    @JvmStatic
    fun dispatchWindowConfiguration(config: Configuration) {
        windowService?.applyConfiguration(config)
    }

    /** The desktop window as the default display; every Resources follows through ResourcesManager
     * (AOSP ActivityThread.handleConfigurationChanged applies the configuration the same way). */
    @JvmStatic
    fun configureDisplay(width: Int, height: Int, scale120: Int) {
        // Stock WM owns the session display and delivers per-activity configurations.
        if (dev.artzig.window.WindowBackend.stock) return
        val density = scale120 / 120f
        val densityDpi = Math.round(160f * density)
        ArtzigDisplay.configure(width, height, densityDpi)
        val config = Configuration()
        config.setToDefaults()
        config.setLocales(android.os.LocaleList.getDefault())
        config.densityDpi = densityDpi
        config.screenWidthDp = (width / density).toInt()
        config.screenHeightDp = (height / density).toInt()
        config.smallestScreenWidthDp = Math.min(config.screenWidthDp, config.screenHeightDp)
        config.orientation =
            if (width >= height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        config.windowConfiguration.setBounds(android.graphics.Rect(0, 0, width, height))
        config.windowConfiguration.setAppBounds(0, 0, width, height)
        config.windowConfiguration.setMaxBounds(0, 0, width, height)
        ResourcesManager.getInstance().applyConfigurationToResources(config, null)
        dispatchWindowConfiguration(config)
    }
}
