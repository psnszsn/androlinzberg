// artzig M4: host a real APK — resources, classloader, Application, MainActivity.
@file:Suppress("DEPRECATION")

package dev.artzig

import android.app.Activity
import android.content.res.ApkAssets
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.RecordingCanvas
import android.graphics.RenderNode
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Enumeration
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import dalvik.system.DexFile

public final class ApkHost {
    private constructor () {}

    companion object {
        private val TAG: String = "ApkHost"
        /** Process binding and visible-activity state are independent. An
         * Activity can be absent while the Application and Services remain
         * alive; that must never cause ActivityThread.bindApplication to run
         * again on the next frame. */
        private var sProcessInitialized: Boolean = false
        private lateinit var sActivity: Activity
        private var sActivityActive: Boolean = false
        private lateinit var sContext: android.content.Context
        private lateinit var sRoot: android.view.ViewRootImpl
        private var sRootActive: Boolean = false
        private lateinit var sClassLoader: HostedApkClassLoader
        private var sWidth: Int = 0
        private var sHeight: Int = 0
        private var sScale120: Int = 0
        private var sAppFullscreenRequested: Boolean = false
        private var sHostFullscreenSuppressed: Boolean = false
        private val TRACE_SLOW_FRAMES: Boolean =
            System.getenv("ARTZIG_SLOW_FRAME_TRACE") != null

        /** AOSP DexFile/OatFileAssistant freshness check for the standard
         * dalvik-cache artifact that the host background compiler installs. */
        @JvmStatic public fun apkDexoptNeeded(): Boolean {
            val apkPath: String = System.getenv("ARTZIG_APK") ?: return false
            val frameworkJar: String = System.getenv("ARTZIG_FRAMEWORK_JAR") ?: return false
            val context: String = "PCL[];PCL[" + frameworkJar + "]"
            return try {
                DexFile.getDexOptNeeded(
                    apkPath,
                    "x86_64",
                    "speed",
                    context,
                    false,
                    false,
                ) != DexFile.NO_DEXOPT_NEEDED
            } catch (t: Throwable) {
                Log.w(TAG, "dexopt freshness check failed; compilation will be retried", t)
                true
            }
        }

        @JvmStatic public fun apkDexoptStatus(): String {
            val apkPath: String = System.getenv("ARTZIG_APK") ?: return "unavailable"
            return try {
                val info = DexFile.getDexFileOptimizationInfo(apkPath, "x86_64")
                info.status + "/" + info.reason
            } catch (t: Throwable) {
                "unavailable:" + t.javaClass.simpleName
            }
        }

        @JvmStatic public fun reportDexoptFailure() {
            android.util.ArtzigTestDiagnostics.reportIssue(
                "dexopt_failure",
                "dev.artzig.ApkHost",
                "backgroundDexopt",
                "speed compilation failed; temporary artifacts were removed and a later launch will retry",
            )
        }

/** Extract the host ABI from the APK because the desktop/bionic bridge
         * cannot mmap Android's special "apk!/lib/..." classloader paths. */
        @Throws(Exception::class)
        @JvmStatic
        private fun extractNativeLibraries(apkPath: String): String {
            val dirPath: String? = System.getenv("ARTZIG_NATIVE_LIB_DIR")
            if (dirPath == null || dirPath.isEmpty()) {
                throw IllegalStateException("ARTZIG_NATIVE_LIB_DIR is not configured")
            }
            val dir: File = File(dirPath)
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw IllegalStateException("cannot create native library directory: " + dir)
            }
            var extracted: Int = 0
            ZipFile(apkPath).use { zip: ZipFile ->
                val entries: Enumeration<out ZipEntry> = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry: ZipEntry = entries.nextElement()
                    val name: String = entry.getName()
                    if (entry.isDirectory() || !name.startsWith("lib/x86_64/")) continue
                    val base: String = name.substring("lib/x86_64/".length)
                    if (base.isEmpty() || base.indexOf('/') >= 0 || !base.endsWith(".so")) continue
                    val target: File = File(dir, base)
                    val temp: File = File(dir, base + ".tmp")
                    zip.getInputStream(entry).use { `in`: InputStream ->
                        FileOutputStream(temp).use { out: FileOutputStream ->
                            `in`.transferTo(out)
                        }
                    }
                    if (target.exists() && !target.delete()) {
                        throw IllegalStateException("cannot replace " + target)
                    }
                    if (!temp.renameTo(target)) {
                        throw IllegalStateException("cannot install " + target)
                    }
                    extracted++
                }
            }
            if (extracted == 0) throw IllegalStateException("APK has no x86_64 native libraries")
            Log.i(TAG, "extracted " + extracted + " x86_64 native libraries to " + dir)
            return dir.getAbsolutePath()
        }

/** Mouse-wheel scroll: dispatch ACTION_SCROLL at (x,y). ~1.0 per wheel detent. */
        @JvmStatic public fun scroll(
            x: Float,
            y: Float,
            hscroll: Float,
            vscroll: Float,
        ) {
            if (!sActivityActive) return
            android.view.HostedInputDispatcher.dispatchScroll(sRoot, x, y, hscroll, vscroll)
        }

/** Unbuttoned pointer motion: dispatch a hover event (hover states, tooltips)
         *  and resolve the pointer icon under (x,y). Returns PointerIcon.TYPE_*. */
        @JvmStatic public fun hover(
            x: Float,
            y: Float,
        ): Int = hoverAction(MotionEvent.ACTION_HOVER_MOVE, x, y)

        @JvmStatic public fun hoverAction(
            action: Int,
            x: Float,
            y: Float,
        ): Int {
            if (!sActivityActive) return 0
            try {
                return android.view.HostedInputDispatcher.dispatchHover(sRoot, action, x, y)
            } catch (t: Throwable) {
                Log.w(TAG, "hover failed", t)
                return 0
            }
        }

/** Synthetic Android key input used by the test control protocol. */
        @JvmStatic public fun key(
            action: Int,
            code: Int,
            unicode: Int,
        ) {
            if (!sActivityActive) return
            try {
                android.view.HostedInputDispatcher.dispatchSyntheticKey(
                    sRoot,
                    if (action == 0) android.view.KeyEvent.ACTION_DOWN else android.view.KeyEvent.ACTION_UP,
                    code,
                    unicode,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "key failed", t)
            }
        }

/** Physical Wayland keyboard input. scanCode is the evdev code; XKB supplies unicode. */
        @JvmStatic public fun physicalKey(
            action: Int,
            scanCode: Int,
            unicode: Int,
            _keysym: Int,
        ) {
            if (!sActivityActive) return
            android.view.HostedInputDispatcher.dispatchPhysicalKey(
                sRoot,
                if (action == 0) android.view.KeyEvent.ACTION_DOWN else android.view.KeyEvent.ACTION_UP,
                scanCode,
                unicode,
            )
        }

/** Physical wl_touch contact input. */
        @JvmStatic public fun touchContact(
            action: Int,
            id: Int,
            x: Float,
            y: Float,
            major: Float,
            minor: Float,
            orientation: Float,
        ) {
            if (!sActivityActive) return
            android.view.HostedInputDispatcher.dispatchTouchContact(
                sRoot, action, id, x, y, major, minor, orientation,
            )
        }

/** Wayland seat capability/focus loss must cancel Android's active input streams. */
        @JvmStatic public fun inputReset(kind: Int) {
            when (kind) {
                0 -> android.view.HostedInputDispatcher.cancelMouse(if (sRootActive) sRoot else null)
                1 -> android.view.HostedInputDispatcher.resetKeyboard()
                2 -> android.view.HostedInputDispatcher.cancelTouch(if (sRootActive) sRoot else null)
            }
        }

        @JvmStatic public fun wantsPointerCapture(): Boolean =
            android.view.HostedInputDispatcher.wantsPointerCapture()

        @JvmStatic public fun onHostPointerCaptureChanged(hasCapture: Boolean) {
            android.view.HostedInputDispatcher.onPointerCaptureChanged(hasCapture)
        }

        @JvmStatic public fun capturedPointer(dx: Float, dy: Float, buttonState: Int) {
            android.view.HostedInputDispatcher.dispatchCapturedPointer(dx, dy, buttonState)
        }

        @JvmStatic public fun capturedMouse(
            action: Int,
            buttonState: Int,
            actionButton: Int,
        ) {
            android.view.HostedInputDispatcher.dispatchCapturedMouse(
                action, buttonState, actionButton,
            )
        }

/** Mouse back button / BTN_SIDE. */
        @JvmStatic public fun back() {
            if (!sActivityActive) return
            try {
                val win: android.view.ArtzigWindowStack.Entry? = android.view.ArtzigWindowStack.top()
                if (win != null) {
                    if (win.onBack != null) {
                        win.onBack.run()
                    } else {
                        // popups and other overlays: synthesize a BACK key
                        val down: android.view.KeyEvent =
                            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK)
                        val up: android.view.KeyEvent =
                            android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK)
                        if (!(win.view.dispatchKeyEvent(down) or win.view.dispatchKeyEvent(up))) {
                            (
                                sContext.getSystemService(
                                    android.content.Context.WINDOW_SERVICE,
                                ) as android.view.WindowManager
                            ).removeView(win.view)
                        }
                    }
                    return
                }
                sActivity.onBackPressed()
            } catch (t: Throwable) {
                Log.w(TAG, "back failed", t)
            }
        }

/** Explicit synthetic touchscreen input used by AZK_TAP/PRESS/SWIPE. */
        @JvmStatic public fun motion(
            action: Int,
            x: Float,
            y: Float,
        ) {
            if (!sActivityActive) return
            android.view.HostedInputDispatcher.dispatchTouch(sRoot, action, x, y)
        }

/** wl_pointer input: Android mouse source/tool type and full button metadata. */
        @JvmStatic public fun mouse(
            action: Int,
            buttonState: Int,
            actionButton: Int,
            x: Float,
            y: Float,
        ) {
            if (!sActivityActive) return
            android.view.HostedInputDispatcher.dispatchMouse(sRoot, action, buttonState, actionButton, x, y)
        }

        /** The window geometry as the process's display and resource configuration
         * (AOSP: the configuration AMS hands bindApplication). */
        @JvmStatic
        private fun applyWindowConfiguration(displayWidth: Int, displayHeight: Int, scale120: Int) {
            val base: Configuration = Configuration()
            base.setToDefaults()
            val windowConfig: android.content.res.HostedWindowConfiguration =
                android.content.res.HostedConfigurationController.calculate(
                    base,
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                    displayWidth,
                    displayHeight,
                    scale120,
                )
            android.view.ArtzigDisplay.configure(
                windowConfig.displayWidth,
                windowConfig.displayHeight,
                windowConfig.densityDpi,
            )
            android.app.ResourcesManager.getInstance().applyConfigurationToResources(windowConfig.configuration, null)
        }

/** Boot the APK: returns after Application.onCreate + MainActivity.onCreate. */
        @Throws(Exception::class)
        @JvmStatic
        public fun init(
            width: Int,
            height: Int,
            scale120: Int,
        ) {
            sWidth = width
            sHeight = height
            sScale120 = scale120
            android.view.WindowManagerImpl.configureWindowMetrics(width, height, scale120 / 120f)
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            val tFonts: Long = SystemClock.uptimeMillis()
            android.graphics.Typeface.loadPreinstalledSystemFontMap()
            Log.i(TAG, "system font map loaded in " + (SystemClock.uptimeMillis() - tFonts) + " ms")
            val frameworkRes: String = System.getenv("ARTZIG_FRAMEWORK_RES")
            val apkPath: String = System.getenv("ARTZIG_APK")
            AssetManager.createSystemAssetsInZygoteLocked(false, frameworkRes)

            // Identity first: the pre-bootstrap package name comes from a
            // lightweight ApkLite parse (AOSP shape); the broker assigns this
            // process its Android uid, and Process.myUid() must not be
            // observed before that. The full manifest parse happens only in
            // the system process (installPackage).
            val pkg: String = parseApkLitePackageName(apkPath)
            dev.artzig.BinderSession.setIdentity(pkg, false)
            android.os.Process.setArgV0(pkg)
            installOwnPackage(apkPath, pkg, extractNativeLibraries(apkPath))
            val pm: android.content.pm.IPackageManager = android.app.ActivityThread.getPackageManager()
            val appInfo: android.content.pm.ApplicationInfo =
                checkNotNull(pm.getApplicationInfo(pkg, 0L, 0)) { "no ApplicationInfo for " + pkg }
            val appClass: String? = appInfo.className
            val mainActivity: String? = resolveLauncherActivity(pm, pkg)
            val appThemeRes: Int = appInfo.theme
            val providers: List<android.content.pm.ProviderInfo> = providersForProcess(pm, pkg)
            Log.i(
                TAG,
                "pkg=" + pkg + " app=" + appClass + " main=" + mainActivity + " theme=0x" +
                    Integer.toHexString(appThemeRes),
            )

            // --- classloader over the apk (+ bundled native libs): the real
            // LoadedApk builds it (ApplicationLoaders -> ClassLoaderFactory ->
            // HostedApkClassLoader) from sourceDir and nativeLibraryDir.
            val cl: HostedApkClassLoader = loadedApkClassLoader(appInfo)
            sClassLoader = cl
            // The real AOSP Parcel has no default-classloader hook; Parcelables
            // are read with an explicit loader (Bundle.setClassLoader) at the
            // call sites. sClassLoader holds the APK loader for that routing.
            Log.i(TAG, "APK dexopt status: " + apkDexoptStatus())

            // Per-process display and input services: real Binder objects the
            // unchanged AOSP DisplayManagerGlobal / InputManagerGlobal clients
            // resolve through ServiceManager.
            android.os.ServiceManager.addProcessLocalService(
                android.content.Context.INPUT_SERVICE,
                dev.artzig.system.HostedInputManagerService(),
            )
            android.os.ServiceManager.addProcessLocalService(
                android.content.Context.DISPLAY_SERVICE,
                dev.artzig.system.HostedDisplayManagerService(),
            )
            // A resizable Activity starts with the current window bounds; a fixed
            // manifest orientation constrains those bounds before onCreate.
            val initialOrientation: Int =
                if (mainActivity != null) {
                    pm.getActivityInfo(android.content.ComponentName(pkg, mainActivity), 0L, 0)
                        ?.screenOrientation
                        ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                } else {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            val initialLandscape: Boolean =
                android.content.res.HostedConfigurationController
                    .resolveLandscape(initialOrientation, width, height)
            val displayWidth: Int = if (initialLandscape) Math.max(width, height) else Math.min(width, height)
            val displayHeight: Int = if (initialLandscape) Math.min(width, height) else Math.max(width, height)
            HostContexts.bootstrap(frameworkRes)
            applyWindowConfiguration(displayWidth, displayHeight, scale120)
            if (Thread.getDefaultUncaughtExceptionHandler() == null) {
                Thread.setDefaultUncaughtExceptionHandler({ thread, throwable ->
                    Log.e(TAG, "uncaught exception on " + thread.getName(), throwable)
                })
            }

            // A hosted process has no keystore daemon. Install the compatible,
            // persistent provider before Application.onCreate initializes its
            // encrypted preferences and databases.
            if (java.security.Security.getProvider("AndroidKeyStore") == null) {
                java.security.Security.insertProviderAt(ArtzigKeyStoreProvider(), 1)
            }

            // AOSP ordering: attachApplication, then bindApplication (LoadedApk,
            // Application.attach, providers installed + published, onCreate).
            android.app.ActivityThread.attachApplicationHosted(pkg)
            val app: android.app.Application = android.app.ActivityThread.currentActivityThread()
                .`handleBindApplication$artzig`(appInfo, providers)
            sContext = app
            installActivityHost(pm, pkg)
            sProcessInitialized = true

            // --- MainActivity
            Log.i(TAG, "starting " + mainActivity)
            if (mainActivity == null) throw IllegalStateException("manifest has no launcher activity")
            android.app.ActivityThread.launchMainActivity(mainActivity)
            Log.i(TAG, "activity up")
        }

        /** The pre-bootstrap package name, read with the AOSP lightweight
         * parse (ApkLiteParseUtils) — no binder identity needed. */
        @JvmStatic
        private fun parseApkLitePackageName(apkPath: String): String {
            // Not forDefaultParsing(): that resolves the platform-compat
            // service through ServiceManager, which would connect the hosted
            // Binder runtime anonymously before bootstrap fixes this
            // process's package identity.
            val input = android.content.pm.parsing.result.ParseTypeImpl(
                android.content.pm.parsing.result.ParseInput.Callback { _, _, _ -> true },
            ).reset()
            val result = android.content.pm.parsing.ApkLiteParseUtils.parsePackageLite(
                input, java.io.File(apkPath), 0,
            )
            if (result.isError) {
                throw IllegalStateException("cannot read package name of " + apkPath + ": " + result.errorMessage)
            }
            return result.result.packageName
        }

        /** Installs this process's APK through the activity manager (parsing
         * happens only in the system process). */
        @JvmStatic
        private fun installOwnPackage(apkPath: String, pkg: String, nativeLibraryDir: String) {
            val installer = checkNotNull(android.app.HostedServiceClient.installerOrNull()) {
                "no hosted installer in this session"
            }
            val installed = installer.installPackage(apkPath, nativeLibraryDir)
            check(installed == pkg) { "installed as " + installed + ", expected " + pkg }
        }

        /** AOSP ActivityThread.handleBindApplication: the process's LoadedApk
         * (CONTEXT_INCLUDE_CODE) owns the APK class loader. */
        @JvmStatic
        private fun loadedApkClassLoader(appInfo: android.content.pm.ApplicationInfo): HostedApkClassLoader {
            val apk: android.app.LoadedApk = android.app.ActivityThread.currentActivityThread().getPackageInfoNoCheck(
                appInfo,
                android.content.res.CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO,
            )
            return apk.classLoader as HostedApkClassLoader
        }

        /** The MAIN/LAUNCHER activity via the real resolver. */
        @JvmStatic
        private fun resolveLauncherActivity(
            pm: android.content.pm.IPackageManager,
            pkg: String,
        ): String? {
            val query = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                .setPackage(pkg)
            val matches = pm.queryIntentActivities(query, null, 0L, 0)?.list ?: return null
            return (matches.firstOrNull() as? android.content.pm.ResolveInfo)?.activityInfo?.name
        }

        /** Providers this process must instantiate: those of its own process
         * name (AOSP installContentProviders filters by processName). */
        @JvmStatic
        private fun providersForProcess(
            pm: android.content.pm.IPackageManager,
            pkg: String,
        ): List<android.content.pm.ProviderInfo> {
            val info = pm.getPackageInfo(
                pkg, android.content.pm.PackageManager.GET_PROVIDERS.toLong(), 0,
            ) ?: return emptyList()
            return info.providers?.filter { it.processName == null || it.processName == pkg }
                ?: emptyList()
        }

        /**
         * Headless bootstrap for `androlinzberg --app-process <apk>`: the same
         * process setup as init() minus window metrics, fonts, activity host,
         * and the launcher activity. The process serves its package's services
         * through the activity manager until it is told to exit.
         */
        @JvmStatic
        public fun initHeadless() {
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            // A windowless service host has no reason to outlive the session:
            // exit if the broker connection is lost (parent death, teardown).
            dev.artzig.BinderSession.setExitOnDisconnect()
            val frameworkRes: String = System.getenv("ARTZIG_FRAMEWORK_RES")
            val apkPath: String = System.getenv("ARTZIG_APK")
            AssetManager.createSystemAssetsInZygoteLocked(false, frameworkRes)
            val pkg: String = parseApkLitePackageName(apkPath)
            dev.artzig.BinderSession.setIdentity(pkg, false)
            android.os.Process.setArgV0(pkg)
            installOwnPackage(apkPath, pkg, extractNativeLibraries(apkPath))
            val pm: android.content.pm.IPackageManager = android.app.ActivityThread.getPackageManager()
            val appInfo: android.content.pm.ApplicationInfo =
                checkNotNull(pm.getApplicationInfo(pkg, 0L, 0)) { "no ApplicationInfo for " + pkg }
            val cl: HostedApkClassLoader = loadedApkClassLoader(appInfo)
            sClassLoader = cl
            // The real AOSP Parcel has no default-classloader hook; Parcelables
            // are read with an explicit loader (Bundle.setClassLoader) at the
            // call sites. sClassLoader holds the APK loader for that routing.
            // Same per-process display/input Binder services as init(): the
            // AOSP manager clients resolve them through ServiceManager even in
            // a headless service host.
            android.os.ServiceManager.addProcessLocalService(
                android.content.Context.INPUT_SERVICE,
                dev.artzig.system.HostedInputManagerService(),
            )
            android.os.ServiceManager.addProcessLocalService(
                android.content.Context.DISPLAY_SERVICE,
                dev.artzig.system.HostedDisplayManagerService(),
            )
            HostContexts.bootstrap(frameworkRes)
            applyWindowConfiguration(640, 480, 120)
            if (java.security.Security.getProvider("AndroidKeyStore") == null) {
                java.security.Security.insertProviderAt(ArtzigKeyStoreProvider(), 1)
            }
            // AOSP ordering: attach first, then bindApplication (providers
            // installed + published before Application.onCreate).
            android.app.ActivityThread.attachApplicationHosted(pkg)
            sContext = android.app.ActivityThread.currentActivityThread()
                .`handleBindApplication$artzig`(appInfo, providersForProcess(pm, pkg))
            Log.i(TAG, "headless app process up: " + pkg)
        }

        @JvmStatic private fun installActivityHost(
            pm: android.content.pm.IPackageManager,
            pkg: String,
        ) {
            val activities = HashMap<String, android.content.pm.ActivityInfo>()
            for (
                activity in pm.getPackageInfo(
                    pkg, android.content.pm.PackageManager.GET_ACTIVITIES.toLong(), 0,
                )?.activities ?: emptyArray()
            ) {
                activities[activity.name] = activity
            }
            android.app.ActivityThread.configureActivityHost(
                object : android.app.ActivityThread.HostAdapter {
                    public override fun getActivityContext(): android.content.Context = sContext

                    public override fun getActivityClassLoader(): ClassLoader = sClassLoader

                    public override fun showActivity(activity: Activity) {
                        sActivity = activity
                        sActivityActive = true
                        show(activity)
                    }

                    public override fun clearActivityWindows() {
                        sActivityActive = false
                        android.view.ArtzigWindowStack.clear()
                        if (sRootActive) sRoot.destroy()
                        sRootActive = false
                        android.view.ArtzigAccessibilityBridge.setActivityRoot(null)
                    }

                    public override fun resizeActivityRoot(
                        activity: Activity,
                        width: Int,
                        height: Int,
                    ) {
                        if (activity === sActivity && sRootActive) sRoot.setSize(width, height)
                    }

                    public override fun resolveActivity(
                        intent: android.content.Intent,
                    ): android.content.ComponentName? =
                        HostedDesktopIntents.resolve(sContext, intent)
                },
                activities,
            )
        }

        @JvmStatic private fun show(a: Activity) {
            if (sRootActive) sRoot.destroy()
            sRoot = android.view.ViewRootImpl(sContext)
            sRootActive = true
            sRoot.setView(a.getWindow().getDecorView(), sWidth, sHeight)
            android.view.ArtzigAccessibilityBridge.setActivityRoot(sRoot)
            android.view.HostedInputDispatcher.reset()
        }

/** Test-control JNI surface. Android semantics stay in the framework bridge. */
        @JvmStatic public fun accessibilitySnapshot(): String = android.view.ArtzigAccessibilityBridge.snapshot()

        @JvmStatic public fun accessibilityDiagnostics(afterRevision: Long): String =
            android.view.ArtzigAccessibilityBridge.diagnostics(
                afterRevision,
            )

        @JvmStatic public fun testDiagnostics(
            afterLogSequence: Long,
            afterIssueSequence: Long,
        ): String = android.util.ArtzigTestDiagnostics.snapshot(afterLogSequence, afterIssueSequence)

        @JvmStatic public fun diagnosticLogSequence(): Long = android.util.ArtzigTestDiagnostics.logSequence()

        @JvmStatic public fun diagnosticIssueSequence(): Long = android.util.ArtzigTestDiagnostics.issueSequence()

        @JvmStatic public fun accessibilityRevision(): Long = android.view.ArtzigAccessibilityBridge.revision()

        @JvmStatic public fun accessibilityIdle(): Boolean = android.view.ArtzigAccessibilityBridge.isIdle()

        @JvmStatic public fun accessibilityPerform(
            expectedRevision: Long,
            windowId: Long,
            viewId: Int,
            virtualId: Int,
            action: Int,
            text: String?,
        ): Boolean =
            android.view.ArtzigAccessibilityBridge.perform(
                expectedRevision,
                windowId,
                viewId,
                virtualId,
                action,
                text,
            )

/** Deliver component teardown and release host rendering before Wayland
         * disconnects and the host process exits. */
        @JvmStatic public fun shutdown() {
            Log.i(TAG, "shutdown")

            // Services first: they may own decoder/audio workers and native layers.
            android.app.ActivityThread.currentActivityThread().`shutdownServices$artzig`()
            Log.i(TAG, "services stopped")

            try {
                android.app.ActivityThread.shutdownActivitiesInProcess()
            } catch (t: Throwable) {
                Log.w(TAG, "activity shutdown failed", t)
            }
            Log.i(TAG, "activities destroyed")

            drainLooper()
            android.view.ArtzigWindowStack.clear()
            if (sRootActive) sRoot.destroy()
            sRootActive = false
            android.view.ArtzigAccessibilityBridge.setActivityRoot(null)
            Log.i(TAG, "views detached")
            try {
                android.view.ThreadedRenderer.destroyInstance()
            } catch (t: Throwable) {
                Log.w(TAG, "renderer shutdown failed", t)
            }
            Log.i(TAG, "renderer destroyed")
            sComposite.discardDisplayList()

            try {
                android.app.ActivityThread.currentActivityThread().`shutdownApplication$artzig`()
            } catch (t: Throwable) {
                Log.w(TAG, "application shutdown failed", t)
            }
            sActivityActive = false
            sProcessInitialized = false
            android.view.HostedInputDispatcher.reset()
            sAppFullscreenRequested = false
            sHostFullscreenSuppressed = false
            Log.i(TAG, "shutdown complete")
        }

        private val sComposite: RenderNode = RenderNode("artzig-window-composite")

        @JvmStatic private fun appRequestsFullscreen(): Boolean {
            if (!sActivityActive) return false
            sAppFullscreenRequested =
                android.view.HostedWindowPresentation.requestsFullscreen(sActivity.getWindow(), sAppFullscreenRequested)
            return sAppFullscreenRequested
        }

/** Whether Android has entered its fullscreen/immersive presentation.
         * Orientation locks are deliberately independent of presentation state. */
        @JvmStatic public fun wantsFullscreen(): Boolean {
            val requested: Boolean = appRequestsFullscreen()
            if (!requested) sHostFullscreenSuppressed = false
            return requested && !sHostFullscreenSuppressed
        }

        @JvmStatic private fun wantsLandscapeConfiguration(
            width: Int,
            height: Int,
        ): Boolean {
            val orientation: Int =
                if (!sActivityActive) {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                } else {
                    sActivity.getRequestedOrientation()
                }
            return android.content.res.HostedConfigurationController
                .resolveLandscape(orientation, width, height)
        }

/** Prevent an app's unchanged immersive flags from immediately undoing a
         * compositor-driven fullscreen exit. Never alter application orientation. */
        @JvmStatic public fun onHostFullscreenChanged(fullscreen: Boolean) {
            if (fullscreen) {
                sHostFullscreenSuppressed = false
            } else if (appRequestsFullscreen()) {
                sHostFullscreenSuppressed = true
            }
        }

        @Throws(Exception::class)
        @JvmStatic
        public fun drawHardware(
            logicalWidth: Int,
            logicalHeight: Int,
            bufferWidth: Int,
            bufferHeight: Int,
            scale120: Int,
        ) {
            val width: Int = bufferWidth
            val height: Int = bufferHeight
            if (!sProcessInitialized) init(width, height, scale120)
            val desiredOrientation: Int =
                if (wantsLandscapeConfiguration(width, height)) {
                    Configuration.ORIENTATION_LANDSCAPE
                } else {
                    Configuration.ORIENTATION_PORTRAIT
                }
            // Requested orientation is Android application state, not Wayland
            // presentation state. Deliver it immediately so applications can react
            // (and, if appropriate, request fullscreen themselves) without waiting
            // for a compositor resize that depends on that reaction.
            val orientationChangeReady: Boolean =
                sContext.getResources().getConfiguration().orientation != desiredOrientation
            if (width != sWidth || height != sHeight || scale120 != sScale120 ||
                orientationChangeReady
            ) {
                resize(width, height, scale120)
            }
            val traceStart: Long = if (TRACE_SLOW_FRAMES) SystemClock.uptimeMillis() else 0L
            drainLooper()
            val traceBeforeDrain: Long = if (TRACE_SLOW_FRAMES) SystemClock.uptimeMillis() else 0L
            val wins: MutableList<android.view.ArtzigWindowStack.Entry> =
                android.view.ArtzigWindowStack.entries()
            for (e: android.view.ArtzigWindowStack.Entry in wins) {
                e.root.`prepareWindowTraversal$artzig`(e.lp, width, height)
            }
            val tracePrepared: Long = if (TRACE_SLOW_FRAMES) SystemClock.uptimeMillis() else 0L
            android.view.Choreographer
                .getInstance()
                .`doFrame$artzig`(System.nanoTime())
            val traceChoreographer: Long = if (TRACE_SLOW_FRAMES) SystemClock.uptimeMillis() else 0L
            // The traversal barrier is now released. Synchronous View.post callbacks observe
            // geometry from this frame and may schedule another traversal for the next frame.
            drainLooper()
            if (TRACE_SLOW_FRAMES) {
                val traceDone: Long = SystemClock.uptimeMillis()
                if (traceDone - traceStart > 16L) {
                    Log.d(
                        "ApkHostFrame",
                        "slow main frame=" + (traceDone - traceStart) +
                            "ms pre-queue=" + (traceBeforeDrain - traceStart) +
                            " prepare=" + (tracePrepared - traceBeforeDrain) +
                            " choreographer=" + (traceChoreographer - tracePrepared) +
                            " post-queue=" + (traceDone - traceChoreographer),
                    )
                }
            }
            val base: RenderNode? = if (sRootActive) sRoot.`getRenderNode$artzig`() else null
            if (wins.isEmpty()) {
                if (base == null) {
                    sComposite.setPosition(0, 0, width, height)
                    val emptyCanvas: RecordingCanvas = sComposite.beginRecording(width, height)
                    try {
                        emptyCanvas.drawColor(0xff000000.toInt())
                    } finally {
                        sComposite.endRecording()
                    }
                    android.view.ThreadedRenderer
                        .getInstance()
                        .render(sComposite, width, height)
                    return
                }
                android.view.ThreadedRenderer
                    .getInstance()
                    .render(base, width, height)
                return
            }
            sComposite.setPosition(0, 0, width, height)
            val c: RecordingCanvas = sComposite.beginRecording(width, height)
            try {
                c.drawColor(0xff000000.toInt())
                if (base != null) c.drawRenderNode(base)
                for (e: android.view.ArtzigWindowStack.Entry in wins) {
                    try {
                        val wb: RenderNode? = e.root.`getRenderNode$artzig`()
                        if (wb == null) continue
                        val windowWidth: Int = e.root.getWidth()
                        val windowHeight: Int = e.root.getHeight()
                        if ((e.lp.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
                            val dim: Float = if (e.lp.dimAmount > 0) e.lp.dimAmount else 0.6f
                            c.drawColor(((dim * 255).toInt() shl 24))
                        }
                        val gravity: Int = if (e.lp.gravity == 0) android.view.Gravity.CENTER else e.lp.gravity
                        val hg: Int = gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK
                        val vg: Int = gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK
                        var wx: Int =
                            if (hg == android.view.Gravity.CENTER_HORIZONTAL) {
                                (width - windowWidth) / 2 + e.lp.x
                            } else if (hg == android.view.Gravity.RIGHT) {
                                width - windowWidth - e.lp.x
                            } else {
                                e.lp.x
                            }
                        var wy: Int =
                            if (vg == android.view.Gravity.CENTER_VERTICAL) {
                                (height - windowHeight) / 2 + e.lp.y
                            } else if (vg == android.view.Gravity.BOTTOM) {
                                height - windowHeight - e.lp.y
                            } else {
                                e.lp.y
                            }
                        wx = Math.max(0, Math.min(wx, width - windowWidth))
                        wy = Math.max(0, Math.min(wy, height - windowHeight))
                        e.frame.set(wx, wy, wx + windowWidth, wy + windowHeight)
                        val save: Int = c.save()
                        c.translate(wx.toFloat(), wy.toFloat())
                        c.drawRenderNode(wb)
                        c.restoreToCount(save)
                    } catch (t: Throwable) {
                        Log.w(TAG, "overlay draw failed: " + e.view.javaClass.name, t)
                    }
                }
            } finally {
                sComposite.endRecording()
            }
            android.view.ThreadedRenderer
                .getInstance()
                .render(sComposite, width, height)
        }

/** Window resized: update metrics/config and relayout. */
        @JvmStatic
        private fun resize(
            width: Int,
            height: Int,
            scale120: Int,
        ) {
            sWidth = width
            sHeight = height
            sScale120 = scale120
            try {
                val res: Resources = sContext.getResources()
                val oldConfig: Configuration = Configuration(res.getConfiguration())
                val requestedOrientation: Int =
                    if (sActivityActive) {
                        sActivity.getRequestedOrientation()
                    } else {
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                val windowConfig: android.content.res.HostedWindowConfiguration =
                    android.content.res.HostedConfigurationController
                        .calculate(oldConfig, requestedOrientation, width, height, scale120)
                android.view.WindowManagerImpl.configureWindowMetrics(width, height, windowConfig.density)
                android.view.ArtzigDisplay.configure(
                    windowConfig.displayWidth,
                    windowConfig.displayHeight,
                    windowConfig.densityDpi,
                )
                val config: Configuration = windowConfig.configuration
                // AOSP ActivityThread.handleConfigurationChanged: ResourcesManager applies the
                // configuration to every Resources; the metrics come from the display service.
                android.app.ResourcesManager.getInstance().applyConfigurationToResources(config, null)
                android.app.ActivityThread.applyConfigurationInProcess(config, windowConfig.changes, width, height)
            } catch (t: Throwable) {
                Log.w(TAG, "resize failed", t)
            }
            Log.i(TAG, "resized to " + width + "x" + height)
        }

        @JvmStatic
        public fun pumpMainLooper(): Long {
            val looper: Looper? = Looper.myLooper()
            if (looper == null) return -1
            val queue = looper.getQueue()
            var guard: Int = 0
            while (guard++ < 500) {
                val msg: android.os.Message? = queue.`pollDueMessage$artzig`()
                if (msg == null) break
                try {
                    try {
                        msg.getTarget().dispatchMessage(msg)
                    } catch (t: Throwable) {
                        // This loop intentionally survives app callbacks. Do not use Log's
                        // Throwable overload here: custom Throwable printers can expand into
                        // tens of megabytes and hide the original failure behind another OOM.
                        val targetName: String = msg.getTarget().javaClass.name
                        Log.e(
                            TAG,
                            "message dispatch failed on " + targetName + "\n" +
                                `formatThrowableForHost$artzig`(t),
                        )
                    }
                } catch (loggingFailure: Throwable) {
                    // Keep the recovery path allocation-light even under severe heap pressure.
                    System.err.print("E/ApkHost: message dispatch and diagnostic failed: ")
                    System.err.println(loggingFailure.javaClass.name)
                } finally {
                    android.os.MessageQueue.`recycleDispatched$artzig`(msg)
                }
            }
            if (guard > 500 && queue.`nextPollDelayMillis$artzig`() == 0L) {
                queue.`wakeHost$artzig`()
                return 0
            }
            queue.`runIdleHandlers$artzig`()
            return queue.`nextPollDelayMillis$artzig`()
        }

        @JvmStatic public fun needsFrame(): Boolean =
            android.view.Choreographer.getInstance().`hasDueCallbacks$artzig`()

        @JvmStatic private fun drainLooper() { pumpMainLooper() }

        private const val HOST_THROWABLE_CHAR_LIMIT: Int = 64 * 1024
        private const val HOST_THROWABLE_FRAME_LIMIT: Int = 64
        private const val HOST_THROWABLE_CAUSE_LIMIT: Int = 16

        /** Bounded diagnostic for the host's catch-and-continue looper seam. */
        @JvmStatic
        public fun `formatThrowableForHost$artzig`(throwable: Throwable): String {
            val out: StringBuilder = StringBuilder(4096)
            val seen: java.util.IdentityHashMap<Throwable, Boolean> =
                java.util.IdentityHashMap()
            var current: Throwable? = throwable
            var causeCount: Int = 0
            while (current != null && causeCount++ < HOST_THROWABLE_CAUSE_LIMIT) {
                if (seen.put(current, true) != null) {
                    appendHostDiagnostic(out, "[circular cause: ")
                    appendHostDiagnostic(out, current.javaClass.name)
                    appendHostDiagnostic(out, "]\n")
                    break
                }
                if (causeCount > 1) appendHostDiagnostic(out, "Caused by: ")
                appendHostDiagnostic(out, current.javaClass.name)
                appendHostDiagnostic(out, " [message omitted by bounded host logger]\n")

                val frames: Array<StackTraceElement> = current.stackTrace
                val frameCount: Int = Math.min(frames.size, HOST_THROWABLE_FRAME_LIMIT)
                var index: Int = 0
                while (index < frameCount && out.length < HOST_THROWABLE_CHAR_LIMIT) {
                    val frame: StackTraceElement = frames[index++]
                    appendHostDiagnostic(out, "\tat ")
                    appendHostDiagnostic(out, frame.className)
                    appendHostDiagnostic(out, ".")
                    appendHostDiagnostic(out, frame.methodName)
                    appendHostDiagnostic(out, "(")
                    if (frame.isNativeMethod) {
                        appendHostDiagnostic(out, "Native Method")
                    } else {
                        appendHostDiagnostic(out, frame.fileName ?: "Unknown Source")
                        if (frame.lineNumber >= 0) {
                            appendHostDiagnostic(out, ":")
                            appendHostDiagnostic(out, frame.lineNumber.toString())
                        }
                    }
                    appendHostDiagnostic(out, ")\n")
                }
                if (frames.size > frameCount) {
                    appendHostDiagnostic(out, "\t... ")
                    appendHostDiagnostic(out, (frames.size - frameCount).toString())
                    appendHostDiagnostic(out, " more frames\n")
                }
                current = current.cause
            }
            if (current != null || out.length >= HOST_THROWABLE_CHAR_LIMIT) {
                appendHostDiagnostic(out, "[throwable diagnostic truncated]\n")
            }
            return out.toString()
        }

        @JvmStatic
        private fun appendHostDiagnostic(
            out: StringBuilder,
            text: CharSequence,
        ) {
            val remaining: Int = HOST_THROWABLE_CHAR_LIMIT - out.length
            if (remaining <= 0) return
            out.append(text, 0, Math.min(remaining, text.length))
        }
    }
}
