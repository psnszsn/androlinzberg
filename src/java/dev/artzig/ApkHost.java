/* artzig M4: host a real APK — resources, classloader, Application, MainActivity. */
package dev.artzig;

import android.app.Activity;
import android.app.ContextImpl;
import android.content.res.ApkAssets;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ApkHost {
    private static final String TAG = "ApkHost";
    private static Activity sActivity;
    private static ContextImpl sContext;
    private static android.view.ViewRootImpl sRoot;
    private static HostedApkClassLoader sClassLoader;
    private static int sWidth, sHeight, sScale120;
    private static boolean sAppFullscreenRequested;
    private static boolean sHostFullscreenSuppressed;
    private ApkHost() {}

    /** Extract the host ABI from the APK because the desktop/bionic bridge
     * cannot mmap Android's special "apk!/lib/..." classloader paths. */
    private static String extractNativeLibraries(String apkPath) throws Exception {
        String dirPath = System.getenv("ARTZIG_NATIVE_LIB_DIR");
        if (dirPath == null || dirPath.isEmpty()) {
            throw new IllegalStateException("ARTZIG_NATIVE_LIB_DIR is not configured");
        }
        File dir = new File(dirPath);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create native library directory: " + dir);
        }
        int extracted = 0;
        try (ZipFile zip = new ZipFile(apkPath)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith("lib/x86_64/")) continue;
                String base = name.substring("lib/x86_64/".length());
                if (base.isEmpty() || base.indexOf('/') >= 0 || !base.endsWith(".so")) continue;
                File target = new File(dir, base);
                File temp = new File(dir, base + ".tmp");
                try (InputStream in = zip.getInputStream(entry);
                        FileOutputStream out = new FileOutputStream(temp)) {
                    in.transferTo(out);
                }
                if (target.exists() && !target.delete()) {
                    throw new IllegalStateException("cannot replace " + target);
                }
                if (!temp.renameTo(target)) {
                    throw new IllegalStateException("cannot install " + target);
                }
                extracted++;
            }
        }
        if (extracted == 0) throw new IllegalStateException("APK has no x86_64 native libraries");
        Log.i(TAG, "extracted " + extracted + " x86_64 native libraries to " + dir);
        return dir.getAbsolutePath();
    }

    /** Mouse-wheel scroll: dispatch ACTION_SCROLL at (x,y). ~1.0 per wheel detent. */
    public static void scroll(float x, float y, float hscroll, float vscroll) {
        if (sActivity == null) return;
        android.view.HostedInputDispatcher.dispatchScroll(sRoot, x, y, hscroll, vscroll);
    }

    /** Unbuttoned pointer motion: dispatch a hover event (hover states, tooltips)
     *  and resolve the pointer icon under (x,y). Returns PointerIcon.TYPE_*. */
    public static int hover(float x, float y) {
        return hoverAction(MotionEvent.ACTION_HOVER_MOVE, x, y);
    }

    public static int hoverAction(int action, float x, float y) {
        if (sActivity == null) return 0;
        try {
            return android.view.HostedInputDispatcher.dispatchHover(sRoot, action, x, y);
        } catch (Throwable t) {
            Log.w(TAG, "hover failed", t);
            return 0;
        }
    }

    /** Keyboard input. action 0=down 1=up; code = Android keycode or 0;
     *  unicode = codepoint for printable input (delivered on down, code==0). */
    public static void key(int action, int code, int unicode) {
        if (sActivity == null) return;
        try {
            android.view.ArtzigWindowStack.Entry win = android.view.ArtzigWindowStack.top();
            View target = win != null ? win.view : sActivity.getWindow().getDecorView();
            long now = SystemClock.uptimeMillis();
            if (code != 0) {
                android.view.KeyEvent ev = new android.view.KeyEvent(now, now,
                        action == 0 ? android.view.KeyEvent.ACTION_DOWN
                                : android.view.KeyEvent.ACTION_UP,
                        code, 0);
                target.dispatchKeyEvent(ev);
            } else if (action == 0 && unicode >= 32) {
                String str = new String(Character.toChars(unicode));
                View f = target.findFocus();
                if (f instanceof android.widget.TextView) {
                    CharSequence txt = ((android.widget.TextView) f).getText();
                    if (txt instanceof android.text.Editable) {
                        // IME-style commit at the cursor
                        android.text.Editable ed = (android.text.Editable) txt;
                        int st = android.text.Selection.getSelectionStart(ed);
                        int en = android.text.Selection.getSelectionEnd(ed);
                        if (st < 0) { st = ed.length(); en = st; }
                        ed.replace(Math.min(st, en), Math.max(st, en), str);
                        return;
                    }
                }
                if (f != null) {
                    android.view.inputmethod.EditorInfo info =
                            new android.view.inputmethod.EditorInfo();
                    android.view.inputmethod.InputConnection connection =
                            f.onCreateInputConnection(info);
                    if (connection != null && connection.commitText(str, 1)) return;
                }
                if (f == null) Log.i(TAG, "key: no focused view for text input");
                // legacy string-injection event: TextView commits getCharacters()
                android.view.KeyEvent ev = new android.view.KeyEvent(now, str,
                        android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0);
                target.dispatchKeyEvent(ev);
            }
        } catch (Throwable t) {
            Log.w(TAG, "key failed", t);
        }
    }

    /** Mouse back button / BTN_SIDE. */
    public static void back() {
        if (sActivity == null) return;
        try {
            android.view.ArtzigWindowStack.Entry win = android.view.ArtzigWindowStack.top();
            if (win != null) {
                if (win.onBack != null) {
                    win.onBack.run();
                } else {
                    // popups and other overlays: synthesize a BACK key
                    android.view.KeyEvent down = new android.view.KeyEvent(
                            android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK);
                    android.view.KeyEvent up = new android.view.KeyEvent(
                            android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK);
                    if (!(win.view.dispatchKeyEvent(down) | win.view.dispatchKeyEvent(up))) {
                        ((android.view.WindowManager) sContext.getSystemService(
                                android.content.Context.WINDOW_SERVICE)).removeView(win.view);
                    }
                }
                return;
            }
            sActivity.onBackPressed();
        } catch (Throwable t) {
            Log.w(TAG, "back failed", t);
        }
    }

    /** Explicit synthetic touchscreen input used by AZK_TAP/PRESS/SWIPE. */
    public static void motion(int action, float x, float y) {
        if (sActivity == null) return;
        android.view.HostedInputDispatcher.dispatchTouch(sRoot, action, x, y);
    }

    /** wl_pointer input: Android mouse source/tool type and full button metadata. */
    public static void mouse(int action, int buttonState, int actionButton, float x, float y) {
        if (sActivity == null) return;
        android.view.HostedInputDispatcher.dispatchMouse(
                sRoot, action, buttonState, actionButton, x, y);
    }

    static ContextImpl makeContext(String frameworkRes, String apkPath, int width, int height,
            int scale120, int themeResId, String packageName) throws Exception {
        AssetManager.createSystemAssetsInZygoteLocked(false, frameworkRes);
        AssetManager.Builder b = new AssetManager.Builder();
        b.addApkAssets(ApkAssets.loadFromPath(frameworkRes, ApkAssets.PROPERTY_SYSTEM));
        b.addApkAssets(ApkAssets.loadFromPath(apkPath));
        AssetManager am = b.build();
        DisplayMetrics dm = new DisplayMetrics();
        dm.setToDefaults();
        Configuration base = new Configuration();
        base.setToDefaults();
        android.content.res.HostedWindowConfiguration windowConfig =
                android.content.res.HostedConfigurationController.calculate(
                        base, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                        width, height, scale120);
        android.view.ArtzigDisplay.configure(windowConfig.getDisplayWidth(),
                windowConfig.getDisplayHeight(), windowConfig.getDensityDpi());
        dm.widthPixels = dm.noncompatWidthPixels = windowConfig.getDisplayWidth();
        dm.heightPixels = dm.noncompatHeightPixels = windowConfig.getDisplayHeight();
        dm.densityDpi = dm.noncompatDensityDpi = windowConfig.getDensityDpi();
        dm.density = dm.noncompatDensity = windowConfig.getDensity();
        dm.scaledDensity = dm.noncompatScaledDensity = dm.density;
        dm.xdpi = dm.noncompatXdpi = windowConfig.getDensityDpi();
        dm.ydpi = dm.noncompatYdpi = windowConfig.getDensityDpi();
        Resources res = new Resources(am, dm, windowConfig.getConfiguration());
        return new ContextImpl(res, packageName, themeResId);
    }

    /** Boot the APK: returns after Application.onCreate + MainActivity.onCreate. */
    public static void init(int width, int height, int scale120) throws Exception {
        sWidth = width; sHeight = height; sScale120 = scale120;
        android.view.WindowManagerImpl.configureWindowMetrics(
                width, height, scale120 / 120f);
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        long tFonts = SystemClock.uptimeMillis();
        android.graphics.Typeface.loadPreinstalledSystemFontMap();
        Log.i(TAG, "system font map loaded in " + (SystemClock.uptimeMillis() - tFonts) + " ms");
        String frameworkRes = System.getenv("ARTZIG_FRAMEWORK_RES");
        String apkPath = System.getenv("ARTZIG_APK");
        AssetManager.createSystemAssetsInZygoteLocked(false, frameworkRes);

        // Package parsing and Android component normalization live in the
        // framework package module, following AOSP PackageParser behavior.
        AssetManager mam = new AssetManager.Builder()
                .addApkAssets(ApkAssets.loadFromPath(apkPath)).build();
        android.content.pm.HostedPackage hostedPackage;
        try {
            hostedPackage = android.content.pm.HostedPackageParser.parse(mam);
        } finally {
            mam.close();
        }
        String pkg = hostedPackage.getPackageName();
        String appClass = hostedPackage.getApplicationClassName();
        String mainActivity = hostedPackage.getLauncherActivityName();
        int appThemeRes = hostedPackage.getApplicationTheme();
        java.util.List<android.content.pm.ProviderInfo> providers =
                hostedPackage.providersForProcess(pkg);
        Log.i(TAG, "pkg=" + pkg + " app=" + appClass + " main=" + mainActivity + " theme=0x"
                + Integer.toHexString(appThemeRes));
        android.content.pm.PackageManager.configureHostedPackage(
                hostedPackage, apkPath,
                android.os.Environment.getDataDirectory().getAbsolutePath());

        // --- classloader over the apk (+ bundled native libs)
        String libPath = extractNativeLibraries(apkPath);
        HostedApkClassLoader cl = new HostedApkClassLoader(
                apkPath, libPath, ClassLoader.getSystemClassLoader());
        sClassLoader = cl;

        // input service: reflective default-answering IInputManager so
        // InputManagerGlobal (VelocityTracker etc.) works without a real service.
        {
            android.view.InputDevice virtualKeyboard = new android.view.InputDevice.Builder()
                    .setId(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
                    .setName("artzig virtual keyboard")
                    .setDescriptor("artzig-virtual-keyboard")
                    .setSources(android.view.InputDevice.SOURCE_KEYBOARD)
                    .setKeyboardType(android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC)
                    .setKeyCharacterMap(android.view.KeyCharacterMap.obtainEmptyMap(
                            android.view.KeyCharacterMap.VIRTUAL_KEYBOARD))
                    .build();
            Class<?> iim = android.hardware.input.IInputManager.class;
            Object im = java.lang.reflect.Proxy.newProxyInstance(iim.getClassLoader(),
                    new Class<?>[] { iim }, (proxy, method, args) -> {
                if (method.getName().equals("getInputDevice")) return virtualKeyboard;
                if (method.getName().equals("getInputDeviceIds")) {
                    return new int[] { android.view.KeyCharacterMap.VIRTUAL_KEYBOARD };
                }
                Class<?> rt = method.getReturnType();
                if (rt == boolean.class) return false;
                if (rt == int.class || rt == byte.class || rt == short.class) return 0;
                if (rt == long.class) return 0L;
                if (rt == float.class) return 0f;
                if (rt == double.class) return 0d;
                if (rt == char.class) return (char) 0;
                if (rt == int[].class) return new int[0];
                if (rt.isArray()) return java.lang.reflect.Array.newInstance(rt.getComponentType(), 0);
                return null;
            });
            android.hardware.input.InputManagerGlobal.createTestSession(
                    (android.hardware.input.IInputManager) im);
        }
        // A resizable Activity starts with the current window bounds; a fixed
        // manifest orientation constrains those bounds before onCreate.
        android.content.pm.ActivityInfo mainInfo = hostedPackage.getActivities().get(mainActivity);
        int initialOrientation = mainInfo != null ? mainInfo.screenOrientation
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        boolean initialLandscape = android.content.res.HostedConfigurationController
                .resolveLandscape(initialOrientation, width, height);
        int displayWidth = initialLandscape ? Math.max(width, height) : Math.min(width, height);
        int displayHeight = initialLandscape ? Math.min(width, height) : Math.max(width, height);
        sContext = makeContext(frameworkRes, apkPath, displayWidth, displayHeight,
                scale120, appThemeRes, pkg);
        sContext.setClassLoaderOverride(cl);
        if (Thread.getDefaultUncaughtExceptionHandler() == null) {
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
                    Log.e(TAG, "uncaught exception on " + thread.getName(), throwable));
        }
        android.app.ActivityThread.configureServiceHost(sContext, cl);
        installActivityHost(hostedPackage);

        // A hosted process has no keystore daemon. Install the compatible,
        // persistent provider before Application.onCreate initializes its
        // encrypted preferences and databases.
        if (java.security.Security.getProvider("AndroidKeyStore") == null) {
            java.security.Security.insertProviderAt(new ArtzigKeyStoreProvider(), 1);
        }

        android.app.ActivityThread.bindApplicationInProcess(
                sContext, cl, appClass, providers);

        // --- MainActivity
        Log.i(TAG, "starting " + mainActivity);
        if (mainActivity == null) throw new IllegalStateException("manifest has no launcher activity");
        android.app.ActivityThread.launchMainActivity(mainActivity);
        Log.i(TAG, "activity up");
    }

    private static void installActivityHost(android.content.pm.HostedPackage hostedPackage) {
        android.app.ActivityThread.configureActivityHost(
                new android.app.ActivityThread.HostAdapter() {
                    @Override public android.content.Context getActivityContext() {
                        return sContext;
                    }
                    @Override public ClassLoader getActivityClassLoader() {
                        return sClassLoader;
                    }
                    @Override public void showActivity(Activity activity) {
                        sActivity = activity;
                        show(activity);
                    }
                    @Override public void clearActivityWindows() {
                        sActivity = null;
                        android.view.ArtzigWindowStack.clear();
                    }
                    @Override public void resizeActivityRoot(Activity activity,
                            int width, int height) {
                        if (activity == sActivity && sRoot != null) sRoot.setSize(width, height);
                    }
                },
                hostedPackage.getActivities());
    }

    private static void show(Activity a) {
        if (sRoot != null) sRoot.destroy();
        sRoot = new android.view.ViewRootImpl(sContext);
        sRoot.setView(a.getWindow().getDecorView(), sWidth, sHeight);
        android.view.ArtzigAccessibilityBridge.setActivityRoot(sRoot);
        android.view.HostedInputDispatcher.reset();
    }

    /** Test-control JNI surface. Android semantics stay in the framework bridge. */
    public static String accessibilitySnapshot() {
        return android.view.ArtzigAccessibilityBridge.snapshot();
    }

    public static String accessibilityDiagnostics(long afterRevision) {
        return android.view.ArtzigAccessibilityBridge.diagnostics(afterRevision);
    }

    public static String testDiagnostics(long afterLogSequence, long afterIssueSequence) {
        return android.util.ArtzigTestDiagnostics.snapshot(afterLogSequence, afterIssueSequence);
    }

    public static long diagnosticLogSequence() {
        return android.util.ArtzigTestDiagnostics.logSequence();
    }

    public static long diagnosticIssueSequence() {
        return android.util.ArtzigTestDiagnostics.issueSequence();
    }

    public static long accessibilityRevision() {
        return android.view.ArtzigAccessibilityBridge.revision();
    }

    public static boolean accessibilityIdle() {
        return android.view.ArtzigAccessibilityBridge.isIdle();
    }

    public static boolean accessibilityPerform(long expectedRevision, long windowId,
            int viewId, int virtualId, int action, String text) {
        return android.view.ArtzigAccessibilityBridge.perform(expectedRevision, windowId,
                viewId, virtualId, action, text);
    }

    /** Deliver component teardown and release host rendering before Wayland
     * disconnects and the host process exits. */
    public static void shutdown() {
        Log.i(TAG, "shutdown");

        // Services first: they may own decoder/audio workers and native layers.
        android.app.ActivityThread.shutdownServicesInProcess();
        Log.i(TAG, "services stopped");

        try {
            android.app.ActivityThread.shutdownActivitiesInProcess();
        } catch (Throwable t) {
            Log.w(TAG, "activity shutdown failed", t);
        }
        Log.i(TAG, "activities destroyed");

        drainLooper();
        android.view.ArtzigWindowStack.clear();
        if (sRoot != null) sRoot.destroy();
        sRoot = null;
        android.view.ArtzigAccessibilityBridge.setActivityRoot(null);
        Log.i(TAG, "views detached");
        try {
            android.view.ThreadedRenderer.destroyInstance();
        } catch (Throwable t) {
            Log.w(TAG, "renderer shutdown failed", t);
        }
        Log.i(TAG, "renderer destroyed");
        sComposite.discardDisplayList();

        try {
            android.app.ActivityThread.shutdownApplicationInProcess();
        } catch (Throwable t) {
            Log.w(TAG, "application shutdown failed", t);
        }
        sActivity = null;
        sContext = null;
        sClassLoader = null;
        android.view.HostedInputDispatcher.reset();
        sAppFullscreenRequested = false;
        sHostFullscreenSuppressed = false;
        Log.i(TAG, "shutdown complete");
    }

    private static final RenderNode sComposite = new RenderNode("artzig-window-composite");

    private static boolean appRequestsFullscreen() {
        if (sActivity == null) return false;
        sAppFullscreenRequested = android.view.HostedWindowPresentation.requestsFullscreen(
                sActivity.getWindow(), sAppFullscreenRequested);
        return sAppFullscreenRequested;
    }

    /** Whether Android has entered its fullscreen/immersive presentation.
     * Orientation locks are deliberately independent of presentation state. */
    public static boolean wantsFullscreen() {
        boolean requested = appRequestsFullscreen();
        if (!requested) sHostFullscreenSuppressed = false;
        return requested && !sHostFullscreenSuppressed;
    }

    private static boolean wantsLandscapeConfiguration(int width, int height) {
        int orientation = sActivity == null
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                : sActivity.getRequestedOrientation();
        return android.content.res.HostedConfigurationController.resolveLandscape(
                orientation, width, height);
    }

    /** Prevent an app's unchanged immersive flags from immediately undoing a
     * compositor-driven fullscreen exit. Never alter application orientation. */
    public static void onHostFullscreenChanged(boolean fullscreen) {
        if (fullscreen) {
            sHostFullscreenSuppressed = false;
        } else if (appRequestsFullscreen()) {
            sHostFullscreenSuppressed = true;
        }
    }

    public static void drawHardware(int logicalWidth, int logicalHeight,
            int bufferWidth, int bufferHeight, int scale120) throws Exception {
        int width = bufferWidth;
        int height = bufferHeight;
        if (sActivity == null) init(width, height, scale120);
        int desiredOrientation = wantsLandscapeConfiguration(width, height)
                ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        // Requested orientation is Android application state, not Wayland
        // presentation state. Deliver it immediately so applications can react
        // (and, if appropriate, request fullscreen themselves) without waiting
        // for a compositor resize that depends on that reaction.
        boolean orientationChangeReady =
                sContext.getResources().getConfiguration().orientation != desiredOrientation;
        if (width != sWidth || height != sHeight || scale120 != sScale120
                || orientationChangeReady) {
            resize(width, height, scale120);
        }
        drainLooper();
        android.view.Choreographer.getInstance().doFrame$artzig(System.nanoTime());
        // Animation callbacks may enqueue immediate Handler work that must be
        // visible to the traversal in this same compositor frame.
        drainLooper();
        RenderNode base = sRoot.doTraversal();
        java.util.List<android.view.ArtzigWindowStack.Entry> wins =
                android.view.ArtzigWindowStack.entries();
        if (wins.isEmpty()) {
            android.view.ThreadedRenderer.getInstance().render(base, width, height);
            return;
        }
        sComposite.setPosition(0, 0, width, height);
        RecordingCanvas c = sComposite.beginRecording(width, height);
        try {
            c.drawColor(0xff000000);
            if (base != null) c.drawRenderNode(base);
            for (android.view.ArtzigWindowStack.Entry e : wins) {
                try {
                    RenderNode wb = e.root.doTraversalWindow(e.lp, width, height);
                    if (wb == null) continue;
                    int windowWidth = e.root.getWidth();
                    int windowHeight = e.root.getHeight();
                    if ((e.lp.flags & android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
                        float dim = e.lp.dimAmount > 0 ? e.lp.dimAmount : 0.6f;
                        c.drawColor(((int) (dim * 255) << 24));
                    }
                    int gravity = e.lp.gravity == 0 ? android.view.Gravity.CENTER : e.lp.gravity;
                    int hg = gravity & android.view.Gravity.HORIZONTAL_GRAVITY_MASK;
                    int vg = gravity & android.view.Gravity.VERTICAL_GRAVITY_MASK;
                    int wx = hg == android.view.Gravity.CENTER_HORIZONTAL
                            ? (width - windowWidth) / 2 + e.lp.x
                            : hg == android.view.Gravity.RIGHT
                                    ? width - windowWidth - e.lp.x : e.lp.x;
                    int wy = vg == android.view.Gravity.CENTER_VERTICAL
                            ? (height - windowHeight) / 2 + e.lp.y
                            : vg == android.view.Gravity.BOTTOM
                                    ? height - windowHeight - e.lp.y : e.lp.y;
                    wx = Math.max(0, Math.min(wx, width - windowWidth));
                    wy = Math.max(0, Math.min(wy, height - windowHeight));
                    e.frame.set(wx, wy, wx + windowWidth, wy + windowHeight);
                    int save = c.save();
                    c.translate(wx, wy);
                    c.drawRenderNode(wb);
                    c.restoreToCount(save);
                } catch (Throwable t) {
                    Log.w(TAG, "overlay draw failed: " + e.view.getClass().getName(), t);
                }
            }
        } finally {
            sComposite.endRecording();
        }
        android.view.ThreadedRenderer.getInstance().render(sComposite, width, height);
    }

    /** Window resized: update metrics/config and relayout. */
    private static void resize(int width, int height, int scale120) {
        sWidth = width;
        sHeight = height;
        sScale120 = scale120;
        try {
            Resources res = sContext.getResources();
            DisplayMetrics dm = res.getDisplayMetrics();
            Configuration oldConfig = new Configuration(res.getConfiguration());
            int requestedOrientation = sActivity != null ? sActivity.getRequestedOrientation()
                    : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            android.content.res.HostedWindowConfiguration windowConfig =
                    android.content.res.HostedConfigurationController.calculate(
                            oldConfig, requestedOrientation, width, height, scale120);
            android.view.WindowManagerImpl.configureWindowMetrics(
                    width, height, windowConfig.getDensity());
            android.view.ArtzigDisplay.configure(windowConfig.getDisplayWidth(),
                    windowConfig.getDisplayHeight(), windowConfig.getDensityDpi());
            dm.widthPixels = dm.noncompatWidthPixels = windowConfig.getDisplayWidth();
            dm.heightPixels = dm.noncompatHeightPixels = windowConfig.getDisplayHeight();
            dm.densityDpi = dm.noncompatDensityDpi = windowConfig.getDensityDpi();
            dm.density = dm.noncompatDensity = windowConfig.getDensity();
            dm.scaledDensity = dm.noncompatScaledDensity = windowConfig.getDensity();
            dm.xdpi = dm.noncompatXdpi = windowConfig.getDensityDpi();
            dm.ydpi = dm.noncompatYdpi = windowConfig.getDensityDpi();
            Configuration config = windowConfig.getConfiguration();
            res.updateConfiguration(config, dm);
            android.app.ActivityThread.applyConfigurationInProcess(
                    config, windowConfig.getChanges(), width, height);
        } catch (Throwable t) {
            Log.w(TAG, "resize failed", t);
        }
        Log.i(TAG, "resized to " + width + "x" + height);
    }

    private static void drainLooper() {
        Looper looper = Looper.myLooper();
        if (looper == null) return;
        int guard = 0;
        while (guard++ < 500) {
            android.os.Message msg = looper.getQueue().pollDueMessage$artzig();
            if (msg == null) break;
            try {
                msg.getTarget().dispatchMessage(msg);
            } catch (Throwable t) {
                // one bad callback must not take the frame loop down
                Log.e(TAG, "message dispatch failed on " + msg.getTarget(), t);
            }
            msg.recycle();
        }
    }

}
