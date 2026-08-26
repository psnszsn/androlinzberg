/* artzig M4: host a real APK — resources, classloader, Application, MainActivity. */
package dev.artzig;

import android.app.Activity;
import android.app.Application;
import android.app.ContextImpl;
import android.content.res.ApkAssets;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import dalvik.system.PathClassLoader;
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
    private static long sDownTime;
    private static PathClassLoader sClassLoader;
    private static final java.util.ArrayList<android.content.ContentProvider> sProviders =
            new java.util.ArrayList<>();
    private static int sWidth, sHeight, sScale120;
    private static int sPendingNewPipeOrientation = -1;
    private static int sNewPipeRelayoutOrientation = -1;
    private static boolean sPendingNewPipeRelayout;
    private static boolean sHostFullscreenSuppressed;
    private static final java.util.ArrayDeque<Activity> sBackStack = new java.util.ArrayDeque<>();
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
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x;
        pc.y = y;
        pc.setAxisValue(MotionEvent.AXIS_HSCROLL, hscroll);
        pc.setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll);
        android.view.ArtzigWindowStack.Entry swin =
                android.view.ArtzigWindowStack.topTouchable();
        View decor;
        if (swin != null && swin.frame.contains((int) x, (int) y)) {
            pc.x = x - swin.frame.left;
            pc.y = y - swin.frame.top;
            decor = swin.view;
        } else {
            decor = sActivity.getWindow().getDecorView();
        }
        MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1,
                new MotionEvent.PointerProperties[] { pp },
                new MotionEvent.PointerCoords[] { pc },
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0);
        decor.dispatchGenericMotionEvent(ev);
        ev.recycle();
    }

    /** Unbuttoned pointer motion: dispatch a hover event (hover states, tooltips)
     *  and resolve the pointer icon under (x,y). Returns PointerIcon.TYPE_*. */
    public static int hover(float x, float y) {
        if (sActivity == null) return 0;
        try {
            android.view.ArtzigWindowStack.Entry win =
                    android.view.ArtzigWindowStack.topTouchable();
            View target;
            float tx = x, ty = y;
            if (win != null && win.frame.contains((int) x, (int) y)) {
                target = win.view;
                tx -= win.frame.left;
                ty -= win.frame.top;
            } else {
                target = sActivity.getWindow().getDecorView();
            }
            long now = SystemClock.uptimeMillis();
            MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
            pp.id = 0;
            pp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
            MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
            pc.x = tx;
            pc.y = ty;
            MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, 1,
                    new MotionEvent.PointerProperties[] { pp },
                    new MotionEvent.PointerCoords[] { pc },
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0);
            target.dispatchGenericMotionEvent(ev);
            android.view.PointerIcon icon = target.onResolvePointerIcon(ev, 0);
            ev.recycle();
            return icon != null ? icon.getType() : android.view.PointerIcon.TYPE_ARROW;
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

    // active touch-gesture target: null = activity decor
    private static android.view.ArtzigWindowStack.Entry sGestureWin;
    private static boolean sGestureWinValid;

    public static void motion(int action, float x, float y) {
        if (sActivity == null) return;
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) {
            sDownTime = now;
            sGestureWin = android.view.ArtzigWindowStack.topTouchable();
            sGestureWinValid = true;
        }
        android.view.ArtzigWindowStack.Entry win = sGestureWinValid ? sGestureWin : null;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            sGestureWinValid = false;
        }
        if (win != null) {
            // touch belongs to the top overlay window (dialog/popup): modal.
            int a = action;
            float wx = x - win.frame.left;
            float wy = y - win.frame.top;
            if (action == MotionEvent.ACTION_DOWN && !win.frame.contains((int) x, (int) y)) {
                a = MotionEvent.ACTION_OUTSIDE;
                sGestureWinValid = false;
            }
            MotionEvent ev = MotionEvent.obtain(sDownTime, now, a, wx, wy, 0);
            ev.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try {
                win.view.dispatchTouchEvent(ev);
            } catch (Throwable t) {
                Log.w(TAG, "overlay touch failed", t);
            }
            ev.recycle();
            return;
        }
        MotionEvent ev = MotionEvent.obtain(sDownTime, now, action, x, y, 0);
        ev.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        sActivity.getWindow().getDecorView().dispatchTouchEvent(ev);
        ev.recycle();
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
        float density = scale120 / 120f;
        int densityDpi = Math.round(160f * density);
        android.view.ArtzigDisplay.configure(width, height, densityDpi);
        dm.widthPixels = dm.noncompatWidthPixels = width;
        dm.heightPixels = dm.noncompatHeightPixels = height;
        dm.densityDpi = dm.noncompatDensityDpi = densityDpi;
        dm.density = dm.noncompatDensity = density;
        dm.scaledDensity = dm.noncompatScaledDensity = dm.density;
        dm.xdpi = dm.noncompatXdpi = densityDpi;
        dm.ydpi = dm.noncompatYdpi = densityDpi;
        Configuration config = new Configuration();
        config.setToDefaults();
        config.densityDpi = densityDpi;
        config.screenWidthDp = (int) (width / dm.density);
        config.screenHeightDp = (int) (height / dm.density);
        config.smallestScreenWidthDp = Math.min(config.screenWidthDp, config.screenHeightDp);
        config.orientation = width >= height
                ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        Resources res = new Resources(am, dm, config);
        return new ContextImpl(res, packageName, themeResId);
    }

    /** Boot the APK: returns after Application.onCreate + MainActivity.onCreate. */
    public static void init(int width, int height, int scale120) throws Exception {
        sWidth = width; sHeight = height; sScale120 = scale120;
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        long tFonts = SystemClock.uptimeMillis();
        android.graphics.Typeface.loadPreinstalledSystemFontMap();
        Log.i(TAG, "system font map loaded in " + (SystemClock.uptimeMillis() - tFonts) + " ms");
        String frameworkRes = System.getenv("ARTZIG_FRAMEWORK_RES");
        String apkPath = System.getenv("ARTZIG_APK");
        AssetManager.createSystemAssetsInZygoteLocked(false, frameworkRes);

        // --- manifest: package, application class, launcher activity
        AssetManager mam = new AssetManager.Builder()
                .addApkAssets(ApkAssets.loadFromPath(apkPath)).build();
        String pkg = null, appClass = null, mainActivity = null, theme = null;
        int versionCode = 0;
        String versionName = null;
        java.util.ArrayList<android.content.pm.ProviderInfo> providers = new java.util.ArrayList<>();
        android.content.pm.ProviderInfo currentProvider = null;
        java.util.ArrayList<android.content.pm.ServiceInfo> services = new java.util.ArrayList<>();
        java.util.HashMap<String, java.util.Set<String>> serviceActions = new java.util.HashMap<>();
        android.content.pm.ServiceInfo currentService = null;
        java.util.HashSet<String> currentServiceActions = null;
        XmlResourceParser p = mam.openXmlResourceParser("AndroidManifest.xml");
        String currentActivity = null;
        String currentActivityTag = null;
        boolean currentActivityEnabled = true;
        boolean isMain = false, isLauncher = false;
        int appThemeRes = 0;
        for (int ev = p.next(); ev != XmlResourceParser.END_DOCUMENT; ev = p.next()) {
            if (ev != XmlResourceParser.START_TAG) {
                if (ev == XmlResourceParser.END_TAG && currentActivityTag != null
                        && p.getName().equals(currentActivityTag)) {
                    if (currentActivityEnabled && isMain && isLauncher && mainActivity == null) {
                        mainActivity = currentActivity;
                    }
                    currentActivity = null;
                    currentActivityTag = null;
                    currentActivityEnabled = true;
                    isMain = false;
                    isLauncher = false;
                } else if (ev == XmlResourceParser.END_TAG && p.getName().equals("provider")) {
                    if (currentProvider != null) providers.add(currentProvider);
                    currentProvider = null;
                } else if (ev == XmlResourceParser.END_TAG && p.getName().equals("service")) {
                    if (currentService != null) {
                        services.add(currentService);
                        serviceActions.put(currentService.name, currentServiceActions);
                    }
                    currentService = null;
                    currentServiceActions = null;
                }
                continue;
            }
            switch (p.getName()) {
                case "manifest":
                    pkg = p.getAttributeValue(null, "package");
                    versionCode = p.getAttributeIntValue(
                            "http://schemas.android.com/apk/res/android", "versionCode", 0);
                    versionName = attr(p, "versionName");
                    break;
                case "application":
                    appClass = attr(p, "name");
                    appThemeRes = p.getAttributeResourceValue(
                            "http://schemas.android.com/apk/res/android", "theme", 0);
                    break;
                case "activity":
                    currentActivity = attr(p, "name");
                    currentActivityTag = "activity";
                    currentActivityEnabled = p.getAttributeBooleanValue(
                            "http://schemas.android.com/apk/res/android", "enabled", true);
                    break;
                case "activity-alias":
                    currentActivity = attr(p, "targetActivity");
                    currentActivityTag = "activity-alias";
                    currentActivityEnabled = p.getAttributeBooleanValue(
                            "http://schemas.android.com/apk/res/android", "enabled", true);
                    break;
                case "provider":
                    currentProvider = new android.content.pm.ProviderInfo();
                    currentProvider.name = attr(p, "name");
                    currentProvider.authority = attr(p, "authorities");
                    currentProvider.processName = attr(p, "process");
                    currentProvider.readPermission = attr(p, "readPermission");
                    currentProvider.writePermission = attr(p, "writePermission");
                    currentProvider.enabled = p.getAttributeBooleanValue(
                            "http://schemas.android.com/apk/res/android", "enabled", true);
                    currentProvider.exported = p.getAttributeBooleanValue(
                            "http://schemas.android.com/apk/res/android", "exported", false);
                    currentProvider.grantUriPermissions = p.getAttributeBooleanValue(
                            "http://schemas.android.com/apk/res/android", "grantUriPermissions", false);
                    currentProvider.multiprocess = p.getAttributeBooleanValue(
                            "http://schemas.android.com/apk/res/android", "multiprocess", false);
                    currentProvider.directBootAware = p.getAttributeBooleanValue(
                            "http://schemas.android.com/apk/res/android", "directBootAware", false);
                    currentProvider.initOrder = p.getAttributeIntValue(
                            "http://schemas.android.com/apk/res/android", "initOrder", 0);
                    currentProvider.metaData = new android.os.Bundle();
                    break;
                case "service":
                    currentService = new android.content.pm.ServiceInfo();
                    currentService.name = attr(p, "name");
                    currentService.processName = attr(p, "process");
                    currentService.permission = attr(p, "permission");
                    currentService.enabled = p.getAttributeBooleanValue(
                            "http://schemas.android.com/apk/res/android", "enabled", true);
                    currentService.exported = p.getAttributeBooleanValue(
                            "http://schemas.android.com/apk/res/android", "exported", false);
                    currentService.directBootAware = p.getAttributeBooleanValue(
                            "http://schemas.android.com/apk/res/android", "directBootAware", false);
                    currentService.foregroundServiceType = p.getAttributeIntValue(
                            "http://schemas.android.com/apk/res/android", "foregroundServiceType", 0);
                    currentService.metaData = new android.os.Bundle();
                    currentServiceActions = new java.util.HashSet<>();
                    break;
                case "meta-data":
                    android.os.Bundle componentMetaData = currentProvider != null
                            ? currentProvider.metaData
                            : currentService != null ? currentService.metaData : null;
                    if (componentMetaData != null) {
                        String name = attr(p, "name");
                        String value = attr(p, "value");
                        int resource = p.getAttributeResourceValue(
                                "http://schemas.android.com/apk/res/android", "resource", 0);
                        if (name != null && resource != 0) {
                            componentMetaData.putInt(name, resource);
                        } else if (name != null && value != null) {
                            if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                                componentMetaData.putBoolean(name, Boolean.parseBoolean(value));
                            } else {
                                componentMetaData.putString(name, value);
                            }
                        }
                    }
                    break;
                case "action":
                    String action = attr(p, "name");
                    if (currentService != null) {
                        if (action != null) currentServiceActions.add(action);
                    } else if ("android.intent.action.MAIN".equals(action)) {
                        isMain = true;
                    }
                    break;
                case "category":
                    if ("android.intent.category.LAUNCHER".equals(attr(p, "name"))) isLauncher = true;
                    break;
            }
        }
        p.close();
        mam.close();
        Log.i(TAG, "pkg=" + pkg + " app=" + appClass + " main=" + mainActivity + " theme=0x"
                + Integer.toHexString(appThemeRes));
        if (pkg == null) throw new IllegalStateException("no package in manifest");
        android.content.pm.PackageManager.configureHostedPackage(
                pkg, versionCode, versionName, apkPath,
                android.os.Environment.getDataDirectory().getAbsolutePath());
        for (java.util.Iterator<android.content.pm.ProviderInfo> it = providers.iterator();
                it.hasNext();) {
            android.content.pm.ProviderInfo provider = it.next();
            if (provider.name != null && provider.name.startsWith(".")) provider.name = pkg + provider.name;
            provider.packageName = pkg;
            if (provider.processName == null || provider.processName.isEmpty()) provider.processName = pkg;
            if (provider.processName.startsWith(":")) provider.processName = pkg + provider.processName;
            provider.applicationInfo = new android.content.pm.ApplicationInfo();
            provider.applicationInfo.packageName = pkg;
            if (!provider.enabled || !pkg.equals(provider.processName)) it.remove();
        }
        providers.sort((left, right) -> Integer.compare(right.initOrder, left.initOrder));
        android.content.pm.PackageManager.configureHostedProviders(providers);
        if (mainActivity != null && mainActivity.startsWith(".")) mainActivity = pkg + mainActivity;
        android.content.pm.PackageManager.configureHostedLauncher(mainActivity);
        java.util.HashMap<String, java.util.Set<String>> normalizedServiceActions =
                new java.util.HashMap<>();
        for (android.content.pm.ServiceInfo service : services) {
            String manifestName = service.name;
            if (service.name != null && service.name.startsWith(".")) service.name = pkg + service.name;
            service.packageName = pkg;
            if (service.processName == null || service.processName.isEmpty()) service.processName = pkg;
            if (service.processName.startsWith(":")) service.processName = pkg + service.processName;
            service.applicationInfo = new android.content.pm.ApplicationInfo();
            service.applicationInfo.packageName = pkg;
            java.util.Set<String> actions = serviceActions.get(manifestName);
            normalizedServiceActions.put(service.name,
                    actions != null ? actions : java.util.Collections.emptySet());
        }
        android.content.pm.PackageManager.configureHostedServices(services, normalizedServiceActions);
        if (appClass != null && appClass.startsWith(".")) appClass = pkg + appClass;

        // --- classloader over the apk (+ bundled native libs)
        String libPath = extractNativeLibraries(apkPath);
        PathClassLoader cl = new PathClassLoader(apkPath, libPath, ClassLoader.getSystemClassLoader());
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
        // A desktop compositor may initially tile the window wider than tall.
        // Android phone apps must not mistake that arbitrary tile shape for a
        // sensor rotation; start them in portrait until they request landscape.
        int displayWidth = Math.min(width, height);
        int displayHeight = Math.max(width, height);
        sContext = makeContext(frameworkRes, apkPath, displayWidth, displayHeight,
                scale120, appThemeRes, pkg);
        sContext.setClassLoaderOverride(cl);
        if (Thread.getDefaultUncaughtExceptionHandler() == null) {
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
                    Log.e(TAG, "uncaught exception on " + thread.getName(), throwable));
        }
        installServiceManager();
        installActivityLauncher();

        // A hosted process has no keystore daemon. Install the compatible,
        // persistent provider before Application.onCreate initializes its
        // encrypted preferences and databases.
        if (java.security.Security.getProvider("AndroidKeyStore") == null) {
            java.security.Security.insertProviderAt(new ArtzigKeyStoreProvider(), 1);
        }

        // --- Application
        Application app;
        if (appClass != null) {
            app = (Application) cl.loadClass(appClass).getDeclaredConstructor().newInstance();
        } else {
            app = new Application();
        }
        android.app.ActivityThread.setApplication(app);
        app.attach(sContext);
        sProviders.clear();
        for (android.content.pm.ProviderInfo info : providers) {
            Log.i(TAG, "installing provider " + info.name + " authority=" + info.authority);
            android.content.ContentProvider provider = (android.content.ContentProvider)
                    cl.loadClass(info.name).getDeclaredConstructor().newInstance();
            provider.attachInfo(sContext, info);
            sProviders.add(provider);
        }
        Log.i(TAG, "Application.onCreate: " + app.getClass().getName());
        app.onCreate();

        // --- MainActivity
        Log.i(TAG, "starting " + mainActivity);
        Activity initialActivity = (Activity) cl.loadClass(mainActivity)
                .getDeclaredConstructor().newInstance();
        sActivity = initialActivity;
        initialActivity.attach(sContext);
        initialActivity.performCreate(null);
        if (sActivity == initialActivity) {
            initialActivity.performStart();
            initialActivity.performResume();
            show(initialActivity);
        }
        Log.i(TAG, "activity up");
    }

    private static void installActivityLauncher() {
        // in-process activity stack for Context.startActivity / Activity.finish
        android.app.ActivityThread.setLauncher(new android.app.ActivityThread.Launcher() {
            @Override public void startActivity(android.content.Intent intent) {
                android.content.ComponentName cn = intent.getComponent();
                if (cn == null) {
                    Log.w(TAG, "startActivity without component ignored: " + intent);
                    return;
                }
                try {
                    Class<?> c = Class.forName(cn.getClassName(), true, sClassLoader);
                    Activity a = (Activity) c.getDeclaredConstructor().newInstance();
                    a.setIntent(intent);
                    a.attach(sContext);
                    a.performCreate(null);
                    a.performStart();
                    a.performResume();
                    sBackStack.push(sActivity);
                    sActivity = a;
                    show(a);
                    Log.i(TAG, "started " + cn.getClassName());
                } catch (Throwable t) {
                    Log.w(TAG, "startActivity failed: " + cn, t);
                }
            }
            @Override public void finishActivity(Activity a) {
                if (a != sActivity || sBackStack.isEmpty()) return;
                sActivity = sBackStack.pop();
                show(sActivity);
                Log.i(TAG, "finished " + a.getClass().getName()
                        + ", back to " + sActivity.getClass().getName());
            }
        });
    }

    // ---- in-process services
    private static final class ServiceRecord {
        android.app.Service service;
        android.os.IBinder binder;
        boolean bound;
        final java.util.ArrayList<android.content.ServiceConnection> conns = new java.util.ArrayList<>();
    }
    private static final java.util.HashMap<String, ServiceRecord> sServices = new java.util.HashMap<>();

    private static ServiceRecord serviceFor(android.content.ComponentName cn) throws Exception {
        ServiceRecord r = sServices.get(cn.getClassName());
        if (r == null) {
            Class<?> c = Class.forName(cn.getClassName(), true, sClassLoader);
            r = new ServiceRecord();
            r.service = (android.app.Service) c.getDeclaredConstructor().newInstance();
            r.service.attach$artzig(sContext, cn.getClassName());
            sServices.put(cn.getClassName(), r);
            r.service.onCreate();
            Log.i(TAG, "service created: " + cn.getClassName());
        }
        return r;
    }

    private static void installServiceManager() {
        android.app.ActivityThread.setServiceManager(new android.app.ActivityThread.ServiceManagerHook() {
            @Override public android.content.ComponentName startService(android.content.Intent intent) {
                android.content.ComponentName cn = intent.getComponent();
                if (cn == null) { Log.w(TAG, "startService without component: " + intent); return null; }
                try {
                    ServiceRecord r = serviceFor(cn);
                    r.service.onStartCommand(intent, 0, 1);
                    return cn;
                } catch (Throwable t) {
                    Log.w(TAG, "startService failed: " + cn, t);
                    return null;
                }
            }
            @Override public boolean stopService(android.content.Intent intent) {
                android.content.ComponentName cn = intent.getComponent();
                if (cn == null) return false;
                ServiceRecord r = sServices.remove(cn.getClassName());
                if (r == null) return false;
                try { r.service.onDestroy(); } catch (Throwable t) { Log.w(TAG, "onDestroy failed", t); }
                Log.i(TAG, "service stopped: " + cn.getClassName());
                return true;
            }
            @Override public boolean bindService(android.content.Intent intent,
                    android.content.ServiceConnection conn, int flags) {
                android.content.ComponentName cn = intent.getComponent();
                if (cn == null) { Log.w(TAG, "bindService without component: " + intent); return false; }
                try {
                    ServiceRecord r = serviceFor(cn);
                    if (!r.bound) { r.binder = r.service.onBind(intent); r.bound = true; }
                    r.conns.add(conn);
                    final android.os.IBinder b = r.binder;
                    final android.content.ComponentName fcn = cn;
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(
                            () -> conn.onServiceConnected(fcn, b));
                    return true;
                } catch (Throwable t) {
                    Log.w(TAG, "bindService failed: " + cn, t);
                    return false;
                }
            }
            @Override public void unbindService(android.content.ServiceConnection conn) {
                for (ServiceRecord r : sServices.values()) r.conns.remove(conn);
            }
        });
    }

    private static void show(Activity a) {
        if (sRoot != null) sRoot.destroy();
        sRoot = new android.view.ViewRootImpl(sContext);
        sRoot.setView(a.getWindow().getDecorView(), sWidth, sHeight);
        sGestureWin = null;
        sGestureWinValid = false;
    }

    /** Deliver component teardown and release host rendering before Wayland
     * disconnects and the host process exits. */
    public static void shutdown() {
        Log.i(TAG, "shutdown");

        // Services first: media services own decoder/audio worker threads and
        // native video layers which must stop producing frames before teardown.
        java.util.ArrayList<ServiceRecord> services =
                new java.util.ArrayList<>(sServices.values());
        sServices.clear();
        for (ServiceRecord record : services) {
            try {
                record.service.onDestroy();
            } catch (Throwable t) {
                Log.w(TAG, "service shutdown failed: " + record.service.getClass().getName(), t);
            }
            record.conns.clear();
            record.binder = null;
        }
        Log.i(TAG, "services stopped");

        java.util.ArrayList<Activity> activities = new java.util.ArrayList<>();
        if (sActivity != null) activities.add(sActivity);
        activities.addAll(sBackStack);
        sBackStack.clear();
        for (Activity activity : activities) {
            try {
                activity.performPause();
                activity.performStop();
                activity.performDestroy();
            } catch (Throwable t) {
                Log.w(TAG, "activity shutdown failed: " + activity.getClass().getName(), t);
            }
        }
        Log.i(TAG, "activities destroyed");

        drainLooper();
        android.view.ArtzigWindowStack.clear();
        if (sRoot != null) sRoot.destroy();
        sRoot = null;
        Log.i(TAG, "views detached");
        try {
            android.view.ThreadedRenderer.destroyInstance();
        } catch (Throwable t) {
            Log.w(TAG, "renderer shutdown failed", t);
        }
        Log.i(TAG, "renderer destroyed");
        sComposite.discardDisplayList();

        Application application = android.app.ActivityThread.currentApplication();
        if (application != null) {
            try { application.onTerminate(); }
            catch (Throwable t) { Log.w(TAG, "application shutdown failed", t); }
        }
        android.app.ActivityThread.setLauncher(null);
        android.app.ActivityThread.setServiceManager(null);
        android.app.ActivityThread.setApplication(null);
        sActivity = null;
        sContext = null;
        sClassLoader = null;
        sProviders.clear();
        sGestureWin = null;
        sGestureWinValid = false;
        Log.i(TAG, "shutdown complete");
    }

    private static String attr(XmlResourceParser p, String name) {
        String v = p.getAttributeValue("http://schemas.android.com/apk/res/android", name);
        return v != null ? v : p.getAttributeValue(null, name);
    }

    private static final RenderNode sComposite = new RenderNode("artzig-window-composite");

    private static boolean appRequestsFullscreen() {
        if (sActivity == null) return false;
        Boolean newPipeFullscreen = newPipePlayerFullscreen();
        if (newPipeFullscreen != null) return newPipeFullscreen;
        int visibility = sActivity.getWindow().getDecorView().getSystemUiVisibility();
        int flags = sActivity.getWindow().getAttributes().flags;
        return (visibility & android.view.View.SYSTEM_UI_FLAG_FULLSCREEN) != 0
                || (flags & android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0;
    }

    /** NewPipe temporarily reveals Android's system UI whenever playback
     * controls are visible. That must not become an xdg fullscreen toggle; its
     * player UI owns the persistent presentation state. */
    private static Boolean newPipePlayerFullscreen() {
        if (sContext == null || !"org.schabi.newpipe".equals(sContext.getPackageName())) {
            return null;
        }
        try {
            NewPipeUiState state = findNewPipeUi();
            return state == null ? null : (Boolean) state.uiClass
                    .getMethod("isFullscreen").invoke(state.ui);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Whether Android has entered its fullscreen/immersive presentation.
     * Orientation locks are deliberately independent of presentation state. */
    public static boolean wantsFullscreen() {
        boolean requested = appRequestsFullscreen();
        if (!requested) sHostFullscreenSuppressed = false;
        return requested && !sHostFullscreenSuppressed;
    }

    private static boolean wantsLandscapeConfiguration() {
        if (sActivity == null) return false;
        int orientation = sActivity.getRequestedOrientation();
        if (android.content.pm.ActivityInfo.isFixedOrientationLandscape(orientation)) return true;
        if (android.content.pm.ActivityInfo.isFixedOrientationPortrait(orientation)) return false;
        return false;
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
        int desiredOrientation = wantsLandscapeConfiguration()
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
        if (sPendingNewPipeOrientation >= 0) {
            boolean landscape = sPendingNewPipeOrientation == 1;
            boolean relayout = sPendingNewPipeRelayout;
            sPendingNewPipeOrientation = -1;
            sPendingNewPipeRelayout = false;
            notifyNewPipeOrientationChanged(landscape, relayout);
            // NewPipe posts its video reparent/height update. Run it after the
            // decor has the compositor's new dimensions, then record the
            // resulting layout in this same frame.
            drainLooper();
            base = sRoot.doTraversal();
        }
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
        boolean sizeChanged = width != sWidth || height != sHeight;
        sWidth = width;
        sHeight = height;
        sScale120 = scale120;
        try {
            Resources res = sContext.getResources();
            DisplayMetrics dm = res.getDisplayMetrics();
            int oldOrientation = res.getConfiguration().orientation;
            float density = scale120 / 120f;
            int densityDpi = Math.round(160f * density);
            boolean landscape = wantsLandscapeConfiguration();
            int displayWidth = landscape ? Math.max(width, height) : Math.min(width, height);
            int displayHeight = landscape ? Math.min(width, height) : Math.max(width, height);
            android.view.ArtzigDisplay.configure(displayWidth, displayHeight, densityDpi);
            dm.widthPixels = dm.noncompatWidthPixels = displayWidth;
            dm.heightPixels = dm.noncompatHeightPixels = displayHeight;
            dm.densityDpi = dm.noncompatDensityDpi = densityDpi;
            dm.density = dm.noncompatDensity = density;
            dm.scaledDensity = dm.noncompatScaledDensity = density;
            dm.xdpi = dm.noncompatXdpi = densityDpi;
            dm.ydpi = dm.noncompatYdpi = densityDpi;
            Configuration config = new Configuration(res.getConfiguration());
            config.densityDpi = densityDpi;
            config.screenWidthDp = (int) (displayWidth / dm.density);
            config.screenHeightDp = (int) (displayHeight / dm.density);
            config.smallestScreenWidthDp = Math.min(config.screenWidthDp, config.screenHeightDp);
            config.orientation = landscape ? Configuration.ORIENTATION_LANDSCAPE
                    : Configuration.ORIENTATION_PORTRAIT;
            res.updateConfiguration(config, dm);
            sRoot.setSize(width, height);
            sActivity.onConfigurationChanged(config);
            if (oldOrientation != Configuration.ORIENTATION_UNDEFINED
                    && oldOrientation != config.orientation) {
                sPendingNewPipeOrientation = landscape ? 1 : 0;
                sPendingNewPipeRelayout = false;
                // The orientation callback runs before xdg-shell can configure
                // the requested fullscreen/windowed size. Repeat it after that
                // resize so NewPipe calculates its player bounds from the final
                // decor dimensions as well.
                sNewPipeRelayoutOrientation = sPendingNewPipeOrientation;
            } else if (sizeChanged && sNewPipeRelayoutOrientation >= 0) {
                sPendingNewPipeOrientation = sNewPipeRelayoutOrientation;
                sPendingNewPipeRelayout = true;
                sNewPipeRelayoutOrientation = -1;
            }
        } catch (Throwable t) {
            Log.w(TAG, "resize failed", t);
        }
        Log.i(TAG, "resized to " + width + "x" + height);
    }

    /** NewPipe normally reconnects its retained PlayerService after an Activity
     * recreation and synchronizes the player UI with the new orientation. Keep
     * the live Activity and surface, and synchronize that UI state in place. */
    private static void notifyNewPipeOrientationChanged(boolean landscape, boolean relayout) {
        try {
            NewPipeUiState state = findNewPipeUi();
            if (state == null) {
                Log.w(TAG, "NewPipe orientation changed without MainPlayerUi");
                return;
            }
            Object ui = state.ui;
            Class<?> mainPlayerUi = state.uiClass;

            boolean fullscreen = (Boolean) mainPlayerUi.getMethod("isFullscreen").invoke(ui);
            if (fullscreen != landscape) {
                mainPlayerUi.getMethod("toggleFullscreen").invoke(ui);
            }
            boolean updated = (Boolean) mainPlayerUi.getMethod("isFullscreen").invoke(ui);
            if (relayout && updated == landscape) {
                // NewPipe's fullscreen fragment callback sizes the player placeholder,
                // not just its SurfaceView. Run it again after xdg-shell delivers
                // the final fullscreen/windowed dimensions so the detail content
                // starts below the final player bounds.
                Class<?> listenerClass = sClassLoader.loadClass(
                        "org.schabi.newpipe.player.event.PlayerServiceEventListener");
                Object listener = fieldValueOfType(state.player, listenerClass);
                if (listener != null) {
                    listenerClass.getMethod("onFullscreenStateChanged", boolean.class)
                            .invoke(listener, landscape);
                } else {
                    Log.w(TAG, "NewPipe relayout has no fragment listener");
                }
            }
            if (landscape) {
                Class<?> surfaceClass = sClassLoader.loadClass(
                        "org.schabi.newpipe.views.ExpandableSurfaceView");
                View surface = viewOfType(
                        sActivity.getWindow().getDecorView(), surfaceClass);
                if (surface != null) {
                    surfaceClass.getMethod("setHeights", int.class, int.class)
                            .invoke(surface, sHeight, sHeight);
                }
            }
            updated = (Boolean) mainPlayerUi.getMethod("isFullscreen").invoke(ui);
            if (updated == landscape) {
                Log.i(TAG, "set NewPipe player fullscreen=" + landscape);
            } else {
                Log.w(TAG, "NewPipe rejected fullscreen=" + landscape);
            }
        } catch (ClassNotFoundException e) {
            // This compatibility callback only applies to NewPipe.
        } catch (Throwable t) {
            Log.w(TAG, "NewPipe orientation callback failed", t);
        }
    }

    private static final class NewPipeUiState {
        final Object player;
        final Object ui;
        final Class<?> uiClass;

        NewPipeUiState(Object player, Object ui, Class<?> uiClass) {
            this.player = player;
            this.ui = ui;
            this.uiClass = uiClass;
        }
    }

    private static NewPipeUiState findNewPipeUi() throws Exception {
        if (sClassLoader == null) return null;
        Class<?> playerClass = sClassLoader.loadClass("org.schabi.newpipe.player.Player");
        Class<?> playerUiListClass = sClassLoader.loadClass(
                "org.schabi.newpipe.player.ui.PlayerUiList");
        Class<?> mainPlayerUi = sClassLoader.loadClass(
                "org.schabi.newpipe.player.ui.MainPlayerUi");
        ServiceRecord record = sServices.get("org.schabi.newpipe.player.PlayerService");
        if (record == null) return null;
        Object player = fieldValueOfType(record.service, playerClass);
        if (player == null) return null;
        Object playerUis = fieldValueOfType(player, playerUiListClass);
        if (playerUis == null) return null;
        Object optionalUi = playerUiListClass.getMethod("get", Class.class)
                .invoke(playerUis, mainPlayerUi);
        Object ui = unwrapOptional(optionalUi);
        return ui == null ? null : new NewPipeUiState(player, ui, mainPlayerUi);
    }

    /** Find an instance field by assignable runtime type. This deliberately
     * avoids field and accessor names, which may differ between APK builds. */
    private static Object fieldValueOfType(Object owner, Class<?> type)
            throws IllegalAccessException {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                if (!type.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(owner);
                if (value != null && type.isInstance(value)) return value;
            }
        }
        return null;
    }

    private static View viewOfType(View view, Class<?> type) {
        if (type.isInstance(view)) return view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); ++i) {
                View found = viewOfType(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Android desugaring may expose either java.util.Optional or
     * j$.util.Optional to reflection, depending on how the APK was built. */
    private static Object unwrapOptional(Object optional) throws Exception {
        if (optional == null) return null;
        if (optional instanceof java.util.Optional<?>) {
            return ((java.util.Optional<?>) optional).orElse(null);
        }
        return optional.getClass().getMethod("orElse", Object.class)
                .invoke(optional, new Object[] { null });
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
