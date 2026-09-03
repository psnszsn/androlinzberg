@file:Suppress("DEPRECATION")

package dev.artzig

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityThread
import android.app.DownloadManager
import android.app.Notification
import android.app.Person
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.ApkAssets
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.HostedConfigurationController
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.android.internal.pm.pkg.parsing.ParsingUtils
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.net.Uri
import android.net.nsd.HostedNsdBackends
import android.net.nsd.InMemoryHostedNsdBackend
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.opengl.GLSurfaceView
import android.util.Rational
import android.util.SparseArray
import android.view.Choreographer
import android.view.ArtzigVideoLayer
import android.view.HostedSystemUiController
import android.view.HostedInputDispatcher
import android.view.HostedWindowPresentation
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowManagerImpl
import android.view.ViewRootImpl
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.SeekBar
import java.io.ByteArrayInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/** Behavioral smoke tests whose surface is the hosted framework modules. */
internal object FrameworkModuleSmoke {
    private val events = mutableListOf<String>()
    private val createdThemeResIds = mutableMapOf<String, Int>()

    private var permissionsFixtureApk: String? = null

    @JvmStatic
    fun main(args: Array<String>) {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        permissionsFixtureApk = args.firstOrNull()
        testPackageNames()
        testIntentTypedExtras()
        testRemoteActionContract()
        testPictureInPictureContracts()
        testConfigurationCalculation()
        testConfigurationContexts()
        testMessageQueueSyncBarrier()
        testMessageQueueHostPumpSemantics()
        testChoreographerPhaseOrder()
        testPostedCallbackRunsAfterPendingTraversal()
        testTraversalRescheduleAndSharedQueue()
        testHostedSystemServices()
        testHostedAudioCapabilities()
        testAsyncMediaCodecFlushRestartsOnePump()
        testMediaMetricsContracts()
        testWebViewCallbackContracts()
        testSurfaceViewLifecycle()
        testNetworkServiceDiscovery()
        testHostedInputEvents()
        testEditableTextSavedStateRoundTrips()
        testHostedInputDispatch()
        testHostedPhysicalInputLifecycle()
        testHostedInputHierarchyDispatch()
        testHostedMouseSeekBarDrag()
        testHostedInputCancelOnInterception()
        testHostedInputSiblingDispatch()
        testHostedInputWindowRouting()
        testWindowPresentation()
        testActivityLifecycle()
        testHostedPermissionGrants()
        println("hosted framework module smoke: PASS")
    }

    private fun testPackageNames() {
        // The AOSP parser's component-name normalization (ParsingUtils).
        check(ParsingUtils.buildClassName("org.example", ".MainActivity") ==
            "org.example.MainActivity")
        check(ParsingUtils.buildClassName("org.example", "MainActivity") ==
            "org.example.MainActivity")
        check(ParsingUtils.buildClassName("org.example", "other.MainActivity") ==
            "other.MainActivity")
    }

    private fun testHostedPermissionGrants() {
        val frameworkResources =
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk"
        // Server-side install of the fixture APK: WAKE_LOCK (normal) is
        // granted at install, CAMERA (dangerous) is not.
        val fixture = checkNotNull(permissionsFixtureApk) { "permission fixture APK argument missing" }
        val installer = checkNotNull(android.app.HostedServiceClient.installerOrNull())
        check(installer.installPackage(fixture, null) == "org.example.permissions")

        val wakeLock = "android.permission.WAKE_LOCK"
        val camera = "android.permission.CAMERA"
        // AOSP checks the calling uid's grants: this process is bound to the
        // fixture package, so its package context answers for it.
        ActivityThread.attachApplicationHosted("org.example.permissions")
        val base = HostContexts.systemContext(frameworkResources, 200, 200, 120, 0)
            .createPackageContext("org.example.permissions", 0)
        val wrapped = ContextWrapper(base)
        check(base.checkSelfPermission(wakeLock) == PackageManager.PERMISSION_GRANTED)
        check(wrapped.checkSelfPermission(wakeLock) == PackageManager.PERMISSION_GRANTED)
        check(base.checkSelfPermission(camera) == PackageManager.PERMISSION_DENIED)
        check(base.checkSelfPermission("android.permission.NOT_DECLARED") ==
            PackageManager.PERMISSION_DENIED)
        // AOSP ContextImpl.checkSelfPermission(null) throws (the Kotlin overload is @NonNull).
        val nullPermission = runCatching {
            android.content.Context::class.java.getMethod("checkSelfPermission", String::class.java)
                .invoke(base, *arrayOf<Any?>(null))
        }.exceptionOrNull()
        check((nullPermission as? java.lang.reflect.InvocationTargetException)?.cause is IllegalArgumentException)

        val packageInfo = base.packageManager.getPackageInfo(
            "org.example.permissions",
            PackageManager.GET_PERMISSIONS,
        )
        val grants = packageInfo.requestedPermissions!!
            .zip(packageInfo.requestedPermissionsFlags!!.toTypedArray())
            .toMap()
        check(grants.getValue(wakeLock) and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0)
        check(grants.getValue(camera) and PackageInfo.REQUESTED_PERMISSION_GRANTED == 0)
    }

    private fun testIntentTypedExtras() {
        val strings = arrayOf("camera", "microphone")
        val numbers = intArrayOf(3, 5, 8)
        val labels = arrayListOf<CharSequence>("first", "second")
        val intent = Intent()
            .putExtra("strings", strings)
            .putExtra("numbers", numbers)
            .putExtra("letter", 'g')
            .putCharSequenceArrayListExtra("labels", labels)

        check(intent.getStringArrayExtra("strings") === strings)
        check(intent.getIntArrayExtra("numbers") === numbers)
        check(intent.getCharExtra("letter", '?') == 'g')
        check(intent.getCharSequenceArrayListExtra("labels") === labels)
        check(intent.getStringArrayExtra("missing") == null)
        check(intent.getIntExtra("strings", 41) == 41)
        check(Uri.encode("a b/c", "/") == "a%20b/c")
        check(Uri.encode("café") == "caf%C3%A9")
        check(Uri.parse("./YoutubeScript.js").isRelative)
        check(!Uri.parse("./YoutubeScript.js").isAbsolute)
        check(Uri.parse("https://plugins.grayjay.app/Youtube/YoutubeScript.js").isAbsolute)
        check(!Uri.parse("https://plugins.grayjay.app/Youtube/YoutubeScript.js").isRelative)

        val shareTarget = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://example.com/watch?v=42")
        val chooser = Intent.createChooser(shareTarget, "Share video")
        check(chooser !== shareTarget)
        check(chooser.action == Intent.ACTION_CHOOSER)
        check(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) === shareTarget)
        check(chooser.getCharSequenceExtra(Intent.EXTRA_TITLE) == "Share video")
        val payload = checkNotNull(HostedDesktopIntents.sharePayload(chooser))
        check(payload.title == "Share video")
        check(payload.text == "https://example.com/watch?v=42")
        check(payload.url == "https://example.com/watch?v=42")
        check(payload.mimeType == "text/plain")
        check(HostedDesktopIntents.sharePayload(Intent(Intent.ACTION_VIEW)) == null)
    }

    private fun testRemoteActionContract() {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            200,
            200,
            120,
            0,
        )
        val icon = Icon.createWithData(byteArrayOf(1, 2, 3), 0, 3)
        val intent = Intent("dev.artzig.REMOTE_ACTION")
        val pendingIntent = PendingIntent.getActivity(
            context,
            42,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        val action = RemoteAction(icon, "Play", "Play video", pendingIntent)
        check(action.icon === icon)
        check(action.title == "Play")
        check(action.contentDescription == "Play video")
        check(action.actionIntent === pendingIntent)
        check(action.isEnabled)
        check(action.shouldShowIcon())

        action.isEnabled = false
        action.setShouldShowIcon(false)
        val clone = action.clone()
        check(clone !== action)
        check(!clone.isEnabled)
        check(!clone.shouldShowIcon())
        check(clone == action)

        val nullArgumentFailure = runCatching {
            RemoteAction::class.java.getConstructor(
                Icon::class.java,
                CharSequence::class.java,
                CharSequence::class.java,
                PendingIntent::class.java,
            ).newInstance(icon, "Play", "Play video", null)
        }.exceptionOrNull()
        check(nullArgumentFailure?.cause is IllegalArgumentException)
    }

    private fun testPictureInPictureContracts() {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            200,
            200,
            120,
            0,
        )
        val action = RemoteAction(
            Icon.createWithData(byteArrayOf(1), 0, 1),
            "Pause",
            "Pause video",
            PendingIntent.getActivity(context, 7, Intent("pause"), PendingIntent.FLAG_IMMUTABLE),
        )
        val source = Rect(10, 20, 170, 110)
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setSourceRectHint(source)
            .setActions(listOf(action))
            .setAutoEnterEnabled(true)
            .build()
        source.setEmpty()

        check(params.aspectRatio == Rational(16, 9))
        check(params.sourceRectHint == Rect(10, 20, 170, 110))
        check(params.actions == listOf(action))
        check(params.isAutoEnterEnabled)
        // android 16: unset seamless-resize now reads false (AOSP
        // PictureInPictureParams.isSeamlessResizeEnabled).
        check(!params.isSeamlessResizeEnabled)

        val activity = Activity().apply { attach(context) }
        activity.setPictureInPictureParams(params)
        check(!activity.enterPictureInPictureMode(params))
        check(!activity.isInPictureInPictureMode)
        check(activity.maxNumPictureInPictureActions >= 3)
    }

    private fun testConfigurationContexts() {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            200,
            200,
            120,
            0,
        )
        val originalUiMode = context.resources.configuration.uiMode
        val override = Configuration().apply {
            uiMode = Configuration.UI_MODE_NIGHT_YES
        }
        val configured = context.createConfigurationContext(override)
        check(configured !== context)
        check(configured.isConfigurationContext)
        check(configured.resources !== context.resources)
        check(configured.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES)
        check(context.resources.configuration.uiMode == originalUiMode)

        val themed = android.view.ContextThemeWrapper(context, 0)
        themed.applyOverrideConfiguration(override)
        check(themed.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES)
    }

    private fun testConfigurationCalculation() {
        val base = Configuration().apply { setToDefaults() }
        val phone = HostedConfigurationController.calculate(
            base,
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            420,
            800,
            120,
        )
        check(!phone.landscape)
        check(phone.configuration.screenWidthDp == 420)
        check(phone.configuration.screenHeightDp == 800)

        val wide = HostedConfigurationController.calculate(
            phone.configuration,
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            1000,
            700,
            120,
        )
        check(wide.landscape)
        check(wide.changes and ActivityInfo.CONFIG_ORIENTATION != 0)
        check(wide.changes and ActivityInfo.CONFIG_SCREEN_SIZE != 0)
        check(wide.changes and ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE != 0)

        val lockedPortrait = HostedConfigurationController.calculate(
            wide.configuration,
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            1000,
            700,
            120,
        )
        check(!lockedPortrait.landscape)
        check(lockedPortrait.displayWidth == 700 && lockedPortrait.displayHeight == 1000)
    }

    private fun testHostedSystemServices() {
        run {
            val context = HostContexts.systemContext(System.getenv("ARTZIG_FRAMEWORK_RES"), 640, 480, 120, 0)
            android.os.ServiceManager.addProcessLocalService(
                Context.DISPLAY_SERVICE,
                dev.artzig.system.HostedDisplayManagerService(),
            )
            android.os.ServiceManager.addProcessLocalService(
                Context.INPUT_SERVICE,
                dev.artzig.system.HostedInputManagerService(),
            )
            val dm = checkNotNull(context.getSystemService(android.hardware.display.DisplayManager::class.java))
            check(dm === context.getSystemService(Context.DISPLAY_SERVICE)) { "display manager not cached" }
            check(dm.displays.size == 1) { "expected one display" }
            val display = checkNotNull(dm.getDisplay(android.view.Display.DEFAULT_DISPLAY))
            android.view.ArtzigDisplay.configure(800, 600, 120)
            val size = android.graphics.Point()
            checkNotNull(dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)).getRealSize(size)
            check(size.x == 800 && size.y == 600) { "display size $size after configure" }
            check(display.displayId == android.view.Display.DEFAULT_DISPLAY)
            val im = checkNotNull(context.getSystemService(android.hardware.input.InputManager::class.java))
            check(im.inputDeviceIds.contentEquals(intArrayOf(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD))) {
                "unexpected input devices"
            }
            checkNotNull(android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD))
            check(context.getSystemService("no.such.service") == null) { "unknown service must be null" }
        }
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            200,
            200,
            120,
            0,
        )
        val activityManager = context.getSystemService(ActivityManager::class.java)
        check(activityManager != null && !activityManager.isBackgroundRestricted)

        val clipboardManager =
            checkNotNull(context.getSystemService(android.content.ClipboardManager::class.java))
        check(clipboardManager === context.getSystemService(Context.CLIPBOARD_SERVICE))
        clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("smoke", "hosted-clip"))
        check(clipboardManager.hasPrimaryClip())
        check(clipboardManager.text == "hosted-clip")
        checkNotNull(clipboardManager.primaryClipDescription)


        val notificationBuilder = Notification.Builder(context, "smoke")
        check(notificationBuilder.addPerson("name:framework-smoke") === notificationBuilder)
        check(notificationBuilder.addPerson(
            Person.Builder().setName("Framework smoke").build(),
        ) === notificationBuilder)
        val people = notificationBuilder.build().extras
            .getParcelableArrayList<Person>(Notification.EXTRA_PEOPLE_LIST)
        check(people?.map { it.uri ?: it.name?.toString() } == listOf(
            "name:framework-smoke", "Framework smoke",
        )) { "Notification.Builder must publish people into notification extras: $people" }

        // AOSP: the download table lives in the application's data directory; a
        // system context has none, so the block runs against a package context
        // of the installed fixture (this process is bound to that package).
        val fixture = checkNotNull(permissionsFixtureApk) { "permission fixture APK argument missing" }
        val fixtureInstaller = checkNotNull(android.app.HostedServiceClient.installerOrNull())
        check(fixtureInstaller.installPackage(fixture, null) == "org.example.permissions")
        ActivityThread.attachApplicationHosted("org.example.permissions")
        val appContext = context.createPackageContext("org.example.permissions", 0)
        // Night mode is delivered to this process only once it is attached to the activity
        // manager (above), as AOSP delivers package configuration to attached processes.
        val uiModeManager = checkNotNull(context.getSystemService(UiModeManager::class.java))
        check(uiModeManager === context.getSystemService(Context.UI_MODE_SERVICE))
        // The hosted IUiModeManager delivers the package's night mode as a configuration
        // transaction on the main looper (AOSP: ActivityTaskManager's package configuration
        // update), so the queue is drained before the configuration is inspected.
        // The transaction arrives on a binder thread (IApplicationThread is oneway) and is
        // posted to the main looper, so drain the queue until the configuration reflects it.
        fun nightBitsBecome(expected: Int): Boolean {
            repeat(250) {
                drainMainQueue()
                if (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == expected) return true
                Thread.sleep(20)
            }
            return false
        }
        try {
            uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)
            check(nightBitsBecome(Configuration.UI_MODE_NIGHT_NO))
            uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
            check(nightBitsBecome(Configuration.UI_MODE_NIGHT_YES))
            check(runCatching {
                uiModeManager.setApplicationNightMode(Int.MIN_VALUE)
            }.exceptionOrNull() is IllegalArgumentException)
        } finally {
            uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
            drainMainQueue()
        }
        // The hosted provider table is deliberately kept under filesDir, where the APK can alter
        // it. Verify that restoring a forged successful record cannot turn provider APIs into a
        // read/delete primitive for paths outside application-owned storage.
        val stateDirectory = File(appContext.filesDir, "hosted-download-manager")
        check(stateDirectory.isDirectory || stateDirectory.mkdirs())
        listOf("state.bin", "state.bin.bak", "state.bin.tmp").forEach {
            File(stateDirectory, it).delete()
        }
        val restoredEscape = File("/tmp/hosted-download-manager-restored-escape.bin")
        restoredEscape.writeText("must survive rejected restored state")
        restoredEscape.deleteOnExit()
        fun DataOutputStream.writeStateString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeInt(bytes.size)
            write(bytes)
        }
        DataOutputStream(FileOutputStream(File(stateDirectory, "state.bin"))).use { output ->
            output.writeInt(1)
            output.writeLong(2)
            output.writeInt(1)
            output.writeLong(1)
            output.writeStateString("https://example.invalid/forged")
            output.writeStateString(restoredEscape.absolutePath)
            repeat(3) { output.writeInt(-1) }
            output.writeInt(0)
            output.writeInt(DownloadManager.STATUS_SUCCESSFUL)
            output.writeInt(0)
            output.writeLong(restoredEscape.length())
            output.writeLong(restoredEscape.length())
            output.writeLong(System.currentTimeMillis())
            output.writeBoolean(true)
        }

        val byName = appContext.getSystemService(Context.DOWNLOAD_SERVICE)
        val downloadManager = appContext.getSystemService(DownloadManager::class.java)
        check(downloadManager != null && downloadManager === byName)
        downloadManager.query(DownloadManager.Query().setFilterById(1)).use { cursor ->
            check(!cursor.moveToFirst()) { "unsafe persisted DownloadManager record was restored" }
        }
        check(restoredEscape.readText() == "must survive rejected restored state")
        restoredEscape.delete()
        check(runCatching {
            DownloadManager.Request(Uri.parse("file:///tmp/not-a-network-download"))
        }.exceptionOrNull() is IllegalArgumentException)

        val payload = "hosted DownloadManager smoke".toByteArray()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = Thread({
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) {
                    // Consume request headers.
                }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\n\r\n".toByteArray())
                    write(payload)
                    flush()
                }
            }
        }, "DownloadManager-smoke-server").apply {
            isDaemon = true
            start()
        }
        val destination = File(appContext.filesDir, "hosted-download-manager-smoke.bin")
        destination.delete()
        val unsafeRequest = DownloadManager.Request(
            Uri.parse("http://127.0.0.1/unsafe"),
        ).setDestinationUri(Uri.fromFile(File("/tmp/hosted-download-manager-escape.bin")))
        check(runCatching { downloadManager.enqueue(unsafeRequest) }.exceptionOrNull() is SecurityException) {
            "hosted DownloadManager must not escape application-owned storage"
        }
        val traversalRequest = DownloadManager.Request(
            Uri.parse("http://127.0.0.1/traversal"),
        ).setDestinationInExternalFilesDir(appContext, null, "../../hosted-download-manager-escape.bin")
        check(runCatching { downloadManager.enqueue(traversalRequest) }.exceptionOrNull() is SecurityException) {
            "hosted DownloadManager must reject external-files traversal"
        }
        val request = DownloadManager.Request(
            Uri.parse("http://127.0.0.1:${server.localPort}/update.apk"),
        ).setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            .setTitle("Framework smoke")
            .setDescription("DownloadManager contract")
            .setDestinationUri(Uri.fromFile(destination))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
        val id = downloadManager.enqueue(request)
        var collisionId = -1L
        try {
            fun awaitTerminal(downloadId: Long): Pair<Int, Int> {
                val deadline = System.nanoTime() + 5_000_000_000L
                var status = DownloadManager.STATUS_PENDING
                var reason = 0
                while (System.nanoTime() < deadline) {
                    downloadManager.query(
                        DownloadManager.Query().setFilterById(downloadId),
                    ).use { cursor ->
                        if (cursor.moveToFirst()) {
                            status = cursor.getInt(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS),
                            )
                            reason = cursor.getInt(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON),
                            )
                        }
                    }
                    if (status == DownloadManager.STATUS_SUCCESSFUL ||
                        status == DownloadManager.STATUS_FAILED
                    ) break
                    Thread.sleep(10)
                }
                return status to reason
            }

            val (status, _) = awaitTerminal(id)
            check(status == DownloadManager.STATUS_SUCCESSFUL) {
                "hosted download finished with status $status"
            }
            val downloaded = ParcelFileDescriptor.AutoCloseInputStream(
                downloadManager.openDownloadedFile(id),
            ).use { it.readBytes() }
            check(downloaded.contentEquals(payload))

            val restoredManager = DownloadManager(appContext)
            restoredManager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                    DownloadManager.STATUS_SUCCESSFUL)
            }
            check(File(appContext.filesDir, "hosted-download-manager/state.bin").isFile) {
                "DownloadManager request table was not persisted"
            }

            check(downloadManager.remove(id) == 1)
            check(!destination.exists())
            val existing = "pre-existing destination".toByteArray()
            destination.writeBytes(existing)
            collisionId = downloadManager.enqueue(request)
            val (collisionStatus, collisionReason) = awaitTerminal(collisionId)
            check(collisionStatus == DownloadManager.STATUS_FAILED)
            check(collisionReason == DownloadManager.ERROR_FILE_ALREADY_EXISTS)
            check(destination.readBytes().contentEquals(existing)) {
                "DownloadManager overwrote an existing destination"
            }
            check(downloadManager.remove(collisionId) == 1)
            collisionId = -1L
            check(destination.readBytes().contentEquals(existing)) {
                "removing a failed download deleted a file it did not create"
            }
        } finally {
            downloadManager.remove(id)
            if (collisionId != -1L) downloadManager.remove(collisionId)
            server.close()
            responder.join(1_000)
            destination.delete()
        }
        check(!destination.exists())
    }

    private fun testHostedAudioCapabilities() {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            200,
            200,
            120,
            0,
        )
        val audioManager = checkNotNull(context.getSystemService(AudioManager::class.java))
        val attributes = AudioAttributes.Builder().build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48_000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        val routedDevices = audioManager.getAudioDevicesForAttributes(attributes)
        check(routedDevices.size == 1)
        check(routedDevices.single().type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        check(audioManager.getDirectProfilesForAttributes(attributes).isEmpty())
        check(!AudioManager.isOffloadedPlaybackSupported(format, attributes))
        check(AudioManager.getPlaybackOffloadSupport(format, attributes) ==
            AudioManager.PLAYBACK_OFFLOAD_NOT_SUPPORTED)
        check(audioManager.getParameters("offloadVariableRateSupported").isEmpty())
        check(AudioRouting::class.java.isAssignableFrom(AudioTrack::class.java))
        AudioTrack::class.java.getMethod(
            "addOnRoutingChangedListener",
            AudioRouting.OnRoutingChangedListener::class.java,
            Handler::class.java,
        )
    }

    private fun testAsyncMediaCodecFlushRestartsOnePump() {
        drainMainQueue()
        val callbackHandler = Handler(checkNotNull(Looper.getMainLooper()))
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_MPEG)
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo,
            ) = Unit
            override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                throw error
            }
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
        }, callbackHandler)
        codec.configure(
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_MPEG, 44_100, 2),
            null,
            null,
            0,
        )
        try {
            // Each pair is the asynchronous lifecycle used by Media3 during a seek. There must
            // be only one runnable producer left for the final execution, not one per flush.
            repeat(20) {
                codec.start()
                codec.flush()
            }
            codec.start()
            val dispatched = drainMainQueueCounting()
            check(dispatched == 1) {
                "asynchronous MediaCodec accumulated $dispatched callback pumps across flushes"
            }
        } finally {
            codec.release()
            drainMainQueue()
        }
    }

    private fun testEditableTextSavedStateRoundTrips() {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            320,
            180,
            120,
            0,
        )
        val viewId = 0x00f0_0101
        var expectedText = "Grayjay query"
        var current = EditText(context).apply {
            id = viewId
            setFreezesText(true)
            text.append(expectedText)
        }
        check(current.canUndo()) { "editable input must create an Editor undo operation" }

        repeat(4) { cycle ->
            val hierarchy = SparseArray<Parcelable>()
            current.saveHierarchyState(hierarchy)
            val saved = checkNotNull(hierarchy[viewId])

            val parcel = Parcel.obtain()
            val restoredState = try {
                parcel.writeParcelable(saved, 0)
                parcel.setDataPosition(0)
                checkNotNull(parcel.readParcelable<Parcelable>(EditText::class.java.classLoader))
            } finally {
                parcel.recycle()
            }

            val restoredHierarchy = SparseArray<Parcelable>().apply {
                put(viewId, restoredState)
            }
            val restored = EditText(context).apply {
                id = viewId
                setFreezesText(true)
                try {
                    restoreHierarchyState(restoredHierarchy)
                } catch (failure: Throwable) {
                    throw IllegalStateException(
                        "editable saved state restore failed on cycle $cycle",
                        failure,
                    )
                }
            }
            check(restored.text.toString() == expectedText) {
                "editable saved state lost text on cycle $cycle: ${restored.text}"
            }
            check(restored.canUndo()) {
                "editable saved state lost its undo operation on cycle $cycle"
            }

            val suffix = ('a'.code + cycle).toChar().toString()
            restored.text.append(suffix)
            expectedText += suffix
            current = restored
        }
    }

    private fun testWebViewCallbackContracts() {
        val cookies = android.webkit.CookieManager.getInstance()
        cookies.removeAllCookies(null)
        var cookieAccepted: Boolean? = null
        cookies.setCookie(
            "https://video.example/path/page",
            "session=abc; Domain=video.example; Path=/path; Secure",
        ) { cookieAccepted = it }
        check(cookieAccepted == true)
        check(cookies.getCookie("https://video.example/path/next") == "session=abc")
        check(cookies.getCookie("http://video.example/path/next") == null)
        cookies.removeSessionCookies(null)
        check(!cookies.hasCookies())

        val response = WebResourceResponse(
            "text/plain",
            "utf-8",
            200,
            "OK",
            mapOf("X-Hosted" to "true"),
            ByteArrayInputStream("body".toByteArray()),
        )
        check(response.getMimeType() == "text/plain")
        check(response.getEncoding() == "utf-8")
        check(response.getStatusCode() == 200 && response.getReasonPhrase() == "OK")
        check(response.getResponseHeaders() == mapOf("X-Hosted" to "true"))
        check(runCatching {
            WebResourceResponse("text/plain", "utf-8", 302, "Found", null, null)
        }.exceptionOrNull() is IllegalArgumentException)

        val request = object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse("https://example.invalid/resource")
            override fun isForMainFrame(): Boolean = true
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }
        val client = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: android.webkit.WebView?,
                url: String?,
            ): Boolean = url == request.getUrl().toString()

            override fun shouldInterceptRequest(
                view: android.webkit.WebView?,
                url: String?,
            ): WebResourceResponse? = response.takeIf { url == request.getUrl().toString() }
        }
        check(client.shouldOverrideUrlLoading(null, request))
        check(client.shouldInterceptRequest(null, request) === response)

        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            200,
            200,
            120,
            0,
        )
        android.webkit.HostedWebViewProviders.installForTests {
            android.webkit.InMemoryHostedWebViewProvider(it)
        }
        try {
            val navigation = mutableListOf<String>()
            val webView = android.webkit.WebView(context, null).apply {
                settings.setJavaScriptEnabled(true)
                settings.setDomStorageEnabled(true)
                settings.setUserAgentString("framework-smoke")
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(
                        view: android.webkit.WebView?,
                        url: String?,
                        favicon: android.graphics.Bitmap?,
                    ) { navigation += "started:$url" }
                    override fun onPageCommitVisible(view: android.webkit.WebView?, url: String?) {
                        navigation += "committed:$url"
                    }
                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                        navigation += "finished:$url"
                    }
                }
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onProgressChanged(view: android.webkit.WebView?, newProgress: Int) {
                        navigation += "progress:$newProgress"
                    }
                }
            }
            check(webView.settings.getJavaScriptEnabled())
            check(webView.settings.getDomStorageEnabled())
            check(webView.settings.getUserAgentString() == "framework-smoke")
            webView.loadUrl("https://example.invalid/page")
            check(navigation == listOf(
                "started:https://example.invalid/page",
                "progress:100",
                "committed:https://example.invalid/page",
                "finished:https://example.invalid/page",
            )) { "hosted WebView callback ordering: $navigation" }
            var evaluation: String? = null
            webView.evaluateJavascript("1 + 1") { evaluation = it }
            check(evaluation == "null")
            webView.destroy()
        } finally {
            android.webkit.HostedWebViewProviders.installForTests(null)
        }
    }

    private fun testMediaMetricsContracts() {
        val metrics = android.media.metrics.PlaybackMetrics.Builder()
            .setPlayerName("framework-smoke")
            .setPlayerVersion("1")
            .setStreamSource(android.media.metrics.PlaybackMetrics.STREAM_SOURCE_NETWORK)
            .setStreamType(android.media.metrics.PlaybackMetrics.STREAM_TYPE_DASH)
            .setVideoFramesPlayed(17)
            .build()
        check(metrics.getPlayerName() == "framework-smoke")
        check(metrics.getStreamSource() ==
            android.media.metrics.PlaybackMetrics.STREAM_SOURCE_NETWORK)
        check(metrics.getStreamType() == android.media.metrics.PlaybackMetrics.STREAM_TYPE_DASH)
        check(metrics.getVideoFramesPlayed() == 17)

        val state = android.media.metrics.PlaybackStateEvent.Builder()
            .setState(android.media.metrics.PlaybackStateEvent.STATE_PLAYING)
            .setTimeSinceCreatedMillis(42)
            .build()
        check(state.getState() == android.media.metrics.PlaybackStateEvent.STATE_PLAYING)
        check(state.getTimeSinceCreatedMillis() == 42L)

        // The real MediaMetricsManager needs the hosted IMediaMetricsManager; the
        // cross-process system test covers session ids, this smoke keeps the value types.
        check(android.media.metrics.LogSessionId.LOG_SESSION_ID_NONE.stringId.isEmpty())
    }

    private fun testNetworkServiceDiscovery() {
        val backend = InMemoryHostedNsdBackend()
        HostedNsdBackends.installForTests(backend)
        try {
            val context = HostContexts.systemContext(
                System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
                200,
                200,
                120,
                0,
            )
            val manager = checkNotNull(context.getSystemService(NsdManager::class.java))
            check(manager === context.getSystemService(Context.NSD_SERVICE))

            val service = NsdServiceInfo().apply {
                setServiceName("Living Room")
                setServiceType("_fcast._tcp")
                setHostAddresses(listOf(InetAddress.getByName("127.0.0.1")))
                setPort(46899)
                setAttribute("id", "framework-smoke")
            }
            backend.publish(service)

            val callbacks = mutableListOf<String>()
            var found: NsdServiceInfo? = null
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    error("discovery failed: $serviceType/$errorCode")
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    error("stop failed: $serviceType/$errorCode")
                }
                override fun onDiscoveryStarted(serviceType: String) {
                    callbacks += "started:$serviceType"
                }
                override fun onDiscoveryStopped(serviceType: String) {
                    callbacks += "stopped:$serviceType"
                }
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    found = serviceInfo
                    callbacks += "found:${serviceInfo.getServiceName()}"
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    callbacks += "lost:${serviceInfo.getServiceName()}"
                }
            }
            manager.discoverServices("_fcast._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
            drainMainQueue()
            check(callbacks == listOf("started:_fcast._tcp", "found:Living Room")) {
                "NSD discovery callback ordering: $callbacks"
            }
            val discovered = checkNotNull(found)
            check(discovered.getHostAddresses().single().hostAddress == "127.0.0.1")
            check(discovered.getPort() == 46899)
            check(String(checkNotNull(discovered.getAttributes()["id"])) == "framework-smoke")

            val serviceInfoCallbacks = mutableListOf<String>()
            val serviceInfoCallback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    error("service info registration failed: $errorCode")
                }
                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    serviceInfoCallbacks += "updated:${serviceInfo.getPort()}"
                }
                override fun onServiceLost() { serviceInfoCallbacks += "lost" }
                override fun onServiceInfoCallbackUnregistered() {
                    serviceInfoCallbacks += "unregistered"
                }
            }
            val directExecutor = java.util.concurrent.Executor(Runnable::run)
            manager.registerServiceInfoCallback(discovered, directExecutor, serviceInfoCallback)
            manager.unregisterServiceInfoCallback(serviceInfoCallback)
            check(serviceInfoCallbacks == listOf("updated:46899", "unregistered"))

            manager.stopServiceDiscovery(listener)
            drainMainQueue()
            check(callbacks.last() == "stopped:_fcast._tcp")
        } finally {
            HostedNsdBackends.installForTests(null)
        }
    }

    private fun testSurfaceViewLifecycle() {
        ArtzigVideoLayer.setNativeCallsEnabledForTests(false)
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            200,
            200,
            120,
            0,
        )
        val glView = GLSurfaceView(context, null)
        glView.setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(
                gl: javax.microedition.khronos.opengles.GL10?,
                config: javax.microedition.khronos.egl.EGLConfig?,
            ) = Unit
            override fun onSurfaceChanged(
                gl: javax.microedition.khronos.opengles.GL10?,
                width: Int,
                height: Int,
            ) = Unit
            override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) = Unit
        })
        glView.setEGLContextClientVersion(2)
        glView.setPreserveEGLContextOnPause(true)
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY)
        check(glView.getRenderMode() == GLSurfaceView.RENDERMODE_WHEN_DIRTY)
        check(glView.getPreserveEGLContextOnPause())
        val callbacks = mutableListOf<String>()
        val surfaceView = SurfaceView(context).apply {
            visibility = View.INVISIBLE
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { callbacks += "created" }
                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                ) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) { callbacks += "destroyed" }
            })
            setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
        }
        try {
            val root = ViewRootImpl(context)
            root.setView(surfaceView, 200, 200)
            try {
                root.doTraversal()
                check(callbacks == listOf("created")) { "attachment lifecycle: $callbacks" }

                surfaceView.visibility = View.VISIBLE
                surfaceView.visibility = View.INVISIBLE
                check(callbacks == listOf("created")) {
                    "surface destroyed while attached: $callbacks"
                }

                surfaceView.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_VISIBILITY)
                check(callbacks == listOf("created", "destroyed")) {
                    "visibility lifecycle: $callbacks"
                }
                surfaceView.visibility = View.VISIBLE
                check(callbacks == listOf("created", "destroyed", "created")) {
                    "visible surface recreation: $callbacks"
                }
            } finally {
                root.destroy()
            }
        } finally {
            ArtzigVideoLayer.setNativeCallsEnabledForTests(true)
        }
        check(callbacks.last() == "destroyed") { "surface not destroyed on detach: $callbacks" }
    }

    private fun testPostedCallbackRunsAfterPendingTraversal() {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            200,
            200,
            120,
            0,
        )
        val child = View(context)
        val parent = DeferredGeometryGroup(context, child)
        val root = ViewRootImpl(context)
        root.setView(parent, 200, 200)
        try {
            root.doTraversal()
            drainMainQueue()
            root.doTraversal()
            check(child.top == 10)

            parent.childTop = 100
            parent.requestLayout()
            var observedTop = -1
            child.post { observedTop = child.top }

            // Mirror the hosted frame lifecycle. AOSP's traversal sync barrier keeps the
            // synchronous View.post callback queued until CALLBACK_TRAVERSAL updates bounds.
            drainMainQueue()
            Choreographer.getInstance().`doFrame$artzig`(System.nanoTime())
            drainMainQueue()
            root.doTraversal()

            check(observedTop == 100) {
                "View.post observed stale bounds $observedTop before pending traversal"
            }
        } finally {
            root.destroy()
            drainMainQueue()
        }
    }

    private fun testMessageQueueSyncBarrier() {
        drainMainQueue()
        val looper = checkNotNull(Looper.getMainLooper())
        val queue = looper.queue
        val observed = mutableListOf<String>()
        val synchronous = Handler(looper)
        val asynchronous = Handler.createAsync(looper)
        val barrier = queue.postSyncBarrier()
        synchronous.post { observed += "sync" }
        asynchronous.post { observed += "async" }

        val asyncMessage = checkNotNull(queue.`pollDueMessage$artzig`()) {
            "barrier blocked asynchronous message"
        }
        try {
            asyncMessage.target.dispatchMessage(asyncMessage)
        } finally {
            android.os.MessageQueue.`recycleDispatched$artzig`(asyncMessage)
        }
        check(observed == listOf("async")) { "unexpected barrier delivery: $observed" }
        check(queue.`pollDueMessage$artzig`() == null) {
            "barrier allowed a synchronous message through"
        }

        queue.removeSyncBarrier(barrier)
        drainMainQueue()
        check(observed == listOf("async", "sync")) { "barrier did not release sync work: $observed" }
        check(runCatching { queue.removeSyncBarrier(barrier) }.exceptionOrNull()
            is IllegalStateException) { "removing a barrier twice must fail" }
    }

    private fun testMessageQueueHostPumpSemantics() {
        drainMainQueue()
        val looper = checkNotNull(Looper.getMainLooper())
        val queue = looper.queue
        val handler = Handler(looper)

        var delayedRan = false
        handler.postDelayed({ delayedRan = true }, 35)
        val delay = queue.`nextPollDelayMillis$artzig`()
        check(delay in 1..35) { "host poll deadline did not track delayed work: $delay" }
        check(queue.`pollDueMessage$artzig`() == null)
        Thread.sleep(45)
        drainMainQueue()
        check(delayedRan) { "delayed message was not runnable at its deadline" }

        var idleRuns = 0
        queue.addIdleHandler {
            idleRuns++
            idleRuns < 2
        }
        queue.`runIdleHandlers$artzig`()
        queue.`runIdleHandlers$artzig`()
        check(idleRuns == 1) { "idle handler ran repeatedly without queue activity" }
        handler.post { }
        drainMainQueue()
        queue.`runIdleHandlers$artzig`()
        check(idleRuns == 2) { "idle handler was not rearmed after work drained" }

        val raced = AtomicInteger()
        val producer = Thread({
            repeat(128) { handler.post { raced.incrementAndGet() } }
        }, "MessageQueue-cross-thread-smoke")
        producer.start()
        producer.join()
        drainMainQueue()
        check(raced.get() == 128) { "enqueue/drain race lost ${128 - raced.get()} messages" }

        val starved = AtomicInteger()
        repeat(620) { handler.post { starved.incrementAndGet() } }
        check(ApkHost.pumpMainLooper() == 0L) {
            "bounded pump did not request immediate starvation rescheduling"
        }
        check(starved.get() == 500) { "starvation guard drained ${starved.get()} messages" }
        drainMainQueue()
        check(starved.get() == 620)

        var enqueueAfterQuit = true
        val quitter = Thread({
            Looper.prepare()
            val privateLooper = checkNotNull(Looper.myLooper())
            privateLooper.quit()
            enqueueAfterQuit = Handler(privateLooper).post { }
        }, "MessageQueue-quit-smoke")
        quitter.start()
        quitter.join()
        check(!enqueueAfterQuit) { "MessageQueue accepted work after quit" }
    }

    private fun testChoreographerPhaseOrder() {
        drainMainQueue()
        val choreographer = Choreographer.getInstance()
        var floodCallbacks = 0
        repeat(100) {
            choreographer.postCallback(
                Choreographer.CALLBACK_ANIMATION,
                { floodCallbacks++ },
                null,
            )
        }
        var wakeMessages = 0
        val queue = checkNotNull(Looper.getMainLooper()).queue
        while (true) {
            val message = queue.`pollDueMessage$artzig`() ?: break
            wakeMessages++
            try {
                message.target.dispatchMessage(message)
            } finally {
                android.os.MessageQueue.`recycleDispatched$artzig`(message)
            }
        }
        check(wakeMessages <= 1) {
            "Choreographer produced $wakeMessages host wakes for one callback deadline"
        }
        choreographer.`doFrame$artzig`(System.nanoTime())
        check(floodCallbacks == 100) {
            "coalescing host wakes lost frame callbacks: $floodCallbacks"
        }

        val observed = mutableListOf<String>()
        choreographer.postCallback(Choreographer.CALLBACK_COMMIT, { observed += "commit" }, null)
        choreographer.postCallback(Choreographer.CALLBACK_TRAVERSAL, { observed += "traversal" }, null)
        choreographer.postCallback(Choreographer.CALLBACK_INPUT, { observed += "input" }, null)
        choreographer.postCallback(Choreographer.CALLBACK_ANIMATION, { observed += "animation" }, null)
        choreographer.`doFrame$artzig`(System.nanoTime())
        check(observed == listOf("input", "animation", "traversal", "commit")) {
            "Choreographer callback phase order: $observed"
        }
    }

    private fun testTraversalRescheduleAndSharedQueue() {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            200,
            200,
            120,
            0,
        )
        val reschedulingView = ReentrantLayoutGroup(context)
        val reschedulingRoot = ViewRootImpl(context)
        reschedulingRoot.setView(reschedulingView, 200, 200)
        try {
            runHostedFrame()
            runHostedFrame()
            reschedulingView.layoutCount = 0
            reschedulingView.invalidate()
            runHostedFrame()
            check(reschedulingView.layoutCount == 0) {
                "draw-only invalidation incorrectly forced another layout pass"
            }

            reschedulingView.layoutCount = 0
            reschedulingView.layoutRequestsRemaining = 1
            reschedulingView.requestLayout()

            runHostedFrame()
            check(reschedulingView.layoutCount == 2) {
                "requestLayout during the first layout pass did not run AOSP's second pass"
            }
            runHostedFrame()
            check(reschedulingView.layoutCount == 2) {
                "one layout request produced a duplicate traversal"
            }
        } finally {
            reschedulingRoot.destroy()
        }

        val firstView = View(context)
        val secondView = View(context)
        val firstRoot = ViewRootImpl(context)
        val secondRoot = ViewRootImpl(context)
        firstRoot.setView(firstView, 80, 90)
        secondRoot.setView(secondView, 120, 130)
        val observed = mutableListOf<String>()
        firstView.post { observed += "first:${firstView.width}x${firstView.height}" }
        secondView.post { observed += "second:${secondView.width}x${secondView.height}" }
        try {
            runHostedFrame()
            check(observed == listOf("first:80x90", "second:120x130")) {
                "roots sharing the main queue did not release callbacks after traversal: $observed"
            }
        } finally {
            firstRoot.destroy()
            secondRoot.destroy()
            drainMainQueue()
        }
    }

    private fun runHostedFrame() {
        drainMainQueue()
        Choreographer.getInstance().`doFrame$artzig`(System.nanoTime())
        drainMainQueue()
    }

    private fun drainMainQueue() {
        drainMainQueueCounting()
    }

    private fun drainMainQueueCounting(): Int {
        val queue = checkNotNull(Looper.getMainLooper()).queue
        var count = 0
        while (true) {
            val message = queue.`pollDueMessage$artzig`() ?: return count
            try {
                message.target.dispatchMessage(message)
                count++
            } finally {
                android.os.MessageQueue.`recycleDispatched$artzig`(message)
            }
        }
    }

    private fun testHostedInputEvents() {
        check(HostedInputDispatcher.createHostKeyCharacterMap().keyboardType ==
            KeyCharacterMap.FULL) {
            "hosted physical keyboard must select AOSP's full-keyboard text path"
        }

        val mouse = HostedInputDispatcher.createMouseEvent(
            downTime = 10,
            eventTime = 20,
            action = MotionEvent.ACTION_BUTTON_PRESS,
            buttonState = MotionEvent.BUTTON_PRIMARY,
            actionButton = MotionEvent.BUTTON_PRIMARY,
            x = 12f,
            y = 34f,
        )
        try {
            check(MotionEvent.FLAG_TARGET_ACCESSIBILITY_FOCUS == 0x40000000) {
                "MotionEvent flags must come from the matching AOSP input AIDL"
            }
            check(mouse.isFromSource(InputDevice.SOURCE_MOUSE))
            check(mouse.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE)
            check(mouse.buttonState == MotionEvent.BUTTON_PRIMARY)
            check(mouse.actionButton == MotionEvent.BUTTON_PRIMARY)
            check(mouse.pressure == 1f)
            check(!mouse.isFromSource(InputDevice.SOURCE_TOUCHSCREEN))
            check(!mouse.isTargetAccessibilityFocus)
            mouse.setTargetAccessibilityFocus(true)
            check(mouse.isTargetAccessibilityFocus)
            mouse.setTargetAccessibilityFocus(false)
            check(!mouse.isTargetAccessibilityFocus)
        } finally {
            mouse.recycle()
        }

        val touch = HostedInputDispatcher.createTouchEvent(
            downTime = 30,
            eventTime = 40,
            action = MotionEvent.ACTION_DOWN,
            x = 56f,
            y = 78f,
        )
        try {
            check(touch.isFromSource(InputDevice.SOURCE_TOUCHSCREEN))
            check(touch.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER)
            check(touch.buttonState == 0)
            check(!touch.isFromSource(InputDevice.SOURCE_MOUSE))
        } finally {
            touch.recycle()
        }
    }

    private fun testHostedInputDispatch() {
        val frameworkResources = System.getenv("ARTZIG_FRAMEWORK_RES")
            ?: "zig-out/res/framework-res.apk"
        val context = HostContexts.systemContext(frameworkResources, 200, 200, 120, 0)
        val observed = mutableListOf<String>()
        val view = object : View(context) {
            init {
                isClickable = true
                contentDescription = "input probe"
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                observed += listOf(
                    "touch",
                    event.actionMasked.toString(),
                    event.source.toString(),
                    event.getToolType(0).toString(),
                    event.buttonState.toString(),
                    event.actionButton.toString(),
                ).joinToString(":")
                return true
            }

            override fun onGenericMotionEvent(event: MotionEvent): Boolean {
                observed += listOf(
                    "generic",
                    event.actionMasked.toString(),
                    event.source.toString(),
                    event.getToolType(0).toString(),
                    event.buttonState.toString(),
                    event.actionButton.toString(),
                    event.getAxisValue(MotionEvent.AXIS_HSCROLL).toString(),
                    event.getAxisValue(MotionEvent.AXIS_VSCROLL).toString(),
                ).joinToString(":")
                return true
            }
        }
        val root = ViewRootImpl(context)
        root.setView(view, 200, 200)
        view.layout(0, 0, 200, 200)
        try {
            root.ensureTouchMode(false)
            view.viewTreeObserver.addOnTouchModeChangeListener { inTouchMode ->
                observed += "touch-mode:$inTouchMode"
            }
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_DOWN,
                MotionEvent.BUTTON_PRIMARY, 0, 20f, 30f,
            )
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_BUTTON_PRESS,
                MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY, 20f, 30f,
            )
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_BUTTON_RELEASE,
                0, MotionEvent.BUTTON_PRIMARY, 20f, 30f,
            )
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_UP, 0, 0, 20f, 30f,
            )
            HostedInputDispatcher.dispatchTouch(
                root, MotionEvent.ACTION_DOWN, 40f, 50f,
            )
            HostedInputDispatcher.dispatchTouch(
                root, MotionEvent.ACTION_UP, 40f, 50f,
            )
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_DOWN,
                MotionEvent.BUTTON_SECONDARY, 0, 60f, 70f,
            )
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_BUTTON_PRESS,
                MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_SECONDARY, 60f, 70f,
            )
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_BUTTON_RELEASE,
                0, MotionEvent.BUTTON_SECONDARY, 60f, 70f,
            )
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_UP, 0, 0, 60f, 70f,
            )
            HostedInputDispatcher.dispatchHover(
                root, MotionEvent.ACTION_HOVER_ENTER, 80f, 90f,
            )
            HostedInputDispatcher.dispatchHover(
                root, MotionEvent.ACTION_HOVER_MOVE, 81f, 91f,
            )
            HostedInputDispatcher.dispatchScroll(
                root, 81f, 91f, 2f, -3f,
            )
            HostedInputDispatcher.dispatchHover(
                root, MotionEvent.ACTION_HOVER_EXIT, 81f, 91f,
            )

            val mousePrefix = "${InputDevice.SOURCE_MOUSE}:${MotionEvent.TOOL_TYPE_MOUSE}"
            val touchPrefix =
                "${InputDevice.SOURCE_TOUCHSCREEN}:${MotionEvent.TOOL_TYPE_FINGER}"
            check(observed == listOf(
                "touch-mode:true",
                "touch:${MotionEvent.ACTION_DOWN}:$mousePrefix:${MotionEvent.BUTTON_PRIMARY}:0",
                "generic:${MotionEvent.ACTION_BUTTON_PRESS}:$mousePrefix:" +
                    "${MotionEvent.BUTTON_PRIMARY}:${MotionEvent.BUTTON_PRIMARY}:0.0:0.0",
                "generic:${MotionEvent.ACTION_BUTTON_RELEASE}:$mousePrefix:0:" +
                    "${MotionEvent.BUTTON_PRIMARY}:0.0:0.0",
                "touch:${MotionEvent.ACTION_UP}:$mousePrefix:0:0",
                "generic:${MotionEvent.ACTION_HOVER_ENTER}:$mousePrefix:0:0:0.0:0.0",
                "touch:${MotionEvent.ACTION_DOWN}:$touchPrefix:0:0",
                "touch:${MotionEvent.ACTION_UP}:$touchPrefix:0:0",
                "touch:${MotionEvent.ACTION_DOWN}:$mousePrefix:${MotionEvent.BUTTON_SECONDARY}:0",
                "generic:${MotionEvent.ACTION_BUTTON_PRESS}:$mousePrefix:" +
                    "${MotionEvent.BUTTON_SECONDARY}:${MotionEvent.BUTTON_SECONDARY}:0.0:0.0",
                "generic:${MotionEvent.ACTION_BUTTON_RELEASE}:$mousePrefix:0:" +
                    "${MotionEvent.BUTTON_SECONDARY}:0.0:0.0",
                "touch:${MotionEvent.ACTION_UP}:$mousePrefix:0:0",
                "generic:${MotionEvent.ACTION_HOVER_MOVE}:$mousePrefix:0:0:0.0:0.0",
                "generic:${MotionEvent.ACTION_HOVER_ENTER}:$mousePrefix:0:0:0.0:0.0",
                "generic:${MotionEvent.ACTION_HOVER_MOVE}:$mousePrefix:0:0:0.0:0.0",
                "generic:${MotionEvent.ACTION_SCROLL}:$mousePrefix:0:0:2.0:-3.0",
                "generic:${MotionEvent.ACTION_HOVER_EXIT}:$mousePrefix:0:0:0.0:0.0",
            )) { "hosted input dispatch contract: $observed" }
            check(root.lastTouchSource == InputDevice.SOURCE_MOUSE)
            check(root.lastTouchPointerId == 0)
            check(root.lastClickToolType == MotionEvent.TOOL_TYPE_MOUSE)
            val lastTouchPoint = Point()
            root.getLastTouchPoint(lastTouchPoint)
            check(lastTouchPoint == Point(60, 70))
        } finally {
            HostedInputDispatcher.reset()
            root.destroy()
        }
    }

    private fun testHostedPhysicalInputLifecycle() {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            200,
            200,
            120,
            0,
        )
        val touchEvents = mutableListOf<String>()
        val keyEvents = mutableListOf<String>()
        val captureEvents = mutableListOf<String>()
        val view = object : View(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                val pointers = (0 until event.pointerCount).joinToString(",") { index ->
                    "${event.getPointerId(index)}@${event.getX(index)}:${event.getTouchMajor(index)}"
                }
                touchEvents += "${event.actionMasked}:${event.actionIndex}:$pointers"
                return true
            }

            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                keyEvents += listOf(
                    event.action,
                    event.keyCode,
                    event.scanCode,
                    event.repeatCount,
                    event.metaState,
                    event.unicodeChar,
                    event.flags,
                ).joinToString(":")
                return true
            }

            override fun onPointerCaptureChange(hasCapture: Boolean) {
                captureEvents += "capture:$hasCapture"
            }

            override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
                captureEvents += listOf(
                    "motion",
                    event.actionMasked,
                    event.source,
                    event.buttonState,
                    event.actionButton,
                    event.getAxisValue(MotionEvent.AXIS_RELATIVE_X),
                    event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y),
                ).joinToString(":")
                return true
            }
        }
        val root = ViewRootImpl(context)
        root.setView(view, 200, 200)
        view.layout(0, 0, 200, 200)
        try {
            HostedInputDispatcher.dispatchTouchContact(root, 0, 7, 10f, 20f, 8f, 4f, 0.25f)
            HostedInputDispatcher.dispatchTouchContact(root, 0, 3, 30f, 40f, 6f, 3f, -0.5f)
            HostedInputDispatcher.dispatchTouchContact(root, 2, 7, 11f, 21f, 9f, 4f, 0.25f)
            HostedInputDispatcher.dispatchTouchContact(root, 1, 7, 11f, 21f, 9f, 4f, 0.25f)
            HostedInputDispatcher.cancelTouch(root)
            check(touchEvents == listOf(
                "${MotionEvent.ACTION_DOWN}:0:0@10.0:8.0",
                "${MotionEvent.ACTION_POINTER_DOWN}:1:0@10.0:8.0,1@30.0:6.0",
                "${MotionEvent.ACTION_MOVE}:0:0@11.0:9.0,1@30.0:6.0",
                "${MotionEvent.ACTION_POINTER_UP}:0:0@11.0:9.0,1@30.0:6.0",
                "${MotionEvent.ACTION_CANCEL}:0:1@30.0:6.0",
            )) { "physical touch lifecycle: $touchEvents" }

            HostedInputDispatcher.dispatchPhysicalKey(root, KeyEvent.ACTION_DOWN, 42, 0)
            HostedInputDispatcher.dispatchPhysicalKey(root, KeyEvent.ACTION_DOWN, 30, 'A'.code)
            HostedInputDispatcher.dispatchPhysicalKey(root, KeyEvent.ACTION_DOWN, 30, 'A'.code)
            HostedInputDispatcher.dispatchPhysicalKey(root, KeyEvent.ACTION_UP, 30, 0)
            HostedInputDispatcher.dispatchPhysicalKey(root, KeyEvent.ACTION_UP, 42, 0)
            check(keyEvents.size == 5)
            check(keyEvents[0].startsWith("0:${KeyEvent.KEYCODE_SHIFT_LEFT}:42:0:"))
            check(keyEvents[1] == "0:${KeyEvent.KEYCODE_A}:30:0:" +
                "${KeyEvent.normalizeMetaState(KeyEvent.META_SHIFT_LEFT_ON)}:${'A'.code}:0")
            check(keyEvents[2] == "0:${KeyEvent.KEYCODE_A}:30:1:" +
                "${KeyEvent.normalizeMetaState(KeyEvent.META_SHIFT_LEFT_ON)}:${'A'.code}:0")
            check(keyEvents[3].startsWith("1:${KeyEvent.KEYCODE_A}:30:0:"))
            check(keyEvents[4] == "1:${KeyEvent.KEYCODE_SHIFT_LEFT}:42:0:0:0:0")

            keyEvents.clear()
            HostedInputDispatcher.dispatchPhysicalKey(root, KeyEvent.ACTION_DOWN, 42, 0)
            HostedInputDispatcher.dispatchPhysicalKey(root, KeyEvent.ACTION_DOWN, 30, 'A'.code)
            HostedInputDispatcher.resetKeyboard()
            check(keyEvents.takeLast(2) == listOf(
                "1:${KeyEvent.KEYCODE_A}:30:0:" +
                    "${KeyEvent.normalizeMetaState(KeyEvent.META_SHIFT_LEFT_ON)}:0:${KeyEvent.FLAG_CANCELED}",
                "1:${KeyEvent.KEYCODE_SHIFT_LEFT}:42:0:0:0:${KeyEvent.FLAG_CANCELED}",
            )) { "keyboard focus-loss cancellation: $keyEvents" }

            view.requestPointerCapture()
            check(HostedInputDispatcher.wantsPointerCapture())
            check(!view.hasPointerCapture())
            HostedInputDispatcher.onPointerCaptureChanged(true)
            check(view.hasPointerCapture())
            check(HostedInputDispatcher.dispatchCapturedPointer(2.5f, -4f, 0))
            check(HostedInputDispatcher.dispatchCapturedMouse(
                MotionEvent.ACTION_BUTTON_PRESS,
                MotionEvent.BUTTON_PRIMARY,
                MotionEvent.BUTTON_PRIMARY,
            ))
            view.releasePointerCapture()
            check(!HostedInputDispatcher.wantsPointerCapture())
            HostedInputDispatcher.onPointerCaptureChanged(false)
            check(!view.hasPointerCapture())
            check(captureEvents == listOf(
                "capture:true",
                "motion:${MotionEvent.ACTION_MOVE}:${InputDevice.SOURCE_MOUSE_RELATIVE}:0:0:2.5:-4.0",
                "motion:${MotionEvent.ACTION_BUTTON_PRESS}:${InputDevice.SOURCE_MOUSE_RELATIVE}:" +
                    "${MotionEvent.BUTTON_PRIMARY}:${MotionEvent.BUTTON_PRIMARY}:0.0:0.0",
                "capture:false",
            )) { "pointer capture lifecycle: $captureEvents" }
        } finally {
            HostedInputDispatcher.reset()
            root.destroy()
        }
    }


    private fun testHostedInputHierarchyDispatch() {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            200,
            200,
            120,
            0,
        )
        val observed = mutableListOf<String>()
        val child = RecordingInputView(context, "child", observed).apply {
            isClickable = true
            contentDescription = "transformed child"
            translationX = 20f
        }
        val parent = InterceptingInputGroup(context, observed).apply { addView(child) }
        val root = ViewRootImpl(context)
        root.setView(parent, 200, 200)
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, 200, 200)
        try {
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_DOWN,
                MotionEvent.BUTTON_PRIMARY, 0, 50f, 50f,
            )
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_UP, 0, 0, 50f, 50f,
            )
            check(observed == listOf(
                "intercept:0", "parent:0", "parent:1", "child:g:9",
            )) {
                "root hierarchy must own hit testing and interception: $observed"
            }
        } finally {
            HostedInputDispatcher.reset()
            root.destroy()
        }
    }

    private fun testHostedMouseSeekBarDrag() {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            200,
            100,
            120,
            0,
        )
        val seekBar = SeekBar(context).apply {
            max = 1_000
            progress = 0
        }
        val parent = FrameLayout(context).apply {
            addView(seekBar, FrameLayout.LayoutParams(200, 30))
        }
        val root = ViewRootImpl(context)
        root.setView(parent, 200, 100)
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, 200, 100)
        try {
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_DOWN,
                MotionEvent.BUTTON_PRIMARY, 0, 10f, 15f,
            )
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_MOVE,
                MotionEvent.BUTTON_PRIMARY, 0, 150f, 15f,
            )
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_UP, 0, 0, 150f, 15f,
            )
            check(seekBar.progress > 600) {
                "mouse drag must update SeekBar progress: ${seekBar.progress}"
            }
        } finally {
            HostedInputDispatcher.reset()
            root.destroy()
        }
    }

    private fun testHostedInputCancelOnInterception() {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            200,
            200,
            120,
            0,
        )
        val observed = mutableListOf<String>()
        val child = RecordingInputView(context, "child", observed).apply { isClickable = true }
        val parent = MoveInterceptingInputGroup(context, observed).apply { addView(child) }
        val root = ViewRootImpl(context)
        root.setView(parent, 200, 200)
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, 200, 200)
        try {
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_DOWN,
                MotionEvent.BUTTON_PRIMARY, 0, 50f, 50f,
            )
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_MOVE,
                MotionEvent.BUTTON_PRIMARY, 0, 50f, 20f,
            )
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_MOVE,
                MotionEvent.BUTTON_PRIMARY, 0, 50f, 10f,
            )
            check(observed == listOf("child:0", "child:3", "parent:2")) {
                "interception must cancel the original mouse target before scrolling: $observed"
            }
        } finally {
            HostedInputDispatcher.reset()
            root.destroy()
        }
    }

    private fun testHostedInputWindowRouting() {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            200,
            200,
            120,
            0,
        )
        val observed = mutableListOf<String>()
        val activity = RecordingInputView(context, "activity", observed).apply { isClickable = true }
        val activityRoot = ViewRootImpl(context)
        activityRoot.setView(activity, 200, 200)
        activity.measure(
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
        )
        activity.layout(0, 0, 200, 200)
        val manager = WindowManagerImpl(context)
        try {
            val lower = addInputWindow(
                manager, context, "lower", observed,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                0, 0, 100, 100,
            )
            addInputWindow(
                manager, context, "upper", observed,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                100, 0, 200, 100,
            )
            addInputWindow(
                manager, context, "untouchable", observed,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                0, 0, 100, 100,
            )

            HostedInputDispatcher.dispatchMouse(
                activityRoot, MotionEvent.ACTION_DOWN,
                MotionEvent.BUTTON_PRIMARY, 0, 50f, 50f,
            )
            HostedInputDispatcher.dispatchMouse(
                activityRoot, MotionEvent.ACTION_UP, 0, 0, 50f, 50f,
            )
            check(observed == listOf("upper:4", "lower:0", "lower:1", "lower:g:9")) {
                "non-modal outside routing must preserve one gesture root: $observed"
            }

            observed.clear()
            HostedInputDispatcher.dispatchHover(
                activityRoot, MotionEvent.ACTION_HOVER_ENTER, 50f, 50f,
            )
            HostedInputDispatcher.dispatchHover(
                activityRoot, MotionEvent.ACTION_HOVER_MOVE, 150f, 50f,
            )
            HostedInputDispatcher.dispatchHover(
                activityRoot, MotionEvent.ACTION_HOVER_EXIT, 150f, 50f,
            )
            check(observed == listOf("lower:g:9", "lower:g:10", "upper:g:9", "upper:g:10")) {
                "hover target changes must exit the old root before entering the new root: $observed"
            }

            android.view.ArtzigWindowStack.clear()
            observed.clear()
            addInputWindow(
                manager, context, "modal", observed,
                0,
                100, 0, 200, 100,
            )
            HostedInputDispatcher.dispatchMouse(
                activityRoot, MotionEvent.ACTION_DOWN,
                MotionEvent.BUTTON_PRIMARY, 0, 50f, 50f,
            )
            HostedInputDispatcher.dispatchMouse(
                activityRoot, MotionEvent.ACTION_UP, 0, 0, 50f, 50f,
            )
            check(observed == listOf("modal:0", "modal:1", "modal:g:9")) {
                "modal window must own an outside gesture: $observed"
            }
            check(lower.root.view == null) { "cleared overlay root remained attached" }
        } finally {
            HostedInputDispatcher.reset()
            android.view.ArtzigWindowStack.clear()
            activityRoot.destroy()
        }
    }

    private fun testHostedInputSiblingDispatch() {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            200,
            200,
            120,
            0,
        )
        val observed = mutableListOf<String>()
        val bottom = RecordingInputView(context, "bottom", observed).apply { isClickable = true }
        val top = RecordingInputView(context, "top", observed).apply {
            isClickable = true
            translationX = 20f
        }
        val parent = OverlayInputGroup(context).apply {
            addView(bottom)
            addView(top)
        }
        val root = ViewRootImpl(context)
        root.setView(parent, 200, 200)
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, 200, 200)
        try {
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_DOWN,
                MotionEvent.BUTTON_PRIMARY, 0, 100f, 50f,
            )
            HostedInputDispatcher.dispatchMouse(
                root, MotionEvent.ACTION_UP, 0, 0, 100f, 50f,
            )
            check(observed == listOf("top:0", "top:1", "top:g:9")) {
                "ViewGroup must resolve transformed overlapping siblings: $observed"
            }
        } finally {
            HostedInputDispatcher.reset()
            root.destroy()
        }
    }

    private fun addInputWindow(
        manager: WindowManagerImpl,
        context: Context,
        name: String,
        observed: MutableList<String>,
        flags: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): android.view.ArtzigWindowStack.Entry {
        val view = RecordingInputView(context, name, observed).apply { isClickable = true }
        val params = WindowManager.LayoutParams().apply {
            width = right - left
            height = bottom - top
            this.flags = flags
            gravity = Gravity.TOP or Gravity.LEFT
        }
        manager.addView(view, params)
        val entry = android.view.ArtzigWindowStack.entries().last()
        entry.root.setSize(right - left, bottom - top)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(right - left, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(bottom - top, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, right - left, bottom - top)
        entry.frame.set(left, top, right, bottom)
        return entry
    }

    private class RecordingInputView(
        context: Context,
        private val name: String,
        private val observed: MutableList<String>,
    ) : View(context) {
        override fun onTouchEvent(event: MotionEvent): Boolean {
            observed += "$name:${event.actionMasked}"
            return true
        }

        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            observed += "$name:g:${event.actionMasked}"
            return true
        }
    }

    private class InterceptingInputGroup(
        context: Context,
        private val observed: MutableList<String>,
    ) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            getChildAt(0).measure(
                MeasureSpec.makeMeasureSpec(80, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(80, MeasureSpec.EXACTLY),
            )
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            getChildAt(0).layout(10, 10, 90, 90)
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) observed += "intercept:0"
            return event.actionMasked == MotionEvent.ACTION_DOWN
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            observed += "parent:${event.actionMasked}"
            return true
        }
    }

    private class MoveInterceptingInputGroup(
        context: Context,
        private val observed: MutableList<String>,
    ) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            getChildAt(0).measure(widthMeasureSpec, heightMeasureSpec)
            setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec),
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            getChildAt(0).layout(0, 0, right - left, bottom - top)
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
            event.actionMasked == MotionEvent.ACTION_MOVE

        override fun onTouchEvent(event: MotionEvent): Boolean {
            observed += "parent:${event.actionMasked}"
            return true
        }
    }

    private class OverlayInputGroup(context: Context) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            for (index in 0 until childCount) {
                getChildAt(index).measure(
                    MeasureSpec.makeMeasureSpec(80, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(80, MeasureSpec.EXACTLY),
                )
            }
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            getChildAt(0).layout(0, 10, 80, 90)
            getChildAt(1).layout(10, 10, 90, 90)
        }
    }

    private class DeferredGeometryGroup(
        context: Context,
        child: View,
    ) : ViewGroup(context) {
        var childTop = 10

        init {
            addView(child)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            getChildAt(0).measure(
                MeasureSpec.makeMeasureSpec(50, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(50, MeasureSpec.EXACTLY),
            )
            setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec),
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            getChildAt(0).layout(0, childTop, 50, childTop + 50)
        }
    }

    private class ReentrantLayoutGroup(context: Context) : ViewGroup(context) {
        private val child = View(context)
        var layoutCount = 0
        var layoutRequestsRemaining = 0

        init {
            addView(child)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            child.measure(
                MeasureSpec.makeMeasureSpec(50, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(50, MeasureSpec.EXACTLY),
            )
            setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec),
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            layoutCount++
            child.layout(0, 0, 50, 50)
            if (layoutRequestsRemaining > 0) {
                layoutRequestsRemaining--
                child.requestLayout()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun testWindowPresentation() {
        val defaultVisible = WindowInsets.Type.defaultVisible()
        val legacySystemBars = WindowInsets.Type.statusBars() or
            WindowInsets.Type.navigationBars()
        check(HostedSystemUiController.hiddenTypesForLegacyVisibility(0) == 0)
        check(HostedSystemUiController.hiddenTypesForLegacyVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION,
        ) == legacySystemBars)
        check(!HostedWindowPresentation.requestsFullscreen(0, 0, defaultVisible, false))
        check(HostedWindowPresentation.requestsFullscreen(
            View.SYSTEM_UI_FLAG_FULLSCREEN,
            0,
            defaultVisible,
            false,
        ))
        check(HostedWindowPresentation.requestsFullscreen(
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION,
            0,
            defaultVisible,
            false,
        ))
        check(HostedWindowPresentation.requestsFullscreen(
            0,
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            defaultVisible,
            false,
        ))

        val transientControls = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        check(!HostedWindowPresentation.requestsFullscreen(
            transientControls,
            0,
            defaultVisible,
            false,
        )) { "immersive layout alone must not enter host fullscreen" }
        check(HostedWindowPresentation.requestsFullscreen(
            transientControls,
            0,
            defaultVisible,
            true,
        )) { "temporarily visible system bars must not exit host fullscreen" }

        val modernImmersive = defaultVisible and WindowInsets.Type.systemBars().inv()
        check(HostedWindowPresentation.requestsFullscreen(0, 0, modernImmersive, false))
    }

    private fun testActivityLifecycle() {
        events.clear()
        createdThemeResIds.clear()
        val firstName = FirstActivity::class.java.name
        val secondName = SecondActivity::class.java.name
        val firstInfo = activityInfo(firstName).apply {
            configChanges = ActivityInfo.CONFIG_SCREEN_SIZE
            theme = 0x7f012345
        }
        val secondInfo = activityInfo(secondName).apply {
            applicationInfo.theme = 0x7f012346
        }
        val adapter = RecordingHostAdapter()

        ActivityThread.configureActivityHost(
            adapter,
            mapOf(firstName to firstInfo, secondName to secondInfo),
        )
        try {
            val first = ActivityThread.launchMainActivity(firstName)
            check(events == listOf("first:create", "first:start", "first:resume", "show:first")) {
                "main launch ordering: $events"
            }
            check(createdThemeResIds["first"] == firstInfo.getThemeResource()) {
                "activity theme must be applied before onCreate"
            }

            ActivityThread.startActivityInProcess(
                Intent().setComponent(ComponentName("org.example", secondName)),
            )
            check(events.takeLast(6) == listOf(
                "first:pause",
                "second:create",
                "second:start",
                "second:resume",
                "first:stop",
                "show:second",
            )) { "second launch ordering: $events" }
            check(createdThemeResIds["second"] == secondInfo.getThemeResource()) {
                "application theme fallback must be applied before onCreate"
            }

            checkNotNull(ActivityThread.currentActivity()).finish()
            check(ActivityThread.currentActivity() === first)
            check(events.takeLast(7) == listOf(
                "second:pause",
                "second:stop",
                "second:destroy",
                "clear",
                "first:start",
                "first:resume",
                "show:first",
            )) { "finish ordering: $events" }

            val shareTarget = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "https://example.com/shared")
            val chooser = Intent.createChooser(shareTarget, "Share")
            adapter.implicitActivity = ComponentName("org.example", secondName)
            ActivityThread.startActivityInProcess(chooser)
            val resolvedShare = checkNotNull(ActivityThread.currentActivity())
            check(resolvedShare is SecondActivity)
            check(resolvedShare.intent.action == Intent.ACTION_CHOOSER)
            check(resolvedShare.intent.component == adapter.implicitActivity)
            check(resolvedShare.intent.getParcelableExtra(
                Intent.EXTRA_INTENT,
                Intent::class.java,
            )?.getCharSequenceExtra(Intent.EXTRA_TEXT) == "https://example.com/shared")
            check(adapter.lastImplicitIntent?.component == null) {
                "resolver must observe the original implicit intent"
            }
            resolvedShare.finish()
            check(ActivityThread.currentActivity() === first)
            adapter.implicitActivity = null

            val handledConfiguration = Configuration().apply { setToDefaults() }
            ActivityThread.applyConfigurationInProcess(
                handledConfiguration,
                ActivityInfo.CONFIG_SCREEN_SIZE,
                600,
                900,
            )
            check(events.takeLast(2) == listOf("resize:first:600x900", "first:config")) {
                "handled configuration ordering: $events"
            }

            first.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ActivityThread.applyConfigurationInProcess(
                handledConfiguration,
                ActivityInfo.CONFIG_ORIENTATION,
                900,
                600,
            )
            check(ActivityThread.currentActivity() !== first)
            check(events.contains("first:save"))
            check(events.takeLast(5) == listOf(
                "first:create:saved",
                "first:start",
                "first:restore",
                "first:resume",
                "show:first",
            )) { "relaunch restore ordering: $events" }
            check(ActivityThread.currentActivity()?.requestedOrientation ==
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                "configuration relaunch lost the activity's requested orientation"
            }

            events.clear()
            checkNotNull(ActivityThread.currentActivity()).finish()
            check(ActivityThread.currentActivity() == null)
            check(events == listOf(
                "first:pause",
                "first:stop",
                "first:destroy",
                "clear",
            )) { "last Activity finish ordering: $events" }
        } finally {
            ActivityThread.shutdownActivitiesInProcess()
        }
    }

    private fun activityInfo(className: String) = ActivityInfo().apply {
        name = className
        packageName = "org.example"
        applicationInfo = ApplicationInfo().apply {
            packageName = "org.example"
        }
        screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private class RecordingHostAdapter : ActivityThread.HostAdapter {
        var implicitActivity: ComponentName? = null
        var lastImplicitIntent: Intent? = null

        override fun getActivityContext(): Context? = null

        override fun getActivityClassLoader(): ClassLoader =
            checkNotNull(javaClass.classLoader)

        override fun showActivity(activity: Activity) {
            events += "show:${(activity as RecordingActivity).label}"
        }

        override fun clearActivityWindows() {
            events += "clear"
        }

        override fun resizeActivityRoot(activity: Activity, width: Int, height: Int) {
            events += "resize:${(activity as RecordingActivity).label}:${width}x$height"
        }

        override fun resolveActivity(intent: Intent): ComponentName? {
            lastImplicitIntent = Intent(intent)
            return implicitActivity
        }
    }

    open class RecordingActivity(private val activityLabel: String) : Activity() {
        val label: String get() = activityLabel

        override fun attach(base: Context?, activityInfo: ActivityInfo?) {
            setRequestedOrientation(
                activityInfo?.screenOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            )
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            createdThemeResIds[activityLabel] = themeResId
            events += if (savedInstanceState?.getString("state") == "saved") {
                "$activityLabel:create:saved"
            } else {
                "$activityLabel:create"
            }
        }

        override fun onStart() { events += "$activityLabel:start" }
        override fun onResume() { events += "$activityLabel:resume" }
        override fun onPause() { events += "$activityLabel:pause" }
        override fun onStop() { events += "$activityLabel:stop" }
        override fun onDestroy() { events += "$activityLabel:destroy" }
        override fun onConfigurationChanged(newConfig: Configuration) {
            events += "$activityLabel:config"
        }

        override fun onSaveInstanceState(outState: Bundle) {
            events += "$activityLabel:save"
            outState.putString("state", "saved")
        }

        override fun onRestoreInstanceState(savedInstanceState: Bundle) {
            check(savedInstanceState.getString("state") == "saved")
            events += "$activityLabel:restore"
        }
    }

    class FirstActivity : RecordingActivity("first")
    class SecondActivity : RecordingActivity("second")
}
