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
        private class BackRoute(
            val root: android.view.ViewRootImpl,
            val callback: android.window.OnBackInvokedCallback,
        )
        private var sSyntheticBackRoute: BackRoute? = null
        private var sPhysicalBackRoute: BackRoute? = null
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
            dev.artzig.ArtzigTestDiagnostics.reportIssue(
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
            // The host linker requires extracted x86_64 files even when an
            // Android device could load an uncompressed library from the APK.
            // NativeLibraryHelper owns ABI filtering, CRC/timestamp reuse,
            // permissions, temporary-file cleanup and atomic replacement.
            com.android.internal.content.NativeLibraryHelper.Handle.create(
                listOf(apkPath), false, true, false, false,
            ).use { handle ->
                val result = com.android.internal.content.NativeLibraryHelper.copyNativeBinariesForSupportedAbi(
                    handle, dir, arrayOf("x86_64"), false, false,
                )
                if (result < 0 && result != android.content.pm.PackageManager.NO_NATIVE_LIBRARIES) {
                    throw java.io.IOException("native library installation failed: " + result)
                }
            }
            Log.i(TAG, "native libraries prepared in " + dir)
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
                if (code == android.view.KeyEvent.KEYCODE_BACK) {
                    if (action == 0) {
                        sSyntheticBackRoute = topBackRoute()
                        if (sSyntheticBackRoute != null) return
                    } else {
                        val route = sSyntheticBackRoute
                        sSyntheticBackRoute = null
                        if (route != null) {
                            val current = topBackRoute()
                            if (current != null && route.root === current.root &&
                                route.callback === current.callback
                            ) route.callback.onBackInvoked()
                            return
                        }
                    }
                }
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
            if (scanCode == 158) {
                if (action == 0) {
                    sPhysicalBackRoute = topBackRoute()
                    if (sPhysicalBackRoute != null) return
                } else {
                    val route = sPhysicalBackRoute
                    sPhysicalBackRoute = null
                    if (route != null) {
                        val current = topBackRoute()
                        if (current != null && route.root === current.root &&
                            route.callback === current.callback
                        ) route.callback.onBackInvoked()
                        return
                    }
                }
            }
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
                1 -> {
                    sPhysicalBackRoute = null
                    android.view.HostedInputDispatcher.resetKeyboard()
                }
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
                if (invokeTopBackCallback()) return
                val win: dev.artzig.HostedWindowStack.Entry? =
                    dev.artzig.HostedWindowStack.topFocusable()
                if (win != null) {
                    val onBack = win.onBack
                    if (onBack != null) {
                        onBack.run()
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

        /** Hosted WMS has no window session to invoke the callback registered by
         * WindowOnBackInvokedDispatcher, so complete that final dispatch locally. */
        private fun topBackRoute(): BackRoute? {
            val root = dev.artzig.HostedWindowStack.topFocusable()?.root ?: return null
            val callback = root.onBackInvokedDispatcher.topCallback ?: return null
            return BackRoute(root, callback)
        }

        private fun invokeTopBackCallback(): Boolean {
            val route = topBackRoute() ?: return false
            route.callback.onBackInvoked()
            return true
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
            HostContexts.dispatchWindowConfiguration(windowConfig.configuration)
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
            val app = android.app.HostedServiceClient.attachApplication(pkg)
            sContext = app
            installActivityHost()
            sProcessInitialized = true

            // --- MainActivity
            Log.i(TAG, "starting " + mainActivity)
            if (mainActivity == null) throw IllegalStateException("manifest has no launcher activity")
            launchMainActivity(mainActivity)
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
            HostContexts.bootstrap(frameworkRes)
            applyWindowConfiguration(640, 480, 120)
            if (java.security.Security.getProvider("AndroidKeyStore") == null) {
                java.security.Security.insertProviderAt(ArtzigKeyStoreProvider(), 1)
            }
            // AOSP ordering: attach first, then bindApplication (providers
            // installed + published before Application.onCreate).
            sContext = android.app.HostedServiceClient.attachApplication(pkg)
            Log.i(TAG, "headless app process up: " + pkg)
        }

        private fun launchMainActivity(className: String) {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                .setComponent(android.content.ComponentName(sContext.packageName, className))
            sContext.startActivity(intent)
            // Bootstrap precedes the host event loop. Let the stock launch transaction
            // run before returning the initialized application to the compositor.
            val deadline = android.os.SystemClock.uptimeMillis() + 5_000
            val queue = Looper.myQueue()
            while (!sActivityActive && android.os.SystemClock.uptimeMillis() < deadline) {
                val message = queue.`pollDueMessage$artzig`()
                if (message == null) android.os.SystemClock.sleep(1)
                else try { message.target.dispatchMessage(message) }
                finally { android.os.MessageQueue.`recycleDispatched$artzig`(message) }
            }
            check(sActivityActive) { "activity launch transaction timed out: $intent" }
        }

        @JvmStatic private fun installActivityHost() {
            android.app.ActivityThread.configureActivityHost(
                object : android.app.ActivityThread.HostAdapter {
                    public override fun showActivity(activity: Activity) {
                        sActivity = activity
                        sActivityActive = true
                        show(activity)
                    }

                    public override fun clearActivityWindows() {
                        sActivityActive = false
                        sSyntheticBackRoute = null
                        sPhysicalBackRoute = null
                        dev.artzig.HostedWindowStack.clear()
                        if (sRootActive) sRoot.destroy()
                        sRootActive = false
                        ArtzigAccessibilityBridge.setActivityRoot(null)
                    }

                    public override fun resizeActivityRoot(
                        activity: Activity,
                        width: Int,
                        height: Int,
                    ) {
                        if (activity === sActivity && sRootActive) sRoot.setSize(width, height)
                    }

                },
            )
        }

        @JvmStatic private fun show(a: Activity) {
            if (sRootActive) sRoot.destroy()
            sRoot = HostedWindowRoots.attachActivity(a, sWidth, sHeight)
            sRootActive = true
            ArtzigAccessibilityBridge.setActivityRoot(sRoot)
            android.view.HostedInputDispatcher.reset()
        }

/** Test-control JNI surface. The host inspector observes Android accessibility nodes. */
        @JvmStatic public fun accessibilitySnapshot(): String = ArtzigAccessibilityBridge.snapshot()

        @JvmStatic public fun notificationsSnapshot(): String = NotificationObservation.snapshot()

        @JvmStatic public fun accessibilityDiagnostics(afterRevision: Long): String =
            ArtzigAccessibilityBridge.diagnostics(
                afterRevision,
            )

        @JvmStatic public fun testDiagnostics(
            afterLogSequence: Long,
            afterIssueSequence: Long,
        ): String = dev.artzig.ArtzigTestDiagnostics.snapshot(afterLogSequence, afterIssueSequence)

        @JvmStatic public fun diagnosticLogSequence(): Long = dev.artzig.ArtzigTestDiagnostics.logSequence()

        @JvmStatic public fun diagnosticIssueSequence(): Long = dev.artzig.ArtzigTestDiagnostics.issueSequence()

        @JvmStatic public fun accessibilityRevision(): Long = ArtzigAccessibilityBridge.revision()

        @JvmStatic public fun accessibilityIdle(): Boolean = ArtzigAccessibilityBridge.isIdle()

        @JvmStatic public fun accessibilityPerform(
            expectedRevision: Long,
            windowId: Long,
            viewId: Int,
            virtualId: Int,
            action: Int,
            text: String?,
        ): Boolean =
            ArtzigAccessibilityBridge.perform(
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
            dev.artzig.HostedWindowStack.clear()
            if (sRootActive) sRoot.destroy()
            sRootActive = false
            ArtzigAccessibilityBridge.setActivityRoot(null)
            Log.i(TAG, "views detached")
            try {
                dev.artzig.HostedRenderer.destroyInstance()
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
            sSyntheticBackRoute = null
            sPhysicalBackRoute = null
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
            val wins: MutableList<dev.artzig.HostedWindowStack.Entry> =
                dev.artzig.HostedWindowStack.entries()
            for (e: dev.artzig.HostedWindowStack.Entry in wins) {
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
                    dev.artzig.HostedRenderer
                        .getInstance()
                        .render(sComposite, width, height)
                    return
                }
                dev.artzig.HostedRenderer
                    .getInstance()
                    .render(base, width, height)
                return
            }
            sComposite.setPosition(0, 0, width, height)
            val c: RecordingCanvas = sComposite.beginRecording(width, height)
            try {
                c.drawColor(0xff000000.toInt())
                if (base != null) c.drawRenderNode(base)
                for (e: dev.artzig.HostedWindowStack.Entry in wins) {
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
            dev.artzig.HostedRenderer
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
                android.view.ArtzigDisplay.configure(
                    windowConfig.displayWidth,
                    windowConfig.displayHeight,
                    windowConfig.densityDpi,
                )
                val config: Configuration = windowConfig.configuration
                // AOSP ActivityThread.handleConfigurationChanged: ResourcesManager applies the
                // configuration to every Resources; the metrics come from the display service.
                android.app.ResourcesManager.getInstance().applyConfigurationToResources(config, null)
                HostContexts.dispatchWindowConfiguration(config)
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
