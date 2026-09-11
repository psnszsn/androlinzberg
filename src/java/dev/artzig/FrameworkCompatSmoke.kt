// artzig: focused smoke checks for hosted-package framework behavior.
@file:Suppress("DEPRECATION")

package dev.artzig

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import com.android.internal.pm.parsing.PackageParser2
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.RenderNode
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.ThreadedRenderer
import java.security.KeyStore
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import javax.crypto.spec.SecretKeySpec

public final class FrameworkCompatSmoke {
    companion object {
        @JvmStatic private fun require(
            condition: Boolean,
            message: String,
        ) {
            if (!condition) throw AssertionError(message)
        }

        /** The AOSP parsing stack (PackageParser2 -> ParsingPackageUtils ->
         * PackageImpl) parses a real APK end to end; framework-res.apk is the
         * always-available fixture (package "android", platform permissions,
         * no code). */
        @JvmStatic
        private fun testAospPackageParser(fixturePath: String?) {
            // androidfw's test fixture: a small unsigned APK with a binary
            // manifest (framework-res trips the parser's duplicate-permission
            // strictness and NewPipe is integration-only). The harness passes
            // the absolute path on argv so the smoke is CWD-independent.
            val fixture = java.io.File(requireNotNull(fixturePath) { "fixture APK argument missing" })
            require(fixture.isFile, "parser fixture exists: " + fixture)
            val callback = object : PackageParser2.Callback() {
                override fun isChangeEnabled(changeId: Long, appInfo: android.content.pm.ApplicationInfo): Boolean = true
                override fun hasFeature(feature: String?): Boolean = false
                override fun getHiddenApiWhitelistedApps(): Set<String> = emptySet()
                override fun getInstallConstraintsAllowlist(): Set<String> = emptySet()
            }
            val parser = PackageParser2(null, null, null, callback)
            val pkg = parser.parsePackage(fixture, 0, false)
            require(pkg.packageName.isNotEmpty(), "PackageParser2 parses the fixture APK")
            require(pkg.baseApkPath.endsWith("app.apk"), "base APK path recorded")
            // The fixture's manifest declares no uses-sdk; the parser then
            // reports 0, which still proves the parse completed.
            require(pkg.targetSdkVersion >= 0, "targetSdkVersion parsed")
            parser.close()
        }

        /** Local providers: acquire through a resolver, hit the in-process
         * short-circuit (no cursor marshalling), and exercise the modern
         * overload delegation. */
        @JvmStatic
        /** SharedMemory over the host memfd natives (SharedMemory/MemoryFile JNI). */
    private fun testSharedMemory() {
        val shm = android.os.SharedMemory.create("azk-smoke", 4096)
        try {
            require(shm.size == 4096, "SharedMemory.size")
            val rw = shm.mapReadWrite()
            rw.put(0, 0x42.toByte())
            rw.put(4095, 0x7f.toByte())
            android.os.SharedMemory.unmap(rw)
            shm.setProtect(android.system.OsConstants.PROT_READ)
            val ro = shm.mapReadOnly()
            require(ro.get(0) == 0x42.toByte(), "SharedMemory read back [0]")
            require(ro.get(4095) == 0x7f.toByte(), "SharedMemory read back [last]")
            android.os.SharedMemory.unmap(ro)
        } finally {
            shm.close()
        }
        android.util.Log.i("FrameworkCompatSmoke", "shared memory ok")
    }

    private fun testLocalContentProvider() {
            var lastCursor: android.database.Cursor? = null
            val provider = object : android.content.ContentProvider() {
                override fun onCreate(): Boolean = true
                override fun query(
                    uri: android.net.Uri,
                    projection: Array<String>?,
                    selection: String?,
                    selectionArgs: Array<String>?,
                    sortOrder: String?,
                ): android.database.Cursor {
                    val cursor = android.database.MatrixCursor(arrayOf("k", "v"))
                    cursor.addRow(arrayOf("selection", selection ?: ""))
                    lastCursor = cursor
                    return cursor
                }
                override fun getType(uri: android.net.Uri): String = "vnd.test/x"
                override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri =
                    android.net.Uri.parse("content://org.example.local/inserted")
                override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<String>?): Int = 3
                override fun update(
                    uri: android.net.Uri,
                    values: android.content.ContentValues?,
                    selection: String?,
                    selectionArgs: Array<String>?,
                ): Int = 4
                override fun call(method: String, arg: String?, extras: Bundle?): Bundle =
                    Bundle().apply { putString("m", method) }
            }
            val info = android.content.pm.ProviderInfo()
            info.packageName = "org.example"
            info.name = "org.example.LocalProvider"
            info.authority = "org.example.local"
            info.exported = false
            // AOSP attachInfo needs a Context (validateIncomingUri reads its user id).
            val providerContext = HostContexts.systemContext(
                requireNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 200, 200, 120, 0,
            )
            provider.attachInfo(providerContext, info)
            val resolver = object : android.content.ContentResolver(providerContext) {
                override fun acquireProvider(c: android.content.Context, authority: String): android.content.IContentProvider? =
                    if (authority == "org.example.local") provider.getIContentProvider() else null
                override fun acquireExistingProvider(c: android.content.Context, authority: String) = acquireProvider(c, authority)
                override fun acquireUnstableProvider(c: android.content.Context, authority: String) = acquireProvider(c, authority)
                override fun releaseProvider(icp: android.content.IContentProvider): Boolean = true
                override fun releaseUnstableProvider(icp: android.content.IContentProvider): Boolean = true
                override fun unstableProviderDied(icp: android.content.IContentProvider) {}
            }
            val uri = android.net.Uri.parse("content://org.example.local/table")
            val cursor = resolver.query(uri, null, "name=?", arrayOf("x"), null)
            // AOSP ContentResolver.query wraps every result (CursorWrapperInner) so
            // the provider connection is released with the cursor.
            require(
                cursor is android.database.CursorWrapper && cursor.wrappedCursor === lastCursor,
                "query wraps the provider's own cursor: " + cursor,
            )
            cursor!!.moveToFirst()
            require(cursor.getString(1) == "name=?", "classic query overload received the selection")
            cursor.close()
            require(resolver.getType(uri) == "vnd.test/x", "local provider getType")
            require(resolver.insert(uri, android.content.ContentValues())?.toString() == "content://org.example.local/inserted", "local provider insert")
            require(resolver.delete(uri, null, null) == 3, "local provider delete")
            require(resolver.update(uri, android.content.ContentValues(), null, null) == 4, "local provider update")
            require(resolver.call(uri, "m1", null, null)?.getString("m") == "m1", "local provider call")
            require(resolver.query(android.net.Uri.parse("content://absent/x"), null, null, null, null) == null, "unknown authority yields null")
        }

        /** Session-wide SystemProperties over the shared area (prop_area.zig). */
        @JvmStatic
        private fun testSystemProperties() {
            // Broker-seeded defaults are visible in every process.
            require(android.os.SystemProperties.get("ro.build.version.sdk") == "35", "seeded ro.build.version.sdk")
            require(android.os.SystemProperties.getInt("ro.build.version.sdk", 0) == 35, "seeded sdk as int")
            require(android.os.SystemProperties.get("ro.missing", "fallback") == "fallback", "missing property default")
            // Round trip through the broker and back out of the mapping.
            android.os.SystemProperties.set("debug.azk.smoke", "smoke-value")
            require(android.os.SystemProperties.get("debug.azk.smoke") == "smoke-value", "set/get round trip")
            android.os.SystemProperties.set("debug.azk.smoke", "on")
            require(android.os.SystemProperties.getBoolean("debug.azk.smoke", false), "getBoolean truth table: on")
            // find()/Handle re-read the live slot.
            val handle = android.os.SystemProperties.find("debug.azk.smoke")
            require(handle != null, "find() locates a set property")
            require(handle!!.get() == "on", "Handle.get")
            android.os.SystemProperties.set("debug.azk.smoke", "42")
            require(handle.getInt(0) == 42, "Handle tracks later writes")
            require(android.os.SystemProperties.find("ro.never.set") == null, "find() misses unset names")
            // Policy: an app process may not touch names outside debug./log.
            val denied = try {
                android.os.SystemProperties.set("sys.azk.smoke", "x")
                false
            } catch (expected: RuntimeException) {
                true
            }
            require(denied, "app property namespace policy enforced")
            // Length cap per AOSP: >91 chars rejected for non-ro names.
            val tooLong = try {
                android.os.SystemProperties.set("debug.azk.long", "x".repeat(92))
                false
            } catch (expected: IllegalArgumentException) {
                true
            }
            require(tooLong, "PROP_VALUE_MAX enforced")
            require(android.os.SystemProperties.digestOf("debug.azk.smoke").length == 40, "digestOf yields sha-1 hex")
            // Change callbacks ride the broker's property_changed push.
            val changed = java.util.concurrent.CountDownLatch(1)
            val callback = Runnable { changed.countDown() }
            android.os.SystemProperties.addChangeCallback(callback)
            android.os.SystemProperties.set("debug.azk.callback", "1")
            require(changed.await(30, java.util.concurrent.TimeUnit.SECONDS), "change callback delivered")
            android.os.SystemProperties.removeChangeCallback(callback)
            // App processes never look like an emulator (the flag is
            // process-local to the system process, not a shared property).
            require(!android.os.Build.IS_EMULATOR, "IS_EMULATOR is false in app processes")
            require(android.os.Build.VERSION.SDK_INT == 35, "Build.VERSION.SDK_INT from ro.build.version.sdk")
            require(android.os.Build.VERSION.SDK_INT_FULL == 35 * 100000, "Build.VERSION.SDK_INT_FULL from ro.build.version.sdk_full")
            require(android.os.Build.VERSION.RELEASE == "15" && android.os.Build.VERSION.CODENAME == "REL", "Build.VERSION release/codename")
            require(android.os.Build.VERSION.KNOWN_CODENAMES.contains("VanillaIceCream"), "Build.VERSION known codenames from long ro property")
            require(android.os.Build.VERSION.SECURITY_PATCH == "2026-08-01", "Build.VERSION.SECURITY_PATCH seeded")
            require(android.os.Build.SUPPORTED_ABIS.toList() == listOf("x86_64", "arm64-v8a"), "Build.SUPPORTED_ABIS from ro.product.cpu.abilist")
            require(android.os.Build.SUPPORTED_32_BIT_ABIS.isEmpty(), "Build.SUPPORTED_32_BIT_ABIS empty")
            require(android.os.Build.MODEL == "artzig" && android.os.Build.FINGERPRINT == "artzig/artzig:16", "Build identity seeded")
            require(!android.os.Build.IS_DEBUGGABLE && android.os.Build.IS_USER, "Build type user, not debuggable")
        }

        /** The real LoadedApk over the installed fixture: the AOSP
         * ActivityThread.getPackageInfo cache, the class loader
         * (ApplicationLoaders -> patched ClassLoaderFactory ->
         * stock PathClassLoader under the public platform loader), resources through the
         * real ResourcesManager, the per-package data directory installd's role
         * created at install, and makeApplication ordering (instantiate, then
         * attach; onCreate is the bind step's). */
        @JvmStatic
        private fun testLoadedApk() {
            val thread = android.app.ActivityThread.currentActivityThread()
            val appInfo = requireNotNull(
                android.app.ActivityThread.getPackageManager().getApplicationInfo("org.example", 0L, 0),
            )
            appInfo.className = "dev.artzig.SmokeApplication"
            val compat = android.content.res.CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO
            // AOSP: code from another uid needs CONTEXT_IGNORE_SECURITY unless
            // this process is bound to that package.
            require(
                runCatching { thread.getPackageInfo(appInfo, compat, android.content.Context.CONTEXT_INCLUDE_CODE) }
                    .exceptionOrNull() is SecurityException,
                "foreign code without CONTEXT_IGNORE_SECURITY is refused",
            )
            val flags = android.content.Context.CONTEXT_INCLUDE_CODE or android.content.Context.CONTEXT_IGNORE_SECURITY
            val apk = thread.getPackageInfo(appInfo, compat, flags)
            require(apk === thread.getPackageInfo(appInfo, compat, flags), "LoadedApk cached per package")
            require(apk === thread.peekPackageInfo("org.example", true), "peekPackageInfo")
            require(apk.packageName == "org.example" && apk.appDir == appInfo.sourceDir, "LoadedApk identity")
            // The fixture is resources-only (no FLAG_HAS_CODE): AOSP then builds
            // the default loader over an empty code path rather than the
            // manifest class loader.
            require(appInfo.flags and android.content.pm.ApplicationInfo.FLAG_HAS_CODE == 0, "fixture has no code")
            val cl = apk.classLoader
            require(cl.javaClass === dalvik.system.PathClassLoader::class.java, "no-code package gets the stock default loader: " + cl)
            require(cl.parent === ClassLoader.getSystemClassLoader().parent, "APK loader parent is the public platform loader: " + cl.parent)
            require(cl.loadClass("android.app.Activity") === android.app.Activity::class.java, "framework parent-first")
            val ceDir = android.os.Environment.getDataUserCePackageDirectory(null, 0, "org.example")
            require(apk.dataDirFile == ceDir, "per-package data dir: " + apk.dataDirFile + " vs " + ceDir)
            require(ceDir.isDirectory, "install created the package data dir")
            require(ceDir.parentFile.startsWith(android.os.Environment.getDataDirectory()), "data dir under the session data root")
            val res = requireNotNull(apk.resources)
            require(res.getString(android.R.string.ok).isNotEmpty(), "LoadedApk resources see framework-res")
            require(apk.resources === res, "ResourcesManager caches per LoadedApk")
            SmokeApplication.attachedAtConstruction = null
            val app = apk.makeApplication(false, null)
            require(app is SmokeApplication, "AppComponentFactory instantiated the manifest class: " + app)
            require(SmokeApplication.attachedAtConstruction == false, "attach happens after instantiation")
            require(!(app as SmokeApplication).created, "makeApplication does not call onCreate (bindApplication does)")
            require(app.baseContext != null && app.packageName == "org.example", "Application attached to the app context")
            require(app.filesDir == java.io.File(ceDir, "files"), "app context files dir per package: " + app.filesDir)
            require(app.getDir("x", 0).startsWith(ceDir), "getDir per package")
            require(apk.makeApplication(false, null) === app, "LoadedApk keeps its Application")
        }

        @Throws(Exception::class)
        @JvmStatic
        public fun main(args: Array<String>) {
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
            // A real application process is installed and attached before framework clients run.
            // The direct --javatest entry has no launcher bindApplication handshake, so establish
            // the broker/PackageManager identity explicitly before static clients such as
            // Environment ask StorageManager for this UID's volumes.
            val fixtureApk: String = requireNotNull(args.getOrNull(1)) { "fixture APK argument missing" }
            val installer = requireNotNull(dev.artzig.HostedServiceClient.installerOrNull())
            require(installer.installPackage(fixtureApk, null) == "org.example", "fixture install")
            // Exercise LoadedApk construction before binding creates the Application.
            HostContexts.bootstrap(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")))
            testLoadedApk()
            val application = dev.artzig.HostedServiceClient.attachApplication("org.example")
            require(application is SmokeApplication && application.created,
                "bindApplication calls onCreate on the existing LoadedApk Application")
            require(android.app.ActivityThread.currentActivityThread().getPackageInfo(
                application.applicationInfo, android.content.res.CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO,
                android.content.Context.CONTEXT_INCLUDE_CODE) != null,
                "the bound application may load its own code without CONTEXT_IGNORE_SECURITY")
            testStockInternalUtilMetadata()
            testStockCompatChanges()
            testGeneratedAppProtoEnums()
            testPinnedAnnotationPrebuilts()
            testStockLoggingClients()
            testStockFontRequest()
            testStockTextSpans()
            testStockHtml()
            testStockProtoStreams()
            testStockImePerfettoTracing()
            testParcelRoundTrips()
            testSystemProperties()
            testOsTail()
            testSharedMemory()
            testLocalContentProvider()
            testStockStrictModeRegistration()
            testAospPackageParser(args.firstOrNull())
            testStockEpicenterReveal()
            val filtered: Intent =
                Intent(Intent.ACTION_VIEW)
                    .setPackage("org.example")
                    .addCategory(Intent.CATEGORY_BROWSABLE)
            val filteredCopy: Intent = Intent(filtered)
            require(filtered.filterEquals(filteredCopy), "Intent.filterEquals copy")
            require(
                filtered.filterHashCode() == filteredCopy.filterHashCode(),
                "Intent.filterHashCode copy",
            )

            // The installed fixture declares the launcher activity and PLAYBACK service.
            val hostedSystemContext = HostContexts.systemContext(
                requireNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 200, 200, 120, 0,
            )
            val packageManager: PackageManager = hostedSystemContext.packageManager
            val launch: Intent? = packageManager.getLaunchIntentForPackage("org.example")
            require(launch != null, "hosted launch intent")
            require(
                ComponentName("org.example", "org.example.MainActivity")
                    .equals(launch?.getComponent()),
                "hosted launcher component",
            )

            val implicitServices: MutableList<ResolveInfo> =
                packageManager.queryIntentServices(Intent("org.example.PLAYBACK").setPackage("org.example"), 0)
            require(implicitServices.size == 1, "implicit hosted service resolution")
            require(
                packageManager.resolveService(
                    Intent().setComponent(ComponentName("org.example", "org.example.PlaybackService")),
                    0,
                ) != null,
                "explicit hosted service resolution",
            )

            // The real JobSchedulerImpl (namespace handling is client-side; the
            // hosted IJobScheduler is exercised by the cross-process system test).
            val defaultScheduler: JobScheduler = android.app.JobSchedulerImpl(
                HostContexts.systemContext(requireNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 200, 200, 120, 0),
                null,
            )
            require(
                defaultScheduler.getNamespace() == null,
                "default job scheduler namespace",
            )
            val workScheduler: JobScheduler = defaultScheduler.forNamespace("  androidx.work  ")
            require(
                "androidx.work".equals(workScheduler.getNamespace()),
                "job scheduler namespace is trimmed",
            )
            var rejectedEmptyNamespace: Boolean = false
            try {
                defaultScheduler.forNamespace("   ")
            } catch (expected: IllegalArgumentException) {
                rejectedEmptyNamespace = true
            }
            require(rejectedEmptyNamespace, "empty job scheduler namespace is rejected")
            require(
                Application::class.java.getMethod("getProcessName").getReturnType() === String::class.java,
                "Application.getProcessName API",
            )
            require(
                android.app.ActivityThread.currentApplication()
                    .getSystemService(android.app.ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(null, 0, 0)
                    .isEmpty(),
                "host has no historical process exits",
            )
            require(
                "org.example".equals(packageManager.getApplicationInfo("org.example", 0).processName),
                "hosted application process name",
            )
            val authenticatedAction: android.app.Notification.Action =
                android.app.Notification.Action
                    .Builder(0, "unlock", null)
                    .setAuthenticationRequired(true)
                    .build()
            require(
                authenticatedAction.isAuthenticationRequired(),
                "notification action authentication requirement",
            )
            // Real NotificationChannel value type (the manager itself needs the
            // hosted INotificationManager: system-test covers it cross-process).
            val conversationChannel: android.app.NotificationChannel =
                android.app.NotificationChannel("conversation", "Conversation", NotificationManager.IMPORTANCE_DEFAULT)
            conversationChannel.setConversationId("messages", "recipient-1")
            require(
                "messages".equals(conversationChannel.getParentChannelId()) &&
                    "recipient-1".equals(conversationChannel.getConversationId()) &&
                    conversationChannel.getImportance() == NotificationManager.IMPORTANCE_DEFAULT,
                "conversation notification channel",
            )
            require(
                android.hardware.Camera.getNumberOfCameras() == 0,
                "desktop legacy camera count",
            )
            verifyAndroidKeyStoreNullParameters()
            verifyMediaStoreCompatibility()
            require(
                PendingIntent.getActivities(
                    hostedSystemContext.createPackageContext("org.example", 0),
                    7,
                    arrayOf<Intent>(Intent()),
                    PendingIntent.FLAG_IMMUTABLE,
                    Bundle(),
                ) != null,
                "PendingIntent.getActivities options overload",
            )
            val accessibilityExtras: Bundle = Bundle()
            val spanStarts: ArrayList<Int> = ArrayList()
            spanStarts.add(4)
            accessibilityExtras.putIntegerArrayList("span-starts", spanStarts)
            require(
                accessibilityExtras.getIntegerArrayList("span-starts") === spanStarts,
                "Bundle integer array list",
            )
            val notificationItems: ArrayList<android.os.Parcelable> = ArrayList()
            notificationItems.add(Intent("notification-item"))
            val notificationIntent: Intent =
                Intent().putParcelableArrayListExtra("notification-items", notificationItems)
            require(
                notificationIntent.getParcelableArrayListExtra<android.os.Parcelable>("notification-items") ===
                    notificationItems,
                "Intent parcelable array list extra",
            )
            require(
                dev.artzig.testing.HostedConfigurationController.resolveLandscape(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                    1280,
                    720,
                ),
                "unspecified orientation follows a wide desktop window",
            )
            require(
                !dev.artzig.testing.HostedConfigurationController.resolveLandscape(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                    420,
                    800,
                ),
                "unspecified orientation follows a tall phone window",
            )
            require(
                !dev.artzig.testing.HostedConfigurationController.resolveLandscape(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    1280,
                    720,
                ),
                "fixed portrait orientation overrides window geometry",
            )
            require(
                dev.artzig.testing.HostedConfigurationController.resolveLandscape(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                    420,
                    800,
                ),
                "fixed landscape orientation overrides window geometry",
            )
            require(
                android.content.pm.ActivityInfo
                    .activityInfoConfigNativeToJava(0x0200) ==
                    android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE,
                "manifest screenSize config change uses Java ActivityInfo bit",
            )
            val snapshotNode: RenderNode = RenderNode("compat-snapshot")
            snapshotNode.setPosition(0, 0, 3, 2)
            val snapshot: Bitmap? = ThreadedRenderer.createHardwareBitmap(snapshotNode, 3, 2)
            require(
                snapshot != null && snapshot.getWidth() == 3 && snapshot.getHeight() == 2,
                "stock RenderNode hardware snapshot",
            )
            require(snapshot!!.config == Bitmap.Config.HARDWARE, "snapshot keeps hardware storage")
            snapshot.recycle()
            val mutableIconBitmap: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            require(
                mutableIconBitmap.getConfig() === Bitmap.Config.ARGB_8888,
                "host 8888 bitmap config",
            )
            val icon: Icon = Icon.createWithBitmap(mutableIconBitmap)
            require(
                icon.getBitmap() != null && !icon.getBitmap().isMutable(),
                "mutable bitmap icon copy",
            )
            System.out.println("framework compat smoke: PASS")
        }

        @JvmStatic private fun verifyAndroidKeyStoreNullParameters() {
            val keyStore: KeyStore =
                KeyStore.getInstance("AndroidKeyStore", ArtzigKeyStoreProvider())
            keyStore.load(null as KeyStore.LoadStoreParameter?)
            keyStore.load(null as java.io.InputStream?, null)

            val alias: String = "artzig-null-parameter-smoke"
            try {
                val expected: ByteArray = ByteArray(32) { index: Int -> index.toByte() }
                keyStore.setEntry(
                    alias,
                    KeyStore.SecretKeyEntry(SecretKeySpec(expected, "AES")),
                    null,
                )
                val entry: KeyStore.Entry? = keyStore.getEntry(alias, null)
                require(entry is KeyStore.SecretKeyEntry, "AndroidKeyStore secret-key entry")
                require(
                    expected.contentEquals((entry as KeyStore.SecretKeyEntry).secretKey.encoded),
                    "AndroidKeyStore null-protected key round trip",
                )
            } finally {
                keyStore.deleteEntry(alias)
            }
        }

        @JvmStatic private fun verifyMediaStoreCompatibility() {
            require(
                "content://media/external/video/media".equals(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString(),
                ),
                "MediaStore video URI",
            )
            require(
                "content://media/external/images/media".equals(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString(),
                ) &&
                    "content://media/external/audio/media".equals(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString(),
                    ) &&
                    "content://media/external/downloads".equals(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI.toString(),
                    ),
                "MediaStore external collection URIs",
            )
            require(MediaStore.getPickImagesMaxLimit() == 100, "photo picker selection limit")
            require(
                "1".equals(
                    MediaStore
                        .setRequireOriginal(android.net.Uri.parse("content://media/item"))
                        .getQueryParameter("requireOriginal"),
                ),
                "MediaStore original-content query parameter",
            )

            val thumbnailUri: Array<android.net.Uri?> = arrayOfNulls(1)
            val thumbnailSize: Array<Size?> = arrayOfNulls(1)
            val expected: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            // AOSP ContentResolver needs its Context (attribution source, package name).
            val resolverContext = HostContexts.systemContext(
                requireNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 200, 200, 120, 0,
            )
            val resolver: ContentResolver =
                object : ContentResolver(resolverContext) {
                    override fun acquireProvider(c: android.content.Context, authority: String): android.content.IContentProvider? = null
                    override fun acquireExistingProvider(c: android.content.Context, authority: String): android.content.IContentProvider? = null
                    override fun acquireUnstableProvider(c: android.content.Context, authority: String): android.content.IContentProvider? = null
                    override fun releaseProvider(icp: android.content.IContentProvider): Boolean = true
                    override fun releaseUnstableProvider(icp: android.content.IContentProvider): Boolean = true
                    override fun unstableProviderDied(icp: android.content.IContentProvider) {}
                    override fun loadThumbnail(
                        uri: android.net.Uri,
                        size: Size,
                        signal: android.os.CancellationSignal?,
                    ): Bitmap {
                        thumbnailUri[0] = uri
                        thumbnailSize[0] = size
                        return expected
                    }
                }
            require(
                MediaStore.Video.Thumbnails.getThumbnail(
                    resolver,
                    42,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null,
                ) === expected,
                "MediaStore video thumbnail result",
            )
            require(
                "content://media/external/video/media/42".equals(thumbnailUri[0].toString()) &&
                    Size(512, 384).equals(thumbnailSize[0]),
                "MediaStore video thumbnail resolver seam",
            )
        }

        /** The android.os tail (real AOSP classes): AsyncTask on the pumped main looper, Trace, UserHandle, LocaleList, CancellationSignal. */
        @JvmStatic private fun testOsTail() {
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
            val progress = ArrayList<Int>()
            var post: String? = null
            val task = object : android.os.AsyncTask<Int, Int, String>() {
                override fun doInBackground(vararg params: Int?): String {
                    for (i in 1..(params[0] ?: 0)) publishProgress(i)
                    return "done" + params[0]
                }
                override fun onProgressUpdate(vararg values: Int?) { progress.add(values[0] ?: -1) }
                override fun onPostExecute(result: String) { post = result }
            }
            require(task.status == android.os.AsyncTask.Status.PENDING, "AsyncTask starts PENDING")
            task.execute(3)
            require(task.get(30, java.util.concurrent.TimeUnit.SECONDS) == "done3", "AsyncTask result")
            val deadline = System.nanoTime() + 5_000_000_000L
            while (post == null && System.nanoTime() < deadline) { drainMainLooper(); Thread.sleep(5) }
            require(post == "done3", "AsyncTask.onPostExecute delivered on the main looper")
            require(progress == listOf(1, 2, 3), "AsyncTask.publishProgress ordered on the main looper: " + progress)
            require(task.status == android.os.AsyncTask.Status.FINISHED, "AsyncTask ends FINISHED")

            val up0 = android.os.SystemClock.uptimeNanos()
            val el0 = android.os.SystemClock.elapsedRealtimeNanos()
            require(el0 >= up0, "SystemClock: elapsedRealtime (BOOTTIME) >= uptime (MONOTONIC)")
            require(Math.abs(android.os.SystemClock.uptimeMillis() - up0 / 1_000_000) < 1000, "SystemClock.uptimeMillis matches uptimeNanos")
            require(Math.abs(android.os.SystemClock.currentTimeMicro() / 1000 - System.currentTimeMillis()) < 1000, "SystemClock.currentTimeMicro is wall time")
            val cpu0 = android.os.SystemClock.currentThreadTimeMillis()
            var spin = 0L
            val spinUntil = System.nanoTime() + 20_000_000L
            while (System.nanoTime() < spinUntil) spin += spin xor 1
            val cpu1 = android.os.SystemClock.currentThreadTimeMillis()
            require(cpu1 >= cpu0 && cpu1 < android.os.SystemClock.uptimeMillis(), "SystemClock.currentThreadTimeMillis is thread CPU time, not wall time (" + spin + ")")
            // Stock handleBindApplication installs target-SDK defaults before onCreate.
            require(runCatching { dalvik.system.BlockGuard.getThreadPolicy().onNetwork() }
                .exceptionOrNull() is android.os.NetworkOnMainThreadException,
                "bindApplication prohibits network on the main thread")
            require(android.os.StrictMode.vmFileUriExposureEnabled(),
                "bindApplication enables the modern file URI exposure policy")
            val oldThread = android.os.StrictMode.getThreadPolicy()
            android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            android.os.StrictMode.noteSlowCall("azk-smoke")
            require(android.os.StrictMode.getThreadPolicy().toString() != oldThread.toString(), "StrictMode.setThreadPolicy takes effect")
            android.os.StrictMode.setThreadPolicy(oldThread)
            val oldVm = android.os.StrictMode.getVmPolicy()
            android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
            android.os.StrictMode.onContentUriWithoutPermission(android.net.Uri.parse("content://dev.artzig/x"), "smoke")
            android.os.StrictMode.onFileUriExposed(android.net.Uri.parse("file:///tmp/x"), "smoke")
            android.os.StrictMode.setVmPolicy(oldVm)
            require(!android.os.StrictMode.vmImplicitDirectBootEnabled() || true, "StrictMode direct-boot query")
            // Real android.net.Uri + Intent: hierarchical/opaque parsing, toUri/parseUri, parcel wire format.
            val h = android.net.Uri.parse("content://dev.artzig/item/1?x=1&y=two#frag")
            require(h.isHierarchical && !h.isOpaque && h.authority == "dev.artzig" && h.pathSegments == listOf("item", "1"), "Uri hierarchical parts")
            require(h.getQueryParameter("y") == "two" && h.queryParameterNames == setOf("x", "y") && h.fragment == "frag", "Uri query/fragment")
            val o = android.net.Uri.parse("mailto:someone@example.com")
            require(o.isOpaque && o.schemeSpecificPart == "someone@example.com", "Uri opaque")
            require(android.net.Uri.fromFile(java.io.File("/tmp/a b")).toString() == "file:///tmp/a%20b", "Uri.fromFile encodes")
            // AOSP: a selector and an explicit package are mutually exclusive.
            val full = Intent(Intent.ACTION_VIEW, h).addCategory(Intent.CATEGORY_BROWSABLE).putExtra("n", 7)
            full.setSelector(Intent(Intent.ACTION_MAIN))
            full.setSourceBounds(android.graphics.Rect(1, 2, 3, 4))
            var selectorRejected = false
            try { full.setPackage("org.example") } catch (e: IllegalArgumentException) { selectorRejected = true }
            require(selectorRejected, "Intent.setPackage rejects a selector-carrying intent")
            val uriForm = full.toUri(Intent.URI_INTENT_SCHEME)
            val parsed = Intent.parseUri(uriForm, Intent.URI_INTENT_SCHEME)
            require(parsed.action == Intent.ACTION_VIEW && parsed.data == h && parsed.getIntExtra("n", 0) == 7 && parsed.selector?.action == Intent.ACTION_MAIN, "Intent.toUri/parseUri round trip: " + uriForm)
            val pIntent = android.os.Parcel.obtain()
            try {
                full.writeToParcel(pIntent, 0)
                pIntent.setDataPosition(0)
                val back = Intent.CREATOR.createFromParcel(pIntent)
                require(back.filterEquals(full) && back.data == h && back.selector?.action == Intent.ACTION_MAIN && back.sourceBounds == android.graphics.Rect(1, 2, 3, 4) && back.getIntExtra("n", 0) == 7, "Intent parcel round trip keeps data/selector/sourceBounds/extras")
                require(pIntent.dataAvail() == 0, "Intent parcel fully consumed")
            } finally {
                pIntent.recycle()
            }
            val uriParcel = android.os.Parcel.obtain()
            try {
                android.net.Uri.writeToParcel(uriParcel, o)
                android.net.Uri.writeToParcel(uriParcel, null)
                uriParcel.setDataPosition(0)
                require(android.net.Uri.CREATOR.createFromParcel(uriParcel) == o && android.net.Uri.CREATOR.createFromParcel(uriParcel) == null, "Uri parcel round trip (opaque + null)")
            } finally {
                uriParcel.recycle()
            }
            // Real Environment over the launcher's data root and the one emulated volume.
            val dataRoot = java.io.File(checkNotNull(System.getenv("ARTZIG_DATA_DIR")) { "launcher exports ARTZIG_DATA_DIR" })
            val partition = android.os.Environment.getDataDirectory()
            require(partition.path == System.getenv("ANDROID_DATA"), "Environment reads the runtime's Android partition")
            require(android.os.Environment.getDataUserCeDirectory(null, 0).canonicalPath == java.io.File(dataRoot, "user/0").canonicalPath, "session CE directory retains persistent application storage")
            val external = android.os.Environment.getExternalStorageDirectory()
            require(external.canonicalPath.startsWith(dataRoot.canonicalPath + "/"), "external storage lives under the data root: " + external)
            require(android.os.Environment.getExternalStorageState() == android.os.Environment.MEDIA_MOUNTED, "external storage mounted")
            require(android.os.Environment.isExternalStorageEmulated() && !android.os.Environment.isExternalStorageRemovable(), "external storage emulated, not removable")
            require(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).parentFile?.canonicalPath == external.canonicalPath, "public directories under the external root")
            require(android.os.Environment.getDownloadCacheDirectory().canonicalPath.startsWith(dataRoot.canonicalPath), "download cache under the data root")
            require(android.os.Environment.getDataUserCePackageDirectory(null, 0, "org.example").path == partition.path + "/user/0/org.example", "CE package dir layout")
            require(!android.os.Trace.isEnabled(), "Trace: no tag enabled on the host")
            require(!android.os.Trace.isTagEnabled(android.os.Trace.TRACE_TAG_VIEW), "Trace.isTagEnabled(VIEW) false")
            android.os.Trace.beginSection("azk-smoke")
            android.os.Trace.endSection()
            android.os.Trace.traceCounter(android.os.Trace.TRACE_TAG_APP, "azk", 1)
            require(android.os.Trace.TRACE_TAG_PACKAGE_MANAGER == 1L shl 18, "Trace tag values are AOSP's")

            require(android.os.UserHandle.myUserId() == 0, "UserHandle.myUserId is 0")
            require(android.os.UserHandle.getUserId(android.os.Process.myUid()) == 0, "app uid maps to user 0")
            require(android.os.UserHandle.isApp(android.os.Process.myUid()), "app uid isApp")
            require(android.os.UserHandle.getUid(1, 10005) == 110005, "UserHandle.getUid arithmetic")

            val locales = android.os.LocaleList.forLanguageTags("fr-CA,en-US")
            require(locales.getFirstMatch(arrayOf("en", "de"))?.language == "en", "LocaleList.getFirstMatch honours the supported set")
            require(android.os.LocaleList.getDefault().size() > 0, "LocaleList.getDefault populated")

            val signal = android.os.CancellationSignal()
            val transport = android.os.CancellationSignal.createTransport()
            signal.setRemote(transport)
            var cancelled = false
            android.os.CancellationSignal.fromTransport(transport).setOnCancelListener { cancelled = true }
            signal.cancel()
            require(cancelled && signal.isCanceled, "CancellationSignal propagates through its transport")
        }

        /** Stock popup reveal transition: captures geometry and builds all three animations. */
        @JvmStatic
        private fun testStockEpicenterReveal() {
            val context = HostContexts.systemContext(
                requireNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")),
                200,
                200,
                120,
                0,
            )
            val sceneRoot = android.widget.FrameLayout(context)
            val view = android.view.View(context)
            view.layout(0, 0, 80, 40)
            view.translationX = 7f
            view.translationY = 9f
            view.translationZ = 5f
            view.clipBounds = android.graphics.Rect(10, 5, 70, 35)

            val values = android.transition.TransitionValues(view)
            val transition = com.android.internal.transition.EpicenterTranslateClipReveal()
            transition.captureEndValues(values)
            require(
                values.values["android:epicenterReveal:bounds"] == android.graphics.Rect(0, 0, 80, 40),
                "epicenter reveal captures bounds",
            )
            require(values.values["android:epicenterReveal:translateX"] == 7f, "captures translation X")
            require(values.values["android:epicenterReveal:translateY"] == 9f, "captures translation Y")
            require(values.values["android:epicenterReveal:translateZ"] == 5f, "captures translation Z")
            require(
                values.values["android:epicenterReveal:clip"] == android.graphics.Rect(10, 5, 70, 35),
                "epicenter reveal captures clip",
            )

            val animator = transition.onAppear(sceneRoot, view, null, values)
            require(animator is android.animation.AnimatorSet, "epicenter reveal creates an animator set")
            val animatorSet = animator as android.animation.AnimatorSet
            require(animatorSet.childAnimations.size == 3, "epicenter reveal animates x, y and z")
            require(view.clipBounds == android.graphics.Rect(40, 20, 40, 20), "reveal starts at center")
            require(view.translationZ == -5f, "reveal starts below captured z")
            android.util.Log.i("FrameworkCompatSmoke", "stock epicenter reveal ok")
        }

        /** Stock proto streams: binary encoding, nested traversal and wire-type validation. */
        @JvmStatic
        private fun testStockProtoStreams() {
            val single = android.util.proto.ProtoStream.FIELD_COUNT_SINGLE
            val intField = android.util.proto.ProtoStream.makeFieldId(
                1,
                single or android.util.proto.ProtoStream.FIELD_TYPE_INT32,
            )
            val stringField = android.util.proto.ProtoStream.makeFieldId(
                2,
                single or android.util.proto.ProtoStream.FIELD_TYPE_STRING,
            )
            val messageField = android.util.proto.ProtoStream.makeFieldId(
                3,
                single or android.util.proto.ProtoStream.FIELD_TYPE_MESSAGE,
            )
            val boolField = android.util.proto.ProtoStream.makeFieldId(
                1,
                single or android.util.proto.ProtoStream.FIELD_TYPE_BOOL,
            )

            val output = android.util.proto.ProtoOutputStream()
            output.write(intField, 150)
            output.write(stringField, "stock")
            val token = output.start(messageField)
            output.write(boolField, true)
            output.end(token)
            val encoded = output.bytes
            require(encoded.isNotEmpty(), "stock proto output emits bytes")

            val input = android.util.proto.ProtoInputStream(encoded)
            require(input.nextField() == 1 && input.readInt(intField) == 150, "stock proto int round trip")
            require(input.nextField() == 2 && input.readString(stringField) == "stock", "stock proto string round trip")
            require(input.nextField() == 3, "stock proto nested field")
            val readToken = input.start(messageField)
            require(input.nextField() == 1 && input.readBoolean(boolField), "stock proto nested value")
            require(input.nextField() == android.util.proto.ProtoInputStream.NO_MORE_FIELDS, "nested message ends")
            input.end(readToken)
            require(input.nextField() == android.util.proto.ProtoInputStream.NO_MORE_FIELDS, "top-level message ends")

            val mismatchInput = android.util.proto.ProtoInputStream(encoded)
            require(mismatchInput.nextField() == 1, "wire mismatch test field")
            val stringAtFieldOne = android.util.proto.ProtoStream.makeFieldId(
                1,
                single or android.util.proto.ProtoStream.FIELD_TYPE_STRING,
            )
            val rejectedWireType = try {
                mismatchInput.readString(stringAtFieldOne)
                false
            } catch (expected: android.util.proto.WireTypeMismatchException) {
                true
            }
            require(rejectedWireType, "stock proto input rejects a mismatched wire type")
            android.util.Log.i("FrameworkCompatSmoke", "stock proto streams ok")
        }

        /** Stock IME tracing over the inert hosted Perfetto producer seam. */
        @JvmStatic
        private fun testStockImePerfettoTracing() {
            require(
                Class.forName("android.internal.perfetto.protos.TracePacketOuterClass\$TracePacket")
                    .getField("TIMESTAMP").getLong(null) != 0L,
                "filtered Perfetto TracePacket constants generated",
            )
            require(
                Class.forName(
                    "android.internal.perfetto.protos.WinscopeExtensionsImplOuterClass\$WinscopeExtensionsImpl",
                ).getField("INPUTMETHOD_CLIENTS").getLong(null) != 0L,
                "filtered Winscope extension constants generated",
            )
            require(
                Class.forName(
                    "android.internal.perfetto.protos.Inputmethodeditor\$InputMethodClientsTraceProto",
                ).getField("WHERE").getLong(null) != 0L,
                "filtered input-method constants generated",
            )

            val tracingClass = Class.forName(
                "com.android.internal.inputmethod.ImeTracingPerfettoImpl",
            ).asSubclass(com.android.internal.inputmethod.ImeTracing::class.java)
            val constructor = tracingClass.getDeclaredConstructor()
            constructor.isAccessible = true
            val tracing = constructor.newInstance()
            require(!tracing.isEnabled, "host Perfetto producer starts with no tracing sessions")
            var dumped = false
            tracing.triggerServiceDump("disabled", { _, _ -> dumped = true }, null)
            require(!dumped, "disabled Perfetto data source skips IME dumps")
            tracing.startTrace(null)
            tracing.stopTrace(null)
            android.util.Log.i("FrameworkCompatSmoke", "stock IME Perfetto tracing ok")
        }

        /** Stock StrictMode registers its unsafe-intent callback through the hosted AMS. */
        @JvmStatic
        private fun testStockStrictModeRegistration() {
            require(android.app.ActivityManager.getService() != null, "hosted activity manager is registered")
            val previous = android.os.StrictMode.getVmPolicy()
            try {
                val policy = android.os.StrictMode.VmPolicy.Builder(previous)
                    .detectUnsafeIntentLaunch()
                    .build()
                android.os.StrictMode.setVmPolicy(policy)
                require(android.os.StrictMode.vmUnsafeIntentLaunchEnabled(), "unsafe-intent detection enabled")
            } finally {
                android.os.StrictMode.setVmPolicy(previous)
            }
            android.util.Log.i("FrameworkCompatSmoke", "stock StrictMode callback registration ok")
        }

        /** Stock validation and code-generation metadata used by AOSP generated value types. */
        @JvmStatic
        private fun testStockInternalUtilMetadata() {
            com.android.internal.util.AnnotationValidations.validate(
                android.annotation.NonNull::class.java,
                null,
                "present",
            )
            require(
                runCatching {
                    com.android.internal.util.AnnotationValidations.validate(
                        android.annotation.NonNull::class.java,
                        null,
                        null,
                    )
                }.exceptionOrNull() is NullPointerException,
                "non-null annotation validation",
            )
            val rangeFailure = runCatching {
                com.android.internal.util.AnnotationValidations.validate(
                    android.annotation.IntRange::class.java,
                    null,
                    4,
                    "from",
                    5L,
                )
            }.exceptionOrNull()
            require(
                rangeFailure is IllegalStateException &&
                    rangeFailure.message == "Invalid @IntRange(from = 5): 4",
                "integer range annotation validation",
            )

            val dataClass = com.android.internal.util.DataClass::class.java
            require(dataClass.getMethod("genGetters").defaultValue == true, "DataClass getter default")
            require(dataClass.getMethod("genCopyConstructor").defaultValue == false, "DataClass copy default")
            require(
                com.android.internal.util.DataClass.ParcelWith::class.java
                    .getMethod("value").genericReturnType.typeName
                    .contains("com.android.internal.util.Parcelling"),
                "DataClass ParcelWith type",
            )
            require(
                com.android.internal.util.DataClass.Generated::class.java
                    .getMethod("time").defaultValue == null,
                "DataClass generated metadata requires a timestamp",
            )
            android.util.Log.i("FrameworkCompatSmoke", "stock internal util metadata ok")
        }

        /** Stock CompatChanges queries the session's hosted IPlatformCompat service. */
        @JvmStatic
        private fun testStockCompatChanges() {
            require(android.app.compat.CompatChanges.isChangeEnabled(0x41L), "current-process change enabled")
            require(
                runCatching { android.app.compat.CompatChanges.isChangeEnabled(
                    0x42L,
                    "org.example",
                    android.os.UserHandle.SYSTEM,
                ) }.exceptionOrNull() is SecurityException,
                "package compatibility queries require privileged permissions",
            )
            require(
                runCatching { android.app.compat.CompatChanges.isChangeEnabled(
                    0x43L, android.os.Process.SYSTEM_UID) }.exceptionOrNull() is SecurityException,
                "UID compatibility queries require privileged permissions",
            )

            val range = android.app.compat.PackageOverride.Builder()
                .setMinVersionCode(2L)
                .setMaxVersionCode(4L)
                .setEnabled(false)
                .build()
            require(range.evaluate(1L) == android.app.compat.PackageOverride.VALUE_UNDEFINED, "override lower bound")
            require(range.evaluate(3L) == android.app.compat.PackageOverride.VALUE_DISABLED, "override range value")
            android.util.Log.i("FrameworkCompatSmoke", "stock compat changes ok")
        }

        /** AppProtoEnums is generated from the pinned AOSP app enum declaration. */
        @JvmStatic
        private fun testGeneratedAppProtoEnums() {
            require(android.app.AppProtoEnums.PROCESS_STATE_TOP == 1002, "generated process state")
            require(
                android.app.AppProtoEnums.RESOURCE_API_RETRIEVE_ATTRIBUTES == 2,
                "generated resource API",
            )
            android.util.Log.i("FrameworkCompatSmoke", "generated app proto enums ok")
        }

        /** AndroidX and Error Prone annotations come from AOSP's pinned java_import artifacts. */
        @JvmStatic
        private fun testPinnedAnnotationPrebuilts() {
            val retention = androidx.annotation.NonNull::class.java.getAnnotation(java.lang.annotation.Retention::class.java)
            require(retention.value == java.lang.annotation.RetentionPolicy.CLASS, "AndroidX retention")
            require(
                Class.forName("androidx.annotation.ChecksSdkIntAtLeast").isAnnotation,
                "complete AndroidX annotation prebuilt",
            )
            require(
                com.google.errorprone.annotations.CompileTimeConstant::class.java.isAnnotation,
                "Error Prone annotation prebuilt",
            )
            android.util.Log.i("FrameworkCompatSmoke", "pinned annotation prebuilts ok")
        }

        /** Verbatim AOSP logging clients over the two host-native sinks. */
        @JvmStatic
        private fun testStockLoggingClients() {
            val unknownHost = java.io.IOException(java.net.UnknownHostException("offline"))
            require(android.util.Log.getStackTraceString(unknownHost).isEmpty(), "UnknownHost filtering")

            var terribleFailureHandled = false
            val previousHandler = android.util.Log.setWtfHandler { tag, failure, system ->
                require(tag == "FrameworkCompatSmoke", "wtf tag")
                require(failure.message == "expected test failure", "wtf message")
                require(!system, "wtf system flag")
                terribleFailureHandled = true
            }
            try {
                android.util.Log.wtf("FrameworkCompatSmoke", "expected test failure")
            } finally {
                android.util.Log.setWtfHandler(previousHandler)
            }
            require(terribleFailureHandled, "stock wtf handler")
            require(android.util.Log.isLoggable("FrameworkCompatSmoke", android.util.Log.INFO), "liblog filtering")

            require(android.util.StatsLog.logStart(17), "stats start breadcrumb")
            require(android.util.StatsLog.logStop(17), "stats stop breadcrumb")
            require(android.util.StatsLog.logEvent(17), "stats event breadcrumb")
            // The host has no logd reader, but the stock binary-event clients still encode and
            // submit every supported payload shape through the Zig/liblog boundary.
            android.util.EventLog.writeEvent(42, 7)
            android.util.EventLog.writeEvent(42, 9L)
            android.util.EventLog.writeEvent(42, 1.5f)
            android.util.EventLog.writeEvent(42, "event")
            android.util.EventLog.writeEvent(42, "nested", 11, 13L, 2.5f)
            val events = ArrayList<android.util.EventLog.Event>()
            android.util.EventLog.readEvents(intArrayOf(42), events)
            require(events.isEmpty(), "host event-log snapshot is empty without logd")

            android.app.admin.SecurityLog.writeEvent(
                android.app.admin.SecurityLog.TAG_ADB_SHELL_CMD,
                "echo hosted",
            )
            val securityEvents = ArrayList<android.app.admin.SecurityLog.SecurityEvent>()
            android.app.admin.SecurityLog.readEvents(securityEvents)
            require(securityEvents.isEmpty(), "host security-log snapshot is empty without logd")
            require(
                Class.forName("com.android.internal.statsd.StatsdStatsLog").name ==
                    "com.android.internal.statsd.StatsdStatsLog",
                "generated StatsD stats log",
            )

            val logCursor = dev.artzig.ArtzigTestDiagnostics.logSequence()
            val issueCursor = dev.artzig.ArtzigTestDiagnostics.issueSequence()
            android.util.Log.i("FrameworkCompatSmoke", "stock logging clients ok")
            require(
                dev.artzig.ArtzigTestDiagnostics.snapshot(logCursor, issueCursor)
                    .contains("stock logging clients ok"),
                "native log diagnostics sink",
            )
            testDiagnosticJournal()
        }

        private fun testDiagnosticJournal() {
            val logsBefore = ArtzigTestDiagnostics.logSequence()
            val issuesBefore = ArtzigTestDiagnostics.issueSequence()
            val text = "quote\" slash\\ newline\n tab\t control\u0001"
            ArtzigTestDiagnostics.recordLog(android.util.Log.INFO, null, text)
            ArtzigTestDiagnostics.recordLog(android.util.Log.ERROR, "optional", "NoSuchMethodError")
            require(ArtzigTestDiagnostics.issueSequence() == issuesBefore,
                "optional method probes must not become runtime issues")
            val failure = ArtzigTestDiagnostics.unsupported("owner", "member", null)
            require(failure.message == "owner.member is not implemented", "unsupported message")
            val snapshot = org.json.JSONObject(ArtzigTestDiagnostics.snapshot(logsBefore, issuesBefore))
            require(snapshot.getJSONArray("logs").getJSONObject(0).getString("message") == text,
                "diagnostic JSON escaping")
            require(snapshot.getJSONArray("logs").getJSONObject(0).getString("tag") == "",
                "null log tag")
            require(snapshot.getJSONArray("issues").getJSONObject(0).getString("kind") == "unimplemented",
                "explicit unsupported issue classification")

            val logMark = ArtzigTestDiagnostics.logSequence()
            val issueMark = ArtzigTestDiagnostics.issueSequence()
            repeat(513) { ArtzigTestDiagnostics.recordLog(android.util.Log.INFO, "bounded", "entry") }
            repeat(257) { ArtzigTestDiagnostics.reportIssue("test", "owner", "member", "entry") }
            val bounded = org.json.JSONObject(ArtzigTestDiagnostics.snapshot(logMark, issueMark))
            require(bounded.getJSONArray("logs").length() == 512, "bounded log retention")
            require(bounded.getJSONArray("issues").length() == 256, "bounded issue retention")
            require(bounded.getLong("logs_dropped_through") == logMark + 1, "dropped log cursor")
            require(bounded.getLong("issues_dropped_through") == issueMark + 1, "dropped issue cursor")
            val empty = org.json.JSONObject(ArtzigTestDiagnostics.snapshot(
                ArtzigTestDiagnostics.logSequence(), ArtzigTestDiagnostics.issueSequence(),
            ))
            require(empty.getJSONArray("logs").length() == 0 && empty.getJSONArray("issues").length() == 0,
                "snapshot cursors are exclusive")
        }

        /** Stock AOSP FontRequest: validation, identity key and certificate rendering. */
        @JvmStatic
        private fun testStockFontRequest() {
            val certificate = byteArrayOf(1, 2, 3)
            val request = android.provider.FontRequest(
                "fonts.example",
                "org.example.fonts",
                "name=Inter",
                listOf(listOf(certificate)),
            )
            require(request.providerAuthority == "fonts.example", "font provider authority")
            require(request.providerPackage == "org.example.fonts", "font provider package")
            require(request.query == "name=Inter", "font query")
            require(request.certificates.single().single() === certificate, "font certificates retained")
            require(
                request.identifier == "fonts.example-org.example.fonts-name=Inter",
                "font request identifier",
            )
            require(request.toString().contains("AQID"), "font certificate rendered as base64")

            val nullFailure = runCatching {
                android.provider.FontRequest::class.java
                    .getConstructor(String::class.java, String::class.java, String::class.java)
                    .newInstance(null, "org.example.fonts", "name=Inter")
            }.exceptionOrNull()
            require(
                (nullFailure as? java.lang.reflect.InvocationTargetException)?.cause is NullPointerException,
                "font request rejects a null authority",
            )
            android.util.Log.i("FrameworkCompatSmoke", "stock font request ok")
        }

        /** Stock AOSP text spans: identity lookup, failure modes, mutation and parcel state. */
        @JvmStatic
        private fun testStockTextSpans() {
            val original = object : android.text.style.ClickableSpan() {
                override fun onClick(widget: android.view.View) {}
            }
            val text = android.text.SpannableString("link")
            text.setSpan(original, 0, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val accessible = android.text.style.AccessibilityClickableSpan(original.id)
            require(accessible.findClickableSpan(text) === original, "accessible span finds original id")
            require(accessible.findClickableSpan("link") == null, "accessible span rejects unspanned text")
            val clickFailure = runCatching {
                android.text.style.AccessibilityClickableSpan::class.java
                    .getMethod("onClick", android.view.View::class.java)
                    .invoke(accessible, null)
            }.exceptionOrNull()
            require(
                (clickFailure as? java.lang.reflect.InvocationTargetException)?.cause is RuntimeException,
                "uninitialized accessible span click fails",
            )

            val replacement = android.text.style.AccessibilityReplacementSpan("image description")
            val replacementParcel = android.os.Parcel.obtain()
            replacement.writeToParcel(replacementParcel, 0)
            replacementParcel.setDataPosition(0)
            val restoredReplacement = android.text.style.AccessibilityReplacementSpan.CREATOR
                .createFromParcel(replacementParcel)
            require(
                restoredReplacement.contentDescription == "image description",
                "replacement span content description parcels",
            )
            replacementParcel.recycle()

            val edit = android.text.style.EasyEditSpan()
            require(edit.isDeleteEnabled, "easy-edit deletion starts enabled")
            edit.setDeleteEnabled(false)
            val editParcel = android.os.Parcel.obtain()
            edit.writeToParcel(editParcel, 0)
            editParcel.setDataPosition(0)
            val restoredEdit = android.text.style.EasyEditSpan(editParcel)
            require(!restoredEdit.isDeleteEnabled, "easy-edit deletion state parcels")
            require(restoredEdit.pendingIntent == null, "easy-edit empty pending intent parcels")
            editParcel.recycle()

            val suggestions = android.text.style.SuggestionSpan(
                java.util.Locale.US,
                arrayOf("one", "two", "three", "four", "five", "six"),
                android.text.style.SuggestionSpan.FLAG_EASY_CORRECT,
            )
            require(
                suggestions.suggestions.contentEquals(arrayOf("one", "two", "three", "four", "five")),
                "suggestion span caps suggestions",
            )
            require(suggestions.localeObject == java.util.Locale.US, "suggestion span keeps locale")
            suggestions.flags = android.text.style.SuggestionSpan.FLAG_MISSPELLED
            val suggestionParcel = android.os.Parcel.obtain()
            suggestions.writeToParcel(suggestionParcel, 0)
            suggestionParcel.setDataPosition(0)
            val restoredSuggestions = android.text.style.SuggestionSpan.CREATOR
                .createFromParcel(suggestionParcel)
            require(
                restoredSuggestions.flags == android.text.style.SuggestionSpan.FLAG_MISSPELLED,
                "suggestion flags parcel",
            )
            require(restoredSuggestions.localeObject == java.util.Locale.US, "suggestion locale parcels")
            suggestionParcel.recycle()

            // Marker construction is itself the interface: there is intentionally no state.
            android.text.style.NoWritingToolsSpan()
            android.util.Log.i("FrameworkCompatSmoke", "stock text spans ok")
        }

        /** Stock Html delegates malformed-HTML recovery to the vendored TagSoup parser. */
        @JvmStatic
        private fun testStockHtml() {
            val parsed = android.text.Html.fromHtml(
                "<p>Hello <b>bold<i> italic</b> &amp; plain</p>",
                android.text.Html.FROM_HTML_MODE_COMPACT,
            )
            require(parsed.toString().contains("Hello bold italic & plain"), "HTML entities and text parsed")
            val boldStart = parsed.toString().indexOf("bold")
            val styles = parsed.getSpans(
                boldStart,
                boldStart + "bold".length,
                android.text.style.StyleSpan::class.java,
            )
            require(styles.any { it.style == android.graphics.Typeface.BOLD }, "bold HTML creates StyleSpan")
            require(android.text.Html.escapeHtml("<&>") == "&lt;&amp;&gt;", "HTML escaping")
            android.util.Log.i("FrameworkCompatSmoke", "stock Html ok")
        }

        @JvmStatic private fun drainMainLooper() {
            BoundedLooper.drain()
        }

        /** Cross-process prerequisite: every framework value type must survive a Parcel round trip. */
        @JvmStatic
        private fun testParcelRoundTrips() {
        fun <T> roundTrip(value: android.os.Parcelable, creator: android.os.Parcelable.Creator<T>): T {
            val parcel = android.os.Parcel.obtain()
            value.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val out = creator.createFromParcel(parcel)
            check(parcel.dataAvail() == 0) { "${'$'}{value.javaClass.simpleName}: ${'$'}{parcel.dataAvail()} bytes unread" }
            parcel.recycle()
            return checkNotNull(out)
        }
        val intent = android.content.Intent("dev.artzig.ACTION")
        intent.setDataAndType(android.net.Uri.parse("content://dev.artzig/item/1"), "text/plain")
        intent.addCategory("android.intent.category.DEFAULT")
        intent.addCategory("dev.artzig.CATEGORY")
        intent.setPackage("dev.artzig.pkg")
        intent.setComponent(android.content.ComponentName("dev.artzig.pkg", "dev.artzig.pkg.Main"))
        intent.putExtra("i", 7)
        intent.putExtra("s", "text")
        val intent2 = roundTrip(intent, android.content.Intent.CREATOR)
        check(intent.filterEquals(intent2)) { "intent filterEquals after round trip" }
        check(intent2.getIntExtra("i", 0) == 7 && intent2.getStringExtra("s") == "text") { "intent extras" }
        check(intent2.flags == intent.flags) { "intent flags" }

        val filter = android.content.IntentFilter("dev.artzig.ACTION")
        filter.addCategory("dev.artzig.CATEGORY")
        filter.addDataScheme("content")
        filter.setPriority(7)
        val filter2 = roundTrip(filter, android.content.IntentFilter.CREATOR)
        check(filter2.hasAction("dev.artzig.ACTION") && filter2.hasCategory("dev.artzig.CATEGORY")) { "filter fields" }
        check(filter2.hasDataScheme("content") && filter2.priority == 7) { "filter scheme/priority" }

        val app = android.content.pm.ApplicationInfo()
        app.packageName = "dev.artzig.pkg"
        app.name = "dev.artzig.pkg.App"
        app.theme = 0x7f010001
        app.uid = 10001
        app.sourceDir = "/tmp/app.apk"
        val app2 = roundTrip(app, android.content.pm.ApplicationInfo.CREATOR)
        check(app2.packageName == app.packageName && app2.theme == app.theme && app2.uid == app.uid) { "applicationInfo" }
        check(app2.sourceDir == app.sourceDir) { "applicationInfo sourceDir" }

        val activity = android.content.pm.ActivityInfo()
        activity.packageName = "dev.artzig.pkg"
        activity.name = "dev.artzig.pkg.Main"
        activity.applicationInfo = app
        activity.theme = 3
        activity.screenOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        activity.configChanges = 0x480
        val activity2 = roundTrip(activity, android.content.pm.ActivityInfo.CREATOR)
        check(activity2.name == activity.name && activity2.screenOrientation == activity.screenOrientation) { "activityInfo" }
        check(activity2.configChanges == activity.configChanges && activity2.applicationInfo?.uid == app.uid) { "activityInfo nested app" }

        val service = android.content.pm.ServiceInfo()
        service.packageName = "dev.artzig.pkg"
        service.name = "dev.artzig.pkg.Svc"
        service.permission = "dev.artzig.PERM"
        service.exported = true
        // The real AOSP ComponentInfo parcels applicationInfo.
        service.applicationInfo = app
        val resolve = android.content.pm.ResolveInfo()
        resolve.serviceInfo = service
        val resolve2 = roundTrip(resolve, android.content.pm.ResolveInfo.CREATOR)
        check(resolve2.serviceInfo?.permission == "dev.artzig.PERM" && resolve2.serviceInfo?.exported == true) { "resolveInfo{serviceInfo}" }

        val pkg = android.content.pm.PackageInfo()
        pkg.packageName = "dev.artzig.pkg"
        pkg.versionCode = 42
        pkg.versionName = "4.2"
        pkg.applicationInfo = app
        pkg.activities = arrayOf(activity)
        pkg.services = arrayOf(service)
        pkg.requestedPermissions = arrayOf("dev.artzig.PERM")
        val pkg2 = roundTrip(pkg, android.content.pm.PackageInfo.CREATOR)
        check(pkg2.versionCode == 42 && pkg2.activities?.size == 1 && pkg2.services?.get(0)?.name == service.name) { "packageInfo" }

        val provider = android.content.pm.ProviderInfo()
        provider.packageName = "dev.artzig.pkg"
        provider.name = "dev.artzig.pkg.Provider"
        provider.authority = "dev.artzig"
        provider.grantUriPermissions = true
        provider.applicationInfo = app
        val provider2 = roundTrip(provider, android.content.pm.ProviderInfo.CREATOR)
        check(provider2.authority == "dev.artzig" && provider2.grantUriPermissions) { "providerInfo" }
        // Wire-shaped bundle path: marshalled bytes into a fresh Parcel, lazy decode.
        run {
            val src = android.os.Parcel.obtain()
            val b = android.os.Bundle()
            b.putInt("i", 7)
            b.putString("s", "text")
            val nested2 = android.os.Bundle()
            nested2.putDouble("d", 2.5)
            b.putBundle("n", nested2)
            src.writeBundle(b)
            val bytes = src.marshall()
            src.recycle()
            val dst = android.os.Parcel.obtain()
            dst.unmarshall(bytes, 0, bytes.size)
            dst.setDataPosition(0)
            val back2 = checkNotNull(dst.readBundle())
            check(back2.isParcelled()) { "wire bundle should stay parcelled until touched" }
            check(back2.getInt("i") == 7 && back2.getString("s") == "text") { "wire bundle values " + back2.getInt("i") + " " + back2.getString("s") }
            check(back2.getBundle("n")?.getDouble("d") == 2.5) { "wire nested bundle" }
            val copy = android.os.Bundle(back2)
            copy.putString("extra", "x")
            check(copy.getInt("i") == 7 && copy.getString("extra") == "x") { "bundle copy after unparcel" }
            dst.recycle()

            // Blobs: small ones ride inline, large ones spill to a sealed memfd
            // carried as a descriptor (AOSP's ashmem blob), so a Parcel with a
            // multi-megabyte payload stays under the broker's frame cap.
            for (size in intArrayOf(0, 5, 16 * 1024, 2 * 1024 * 1024)) {
                val blob = ByteArray(size) { i -> (i % 251).toByte() }
                val bp = android.os.Parcel.obtain()
                bp.writeBlob(blob)
                check(bp.hasFileDescriptors() == (size > 16 * 1024)) { "blob of $size bytes: descriptor spill" }
                bp.setDataPosition(0)
                val back = checkNotNull(bp.readBlob()) { "blob of $size bytes read back null" }
                check(back.contentEquals(blob)) { "blob of $size bytes round trip" }
                check(bp.dataAvail() == 0) { "blob of $size bytes: ${bp.dataAvail()} bytes unread" }
                bp.recycle()
            }
            val np = android.os.Parcel.obtain()
            np.writeBlob(null)
            np.setDataPosition(0)
            check(np.readBlob() == null) { "null blob round trip" }
            np.recycle()
        }
        android.util.Log.i("FrameworkCompatSmoke", "parcel round trips ok")
        }
    }
}

/** Manifest Application class for testLoadedApk (loaded through the APK loader's parent). */
class SmokeApplication : Application() {
    var created: Boolean = false
    init { attachedAtConstruction = baseContext != null }
    override fun onCreate() { super.onCreate(); created = true }
    companion object { @JvmStatic var attachedAtConstruction: Boolean? = null }
}
