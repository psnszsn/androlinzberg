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
    private var processServiceCacheInitialized = false

    /** The process's system context over the given window geometry (theme applied when not 0). */
    @JvmStatic
    fun systemContext(frameworkRes: String, width: Int, height: Int, scale120: Int, themeResId: Int): Context {
        val thread = bootstrap(frameworkRes)
        configureDisplay(width, height, scale120)
        val context: Context = thread.systemContext
        if (themeResId != 0) context.setTheme(themeResId)
        return context
    }

    /** ActivityThread.systemMain() + installSystemApplicationInfo + the display service; idempotent. */
    @JvmStatic
    fun bootstrap(frameworkRes: String): ActivityThread {
        AssetManager.createSystemAssetsInZygoteLocked(false, frameworkRes)
        // ZygoteInit preloads the system font map before any process draws text.
        android.graphics.Typeface.loadPreinstalledSystemFontMap()
        synchronized(this) {
            if (!processServiceCacheInitialized) {
                // AOSP ActivityThread.ApplicationThread.bindApplication seeds
                // process-specific Binder references through this stock API.
                android.os.ServiceManager.initServiceCache(
                    hashMapOf<String, android.os.IBinder>(
                        Context.DISPLAY_SERVICE to dev.artzig.system.HostedDisplayManagerService(),
                        Context.INPUT_SERVICE to dev.artzig.system.HostedInputManagerService(),
                    ),
                )
                processServiceCacheInitialized = true
            }
        }
        val thread = ActivityThread.systemMain()
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
        // AOSP ActivityThread.attach(system=true) creates the system Application before
        // SystemServer starts services. DeviceConfig's Settings-backed store relies on it.
        thread.`bindSystemApplication$artzig`()
        return thread
    }

    /** The desktop window as the default display; every Resources follows through ResourcesManager
     * (AOSP ActivityThread.handleConfigurationChanged applies the configuration the same way). */
    @JvmStatic
    fun configureDisplay(width: Int, height: Int, scale120: Int) {
        val density = scale120 / 120f
        val densityDpi = Math.round(160f * density)
        ArtzigDisplay.configure(width, height, densityDpi)
        val config = Configuration()
        config.setToDefaults()
        config.densityDpi = densityDpi
        config.screenWidthDp = (width / density).toInt()
        config.screenHeightDp = (height / density).toInt()
        config.smallestScreenWidthDp = Math.min(config.screenWidthDp, config.screenHeightDp)
        config.orientation =
            if (width >= height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        ResourcesManager.getInstance().applyConfigurationToResources(config, null)
    }
}
