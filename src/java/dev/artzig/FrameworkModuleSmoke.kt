@file:Suppress("DEPRECATION")

package dev.artzig

import com.android.settingslib.flags.Flags as SettingsLibFlags
import com.android.server.companion.virtual.VirtualDeviceManagerInternal

import com.android.internal.org.bouncycastle.cert.X509CertificateHolder
import com.android.internal.org.bouncycastle.cms.CMSSignedData
import com.android.internal.org.bouncycastle.util.CollectionStore
import android.hardware.usb.IUsbSerialReader
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.app.Activity
import android.app.ActivityClient
import android.app.ActivityGroup
import android.app.ActivityOptions
import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.ActivityThread
import android.app.ComponentCaller
import android.app.DownloadManager
import android.app.GrammaticalInflectionManager
import android.app.IActivityClientController
import android.app.IApplicationThread
import android.app.Instrumentation
import android.app.LocalActivityManager
import android.app.Notification
import android.app.Person
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.app.ResultInfo
import android.app.TaskInfo
import android.app.TaskStackListener
import android.app.UiModeManager
import android.app.servertransaction.ActivityConfigurationChangeItem
import android.app.servertransaction.ActivityResultItem
import android.app.servertransaction.ActivityRelaunchItem
import android.app.servertransaction.ClientTransaction
import android.app.servertransaction.ClientTransactionListenerController
import android.app.servertransaction.ConfigurationChangeItem
import android.app.servertransaction.DestroyActivityItem
import android.app.servertransaction.LaunchActivityItem
import android.app.servertransaction.PauseActivityItem
import android.app.servertransaction.PendingTransactionActions
import android.app.servertransaction.ResumeActivityItem
import android.app.servertransaction.StopActivityItem
import android.content.AttributionSource
import android.content.ComponentName
import android.content.ComponentCallbacks
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.LocusId
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.ApkAssets
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.HostedConfigurationController
import android.health.connect.HealthPermissions
import android.health.connect.HealthPermissionCategory
import android.health.connect.HealthDataCategory
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
import android.net.InetAddresses
import android.net.INetworkManagementEventObserver
import android.net.InterfaceConfiguration
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.os.Bundle
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.INetworkManagementService
import android.os.Looper
import com.android.internal.pm.pkg.parsing.ParsingUtils
import com.android.internal.pm.pkg.component.AconfigFlags
import com.android.internal.os.BinderCallsStats
import com.android.internal.os.BinderLatencyObserver
import com.android.internal.os.CachedDeviceState
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.os.PersistableBundle
import android.os.StatFs
import android.os.UserHandle
import android.provider.Downloads
import android.util.SuperNotCalledException
import android.security.net.config.NetworkSecurityConfig
import android.security.net.config.SystemCertificateSource
import android.security.net.config.CertificatesEntryRef
import android.security.net.config.NetworkSecurityConfigProvider
import android.security.net.config.Pin
import android.security.net.config.PinSet
import android.service.contentcapture.ContentCaptureService
import android.service.textclassifier.TextClassifierService
import android.service.voice.VisualQueryDetectedResult
import android.net.Uri
import android.net.http.X509TrustManagerExtensions
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.opengl.GLSurfaceView
import android.util.Rational
import android.util.MergedConfiguration
import android.util.SparseArray
import android.util.proto.ProtoOutputStream
import android.view.Choreographer
import android.view.ArtzigVideoLayer
import android.view.contentcapture.ContentCaptureEvent
import android.view.contentcapture.IContentCaptureManager
import android.view.contentprotection.ContentProtectionEventProcessor
import android.view.HostedSystemUiController
import android.view.HostedInputDispatcher
import android.view.HostedWindowPresentation
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Gravity
import android.view.Display
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowManagerImpl
import android.view.ViewRootImpl
import android.window.ActivityWindowInfo
import android.window.IWindowContainerToken
import android.window.TaskSnapshot
import android.window.WindowContainerToken
import dev.artzig.system.HostedActivityManagerService
import dev.artzig.system.HostedActivityTaskManagerService
import dev.artzig.system.HostedPackageManagerService
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.SeekBar
import com.android.internal.util.RingBuffer
import com.android.internal.util.TraceBuffer
import com.android.internal.content.ReferrerIntent
import java.io.ByteArrayInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.X509TrustManager
import javax.microedition.khronos.opengles.GL10

/** Behavioral smoke tests whose surface is the hosted framework modules. */
internal object FrameworkModuleSmoke {
    private val events = mutableListOf<String>()
    private val legacyFragmentEvents = mutableListOf<String>()
    private val localActivityEvents = mutableListOf<String>()
    private val activityGroupEvents = mutableListOf<String>()
    private val activityResultEvents = mutableListOf<String>()
    private val activityConfigurationEvents = mutableListOf<String>()
    private val activityRelaunchEvents = mutableListOf<String>()
    private val changingConfigurationEvents = mutableListOf<String>()
    private val createdThemeResIds = mutableMapOf<String, Int>()
    private var expectedLocalActivityParent: Activity? = null
    private var activityRelaunchGeneration = 0
    private val transactionCompletionEvents = mutableListOf<String>()

    private var permissionsFixtureApk: String? = null

    private fun packageIdentityContext(base: Context, packageName: String): Context =
        object : ContextWrapper(base.createPackageContext(packageName, 0)) {
            override fun getPackageName(): String = packageName
            override fun getOpPackageName(): String = packageName
        }

    @JvmStatic
    fun main(args: Array<String>) {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        HostContexts.bootstrap(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")))
        permissionsFixtureApk = args.firstOrNull()
        testPackageNames()
        testStockModuleClientContracts()
        testTaskInfoParcelableContracts()
        testWindowContextTransactionContracts()
        testAccessibilityHtmlFiltering()
        testFrameworkServiceValueContracts()
        testBinderCallStatistics()
        testStockDebugMemory()
        testStockEgl()
        StockLegacyGlesSmoke.run()
        StockLegacyEglSmoke.run()
        testStockGraphicBuffers()
        testStockOverlayProperties()
        dev.artzig.window.HostedLogicalDisplaySmoke.run()
        dev.artzig.window.WaylandTaskInputSmoke.run()
        dev.artzig.testing.SessionJournalSmoke.run()
        dev.artzig.testing.SessionObservationSmoke.run()
        dev.artzig.window.WaylandPointerCaptureSmoke.run()
        dev.artzig.window.WaylandKeyboardMapSmoke.run()
        testStockSurfaceSynchronization()
        testStockPixelCopy()
        testStockVerity()
        testStockUsbDevice()
        testStockMediaContracts()
        StockPerformanceHintSmoke.run()
        StockNetworkStatsSmoke.run()
        StockNetworkPolicySmoke.run()
        StockUsbSmoke.run()
        StockMediaCryptoSmoke.run()
        StockMediaMetadataRetrieverSmoke.run()
        StockFileObserverSmoke.run()
        StockLegacyDrmSmoke.run()
        StockDownloadFileSupportSmoke.run()
        StockMediaDrmSmoke.run()
        StockMediaHttpSmoke.run()
        StockHidlMemorySmoke.run()
        StockZygoteCommandBufferSmoke.run()
        StockLegacyCameraSmoke.run()
        StockIpSecProposalSmoke.run()
        StockImageSmoke.run()
        testStockProtoLog()
        com.android.server.criticalevents.StockCriticalEventLogSmoke.run()
        com.android.server.StockWatchdogSmoke.run()
        dev.artzig.system.StockSdkSandboxSmoke.run()
        dev.artzig.system.HostedPowerLocalSmoke.run()
        dev.artzig.system.HostedConfigurationUsageSmoke.run()
        dev.artzig.system.HostedProcessObserversSmoke.run()
        dev.artzig.system.DesktopDeviceStateSmoke.run(ActivityThread.currentActivityThread().systemContext)
        dev.artzig.window.HostedDesktopWindowPolicySmoke.run(ActivityThread.currentActivityThread().systemContext)
        dev.artzig.window.HostedSessionDisplaySmoke.run(ActivityThread.currentActivityThread().systemContext)
        com.android.server.devicestate.StockDeviceStateSmoke.run(ActivityThread.currentActivityThread().systemContext)
        dev.artzig.system.StockOptionalWmServicesSmoke.run(
            ActivityThread.currentActivityThread().systemContext, checkNotNull(permissionsFixtureApk))
        dev.artzig.system.StockSaferIntentSmoke.run()
        com.android.server.wallpaper.StockWallpaperCropSmoke.run()
        StockInstantAppResolutionSmoke.run()
        com.android.server.display.color.StockAppSaturationSmoke.run()
        testStockActivityOptions()
        testStockInstrumentation()
        StockGlSurfaceViewSmoke.run()
        StockActivitySupportSmoke.run(::drainMainQueue)
        StockConfigurationSmoke.run(::drainMainQueue)
        StockActivityCallbacksSmoke.run()
        StockGraphicsEnvironmentSmoke.run()
        StockFenceSmoke.run()
        StockPerfettoSmoke.run()
        testSplashScreenLifecycle()
        testStockWindowContexts()
        testQueuedRootInput()
        testHealthPermissionContracts()
        testAconfigManifestFlags()
        testContentCaptureContracts()
        testTraceBufferContracts()
        testProviderContracts()
        testStatFsContracts()
        testIntentTypedExtras()
        testConfigurationCalculation()
        testConfigurationContexts()
        testMessageQueueSyncBarrier()
        testMessageQueueHostPumpSemantics()
        StockNdkLooperSmoke.run(::drainMainQueue)
        StockNdkGraphicsSmoke.run()
        StockNativePropertiesSmoke.run()
        StockDisplayEventsSmoke.run()
        testChoreographerPhaseOrder()
        testAccessibilityInspectorContracts()
        testAccessibilityVirtualNodes()
        testSimpleRendererContracts()
        testPostedCallbackRunsAfterPendingTraversal()
        testTraversalRescheduleAndSharedQueue()
        testHostedSystemServices()
        testStockActivityManagerAndBackup()
        testStockNetworkScoring()
        StockWifiP2pSmoke.run(::drainMainQueue)
        com.android.server.stats.StockStatsManagerSmoke.run()
        com.android.server.uri.StockUriPermissionSmoke.run()
        StockAudioPolicySmoke.run()
        StockAudioTransportSmoke.run()
        StockAudioClientPrerequisitesSmoke.run()
        StockRingtoneShortcutSmoke.run(::drainMainQueue)
        StockHidlTransportSmoke.run()
        StockMediaMetricsSmoke.run()
        StockAudioManagerSmoke.run(::drainMainQueue)
        StockAudioTrackSmoke.run()
        StockMediaCodecSmoke.run()
        StockNdkMediaCodecSmoke.run()
        StockAAudioSmoke.run()
        StockAudioFoundationSmoke.run()
        StockVoiceTypesSmoke.run()
        StockMainlineTypesSmoke.run()
        StockCronetConfigSmoke.run()
        StockHttpEngineSmoke.run()
        StockMediaStoreSmoke.run()
        StockServiceRegistrySmoke.run()
        StockOverlaySmoke.run()
        StockCameraMetadataSmoke.run()
        StockCodecCatalogSmoke.run()
        StockRendererSmoke.run()
        StockSplashScreenSmoke.run()
        StockConnectivitySmoke.run(checkNotNull(permissionsFixtureApk), ::drainMainQueue)
        StockJobNetworkSmoke.run(checkNotNull(permissionsFixtureApk), ::drainMainQueue)
        StockNetworkValidationSmoke.run()
        StockWifiManagerSmoke.run()
        com.android.server.pm.StockApexManagerSmoke.run()
        com.android.server.pm.StockSharedUserSmoke.run()
        com.android.server.compat.StockCompatChangeSmoke.run()
        com.android.server.pm.StockPackageSettingsSmoke.run()
        testStockJankAndWifiModels()
        testStockApplicationSharedMemory()
        testStockMemoryIntArray()
        testStockCompanionModelsAndLocalSockets()
        testWindowRootRegistry()
        testWindowConfigurationCallbacks()
        testStockRuntimeInitialization()
        testStockMimeMapResources()
        testStockSystemConfigAndRuntimeInfo()
        testStockNativeLibraryExtraction()
        testStockStrictJarVerification()
        testStockInteractionJankMonitor()
        testStockMediaValueContracts()
        testStockInputMethodService()
        testStockMediaProjectionCallbacks()
        testHostedVirtualDeviceDefaults()
        testPictureInPictureContracts()
        testRemoteActionContract()
        testHostedTaskReuse()
        com.android.server.uri.StockUriPermissionSmoke.withService {
            testHostedActivityTaskServiceTransactions()
            StockAudioPolicyClientsSmoke.run()
            StockMediaRouterSmoke.run(::drainMainQueue)
            StockVirtualDeviceClientsSmoke.run(::drainMainQueue)
            StockSystemSensorSmoke.run(::drainMainQueue)
            testHostedAudioCapabilities()
            testAsyncMediaCodecFlushRestartsOnePump()
            testMediaMetricsContracts()
            StockBootTasksSmoke.run()
            testWebViewCallbackContracts()
            testAlertDialogContracts()
            testDialogLifecycleAndStateContracts()
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
            StockActivityFixture(checkNotNull(args.getOrNull(1)), ::drainMainQueue).use {
                testLocalActivityManagerLifecycleAndRecreation(it)
            }
            testActivityThreadResultDelivery()
            testInstrumentationResultTransactions()
            testActivityThreadTransactionCompletion()
            testActivityThreadTransactionCompletion(persistable = true)
            testActivityThreadConfigurationChange()
            testActivityThreadConfigurationChange(true)
            testActivityThreadRelaunch()
            // Pinned ActivityGroup overrides the caller-aware result signature.
            withContentUriPermissionApis(true) {
                StockActivityFixture(checkNotNull(args.getOrNull(1)), ::drainMainQueue).use {
                    testActivityGroupLifecycleStateAndResults(it)
                }
            }
            testLegacyFragmentTransactionsAndRecreation()
            StockActivityFixture(checkNotNull(args.getOrNull(1)), ::drainMainQueue).use {
                testLegacyFragmentHostedResultsAndRetention(it)
            }
            StockActivityFixture(checkNotNull(args.getOrNull(1)), ::drainMainQueue).use {
                testActivityLifecycle(it)
            }
        }
        testHostedPermissionGrants()
        println("hosted framework module smoke: PASS")
    }

    private fun testStockRuntimeInitialization() {
        val mime = libcore.content.type.MimeMap.getDefault()
        check(mime.guessMimeTypeFromExtension("apk") == "application/vnd.android.package-archive")
        check(mime.guessExtensionFromMimeType("image/jpeg") == "jpg")
        check(System.getProperty("http.agent").startsWith("Dalvik/"))
        val before = ArtzigTestDiagnostics.logSequence()
        val message = "runtime bootstrap logger ${System.nanoTime()}"
        java.util.logging.Logger.getLogger("RuntimeInitSmoke").info(message)
        check(ArtzigTestDiagnostics.snapshot(before, ArtzigTestDiagnostics.issueSequence()).contains(message))
        android.app.ActivityThread.currentActivityThread().stopProfiling()
    }

    private fun testStockMediaValueContracts() {
        fun <T : Parcelable> roundTrip(value: T, creator: Parcelable.Creator<T>): T {
            val parcel = android.os.Parcel.obtain()
            try {
                value.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                return creator.createFromParcel(parcel)
            } finally { parcel.recycle() }
        }
        val timestamp = android.media.AudioTimestamp().apply {
            framePosition = (1L shl 40) + 7
            nanoTime = 1234567890123L
        }
        val copy = roundTrip(timestamp, android.media.AudioTimestamp.CREATOR)
        check(copy.framePosition == timestamp.framePosition && copy.nanoTime == timestamp.nanoTime)
        val params = android.media.PlaybackParams()
        check(runCatching { params.speed }.exceptionOrNull() is IllegalStateException)
        check(runCatching { params.pitch }.exceptionOrNull() is IllegalStateException)
        check(runCatching { params.setPitch(-1f) }.exceptionOrNull() is IllegalArgumentException)
        params.setSpeed(1.5f)
        val partial = roundTrip(params, android.media.PlaybackParams.CREATOR)
        check(partial.speed == 1.5f)
        check(runCatching { partial.pitch }.exceptionOrNull() is IllegalStateException)
        check(partial.allowDefaults() === partial && partial.pitch == 1f && partial.speed == 1.5f)
        val format = android.media.MediaFormat.createAudioFormat("audio/raw", 48000, 2)
        format.setFeatureEnabled("test-feature", true)
        val keys = format.keys
        val features = format.features
        check("test-feature" in features && "feature-test-feature" !in keys)
        check(format.getFeatureEnabled("test-feature"))
        format.setLong("wide", 1L shl 40)
        check("wide" in keys && checkNotNull(format.getNumber("wide")).toLong() == (1L shl 40))
        check(runCatching { format.getInteger("wide", 0) }.exceptionOrNull() is ClassCastException)
        check(format.getInteger("missing", 42) == 42)
        check(runCatching { format.getInteger("missing") }.exceptionOrNull() is NullPointerException)
        val formatCopy = android.media.MediaFormat(format)
        keys.remove("wide")
        features.remove("test-feature")
        check(!format.containsKey("wide") && !format.containsFeature("test-feature"))
        check(formatCopy.getLong("wide") == (1L shl 40) && formatCopy.getFeatureEnabled("test-feature"))
    }

    private fun testStockInteractionJankMonitor() {
        val context = android.app.ActivityThread.currentActivityThread().systemContext
        val surface = android.view.SurfaceControl()
        check(!surface.isValid && surface.layerId == -1)
        val registration = surface.addOnJankDataListener { error("No compositor data") }
        registration.flush()
        registration.removeAfter(0)
        registration.release()
        registration.release()
        check(runCatching { android.view.SurfaceControl.Transaction.sendSurfaceFlushJankData(surface) }
            .exceptionOrNull() is NullPointerException)

        val cuj = com.android.internal.jank.Cuj.CUJ_LAUNCHER_APP_LAUNCH_FROM_ICON
        val invalidSurface = com.android.internal.jank.InteractionJankMonitor.Configuration.Builder
            .withSurface(cuj, context, surface, android.os.Handler(android.os.Looper.getMainLooper()))
        check(runCatching { invalidSurface.build() }.exceptionOrNull() is IllegalArgumentException)
        val view = View(context)
        val invalidView = com.android.internal.jank.InteractionJankMonitor.Configuration.Builder
            .withView(cuj, view).setTimeout(-1)
        val invalid = runCatching { invalidView.build() }.exceptionOrNull()
        check(invalid is IllegalArgumentException && invalid.message!!.contains("Invalid timeout"))
        val monitor = com.android.internal.jank.InteractionJankMonitor.getInstance()
        check(!monitor.begin(invalidSurface) && !monitor.isInstrumenting(cuj))
        check(!monitor.end(cuj) && !monitor.cancel(cuj))
        check(com.android.internal.jank.InteractionJankMonitor.Configuration
            .generateSessionName("SCROLL", "list") == "J<SCROLL::list>")
        check(com.android.internal.jank.DisplayRefreshRate.getRefreshRate(16_666_667L) ==
            com.android.internal.jank.DisplayRefreshRate.REFRESH_RATE_60_HZ)
        val root = ViewRootImpl(context)
        val events = arrayListOf<String>()
        val callback = object : ViewRootImpl.SurfaceChangedCallback {
            override fun surfaceCreated(transaction: android.view.SurfaceControl.Transaction) { error("No compositor surface") }
            override fun surfaceReplaced(transaction: android.view.SurfaceControl.Transaction) { error("No compositor surface") }
            override fun surfaceDestroyed() { error("No compositor surface") }
            override fun vriDrawStarted(isWmSync: Boolean) { check(!isWmSync); events.add("start") }
        }
        try {
            root.setView(view, 32, 32)
            root.addSurfaceChangedCallback(callback)
            view.viewTreeObserver.addOnDrawListener { events.add("draw") }
            root.doTraversal()
            check(events == listOf("start", "draw"))
            root.removeSurfaceChangedCallback(callback)
            events.clear()
            view.invalidate()
            root.doTraversal()
            check(events == listOf("draw"))
        } finally { root.removeSurfaceChangedCallback(callback); root.destroy() }
    }

    private fun testStockSystemConfigAndRuntimeInfo() {
        val wifi = android.content.pm.PackageManager.FEATURE_WIFI
        val camera = android.content.pm.PackageManager.FEATURE_CAMERA
        val wifiIndex = android.content.pm.PackageManager.maybeGetSdkFeatureIndex(wifi)
        val cameraIndex = android.content.pm.PackageManager.maybeGetSdkFeatureIndex(camera)
        check(wifiIndex in 0 until android.content.pm.PackageManager.SDK_FEATURE_COUNT)
        check(cameraIndex in 0 until android.content.pm.PackageManager.SDK_FEATURE_COUNT)
        check(wifiIndex != cameraIndex)
        val features = android.content.pm.SystemFeaturesCache(listOf(
            android.content.pm.FeatureInfo().apply { name = wifi; version = 4 },
            android.content.pm.FeatureInfo().apply { name = "dev.artzig.custom"; version = 7 },
        ))
        check(features.maybeHasFeature(wifi, 4) == true)
        check(features.maybeHasFeature(wifi, 5) == false)
        check(features.maybeHasFeature(camera, 0) == false)
        check(features.maybeHasFeature("dev.artzig.custom", 7) == null)
        val restoredFeatures = android.content.pm.SystemFeaturesCache(features.sdkFeatureVersions.copyOf())
        check(restoredFeatures.maybeHasFeature(wifi, 4) == true)
        check(restoredFeatures.maybeHasFeature(camera, 0) == false)
        check(com.android.internal.pm.RoSystemFeatures.getReadOnlySystemEnabledFeatures().isEmpty())
        check(com.android.internal.pm.RoSystemFeatures.maybeHasFeature(
            android.content.pm.PackageManager.FEATURE_PC, 0) == null)
        check(android.os.VintfObject.report().isEmpty())
        check(android.os.VintfObject.getHalNamesAndVersions().isEmpty())
        check(android.os.VintfObject.verifyBuildAtBoot() == -android.system.OsConstants.ENODEV)
        check(android.os.VintfObject.getSepolicyVersion() == null)
        check(android.os.VintfObject.getVndkSnapshots() == null)
        check(android.os.VintfObject.getTargetFrameworkCompatibilityMatrixVersion() == null)
        val missingMatrix = runCatching { android.os.VintfObject.getPlatformSepolicyVersion() }.exceptionOrNull()
        check(missingMatrix is RuntimeException && missingMatrix.message == "Cannot get framework compatibility matrix")
        val uname = android.system.Os.uname()
        check(android.os.VintfRuntimeInfo.getOsName() == uname.sysname)
        check(android.os.VintfRuntimeInfo.getNodeName() == uname.nodename)
        check(android.os.VintfRuntimeInfo.getOsRelease() == uname.release)
        check(android.os.VintfRuntimeInfo.getOsVersion() == uname.version)
        check(android.os.VintfRuntimeInfo.getHardwareId() == uname.machine)
        val version = checkNotNull(Regex("^(\\d+)\\.(\\d+)\\.(\\d+)").find(uname.release))
        check(android.os.VintfRuntimeInfo.getKernelVersion() == version.groupValues.drop(1).joinToString(".") { it.toLong().toString() })
        val cpuInfo = checkNotNull(android.os.VintfRuntimeInfo.getCpuInfo())
        check(cpuInfo.contains("processor") && cpuInfo == android.os.VintfRuntimeInfo.getCpuInfo())
        check(android.os.VintfRuntimeInfo.getBootAvbVersion() == "0.0")
        check(android.os.VintfRuntimeInfo.getBootVbmetaAvbVersion() == "0.0")
        val policyFile = java.io.File("/sys/fs/selinux/policyvers")
        check(android.os.VintfRuntimeInfo.getKernelSepolicyVersion() ==
            if (policyFile.canRead()) policyFile.readText().trim().toLong() else 0L)
        check(android.os.incremental.IncrementalManager.getVersion() == 0)
        val directory = java.nio.file.Files.createTempDirectory("artzig-system-config-").toFile()
        try {
            val library = java.io.File(directory, "shared.jar").apply { writeBytes(byteArrayOf()) }
            java.io.File(directory, "host-test.xml").writeText("""
                <permissions>
                    <feature name="dev.artzig.kept" version="4" />
                    <feature name="dev.artzig.kept" version="2" />
                    <hidden-api-whitelisted-app package="dev.artzig.allowed" />
                    <library name="dev.artzig.shared" file="${library.absolutePath}" />
                    <library name="dev.artzig.missing" file="${directory.absolutePath}/missing.jar" />
                </permissions>
            """.trimIndent())
            val config = com.android.server.SystemConfig(false)
            config.readPermissions(android.util.Xml.newPullParser(), directory, -1)
            check(config.availableFeatures["dev.artzig.kept"]?.version == 4)
            check("dev.artzig.allowed" in config.hiddenApiWhitelistedApps)
            check(config.sharedLibraries["dev.artzig.shared"]?.filename == library.absolutePath)
            check(!config.sharedLibraries.containsKey("dev.artzig.missing"))
        } finally { directory.deleteRecursively() }
    }

    private fun testStockNativeLibraryExtraction() {
        val directory = java.nio.file.Files.createTempDirectory("artzig-native-libraries-").toFile()
        try {
            val bytes = ByteArray(257) { (it * 31).toByte() }
            val apk = java.io.File(directory, "native.apk")
            java.util.zip.ZipOutputStream(apk.outputStream()).use { zip ->
                // Extraction checks archive entries and ABI names; it does not
                // execute the payload or require a loadable ELF in this mode.
                for ((name, payload) in listOf(
                    "lib/x86_64/libfixture.so" to bytes,
                    "lib/arm64-v8a/libfixture.so" to byteArrayOf(4, 5, 6),
                    "assets/ignored.so" to byteArrayOf(9),
                )) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(payload)
                    zip.closeEntry()
                }
            }
            val output = java.io.File(directory, "libraries")
            com.android.internal.content.NativeLibraryHelper.createNativeLibrarySubdir(output)
            com.android.internal.content.NativeLibraryHelper.createNativeLibrarySubdir(output)
            com.android.internal.content.NativeLibraryHelper.Handle.create(
                listOf(apk.absolutePath), false, true, false, false,
            ).use { handle ->
                check(com.android.internal.content.NativeLibraryHelper.findSupportedAbi(
                    handle, arrayOf("mips", "x86_64", "arm64-v8a")) == 1)
                check(com.android.internal.content.NativeLibraryHelper.findSupportedAbi(
                    handle, arrayOf("mips")) == android.content.pm.PackageManager.INSTALL_FAILED_NO_MATCHING_ABIS)
                check(com.android.internal.content.NativeLibraryHelper.sumNativeBinariesWithOverride(
                    handle, "x86_64") == bytes.size.toLong())
                check(com.android.internal.content.NativeLibraryHelper.copyNativeBinaries(
                    handle, output, "x86_64") == android.content.pm.PackageManager.INSTALL_SUCCEEDED)
                val library = java.io.File(output, "libfixture.so")
                check(library.readBytes().contentEquals(bytes) && library.canExecute())
                check(output.listFiles()!!.size == 1)
                library.writeBytes(byteArrayOf(0))
                check(com.android.internal.content.NativeLibraryHelper.copyNativeBinaries(
                    handle, output, "x86_64") == android.content.pm.PackageManager.INSTALL_SUCCEEDED)
                check(library.readBytes().contentEquals(bytes))
            }
            check(runCatching {
                com.android.internal.content.NativeLibraryHelper.Handle.create(
                    listOf(apk.absolutePath, java.io.File(directory, "missing.apk").absolutePath),
                    false, true, false, false,
                ).close()
            }.exceptionOrNull() is java.io.IOException)
            val javaApk = java.io.File(directory, "java-only.apk")
            java.util.zip.ZipOutputStream(javaApk.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("assets/data"))
                zip.write(1)
                zip.closeEntry()
            }
            com.android.internal.content.NativeLibraryHelper.Handle.create(
                listOf(javaApk.absolutePath), false, true, false, false,
            ).use { handle ->
                check(com.android.internal.content.NativeLibraryHelper.copyNativeBinariesForSupportedAbi(
                    handle, output, arrayOf("x86_64"), false, false,
                ) == android.content.pm.PackageManager.NO_NATIVE_LIBRARIES)
            }
            check(!android.os.incremental.IncrementalManager.isIncrementalPath(apk.absolutePath))
            check(android.os.incremental.IncrementalManager.unsafeGetFileSignature(apk.absolutePath) == null)
            android.os.ParcelFileDescriptor.open(apk, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use {
                check(!android.os.incremental.IncrementalManager.isIncrementalFileFd(it.fileDescriptor))
            }
            if (!android.os.SELinux.isSELinuxEnabled()) {
                check(android.os.SELinux.getContext() == null)
                check(android.os.SELinux.restorecon(output))
                check(android.os.SELinux.checkSELinuxAccess(null, null, null, null))
            }
            check(runCatching { android.os.SELinux.restorecon(null as String?) }
                .exceptionOrNull() is NullPointerException)
            com.android.internal.content.NativeLibraryHelper.removeNativeBinariesFromDirLI(output, true)
            check(!output.exists())
        } finally { directory.deleteRecursively() }
    }

    private fun testStockStrictJarVerification() {
        val fixture = checkNotNull(permissionsFixtureApk)
        val jar = android.util.jar.StrictJarFile(fixture, true, false)
        val originalManifest: ByteArray
        try {
            val entry = checkNotNull(jar.findEntry("AndroidManifest.xml"))
            check(jar.findEntry("missing-entry") == null)
            check(jar.iterator().asSequence().any { it.name == entry.name })
            check(jar.getCertificateChains(entry) == null)
            originalManifest = jar.getInputStream(entry).use { it.readBytes() }
            check(originalManifest.isNotEmpty())
            val chains = checkNotNull(jar.getCertificateChains(entry))
            check(chains.isNotEmpty() && chains[0].isNotEmpty())
            check(chains[0][0].publicKey.algorithm == "RSA")
        } finally { jar.close() }
        jar.close() // AOSP close is idempotent.

        val directory = java.nio.file.Files.createTempDirectory("artzig-strict-jar-").toFile()
        try {
            fun rewrite(name: String, tamper: Boolean): File {
                val result = File(directory, name)
                java.util.zip.ZipFile(fixture).use { source ->
                    java.util.zip.ZipOutputStream(result.outputStream()).use { target ->
                        val entries = source.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val bytes = source.getInputStream(entry).use { it.readBytes() }
                            if (tamper && entry.name == "AndroidManifest.xml") {
                                bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
                            }
                            target.putNextEntry(java.util.zip.ZipEntry(entry.name))
                            target.write(bytes)
                            target.closeEntry()
                        }
                    }
                }
                return result
            }
            // Repacking preserves v1-signed entries but removes the APK signing
            // block. The signed .SF marker must prevent fallback to v1.
            val stripped = rewrite("stripped.apk", false)
            val rollback = runCatching {
                android.util.jar.StrictJarFile(stripped.absolutePath, true, true).close()
            }.exceptionOrNull()
            check(rollback is SecurityException && rollback.message!!.contains("Signature stripped"))
            val legacy = android.util.jar.StrictJarFile(stripped.absolutePath, true, false)
            try {
                val entry = checkNotNull(legacy.findEntry("AndroidManifest.xml"))
                check(legacy.getInputStream(entry).use { it.readBytes() }.contentEquals(originalManifest))
                check(legacy.getCertificateChains(entry) != null)
            } finally { legacy.close() }

            val tampered = rewrite("tampered.apk", true)
            val invalid = android.util.jar.StrictJarFile(tampered.absolutePath, true, false)
            try {
                check(runCatching {
                    invalid.getInputStream(checkNotNull(invalid.findEntry("AndroidManifest.xml")))
                        .use { it.readBytes() }
                }.exceptionOrNull() is SecurityException)
            } finally { invalid.close() }

            val fd = android.system.Os.open(fixture, android.system.OsConstants.O_RDONLY, 0)
            val fdJar = android.util.jar.StrictJarFile(fd, false, false)
            try {
                val entry = checkNotNull(fdJar.findEntry("AndroidManifest.xml"))
                check(fdJar.getInputStream(entry).use { it.readBytes() }.contentEquals(originalManifest))
                check(fdJar.getCertificateChains(entry) == null)
            } finally { fdJar.close() }
            val closed = runCatching { android.system.Os.fstat(fd) }.exceptionOrNull()
            check(closed is android.system.ErrnoException && closed.errno == android.system.OsConstants.EBADF)
        } finally { directory.deleteRecursively() }
    }

    private fun testStockMimeMapResources() {
        val map = android.content.type.DefaultMimeMapFactory.create()
        check(map.guessMimeTypeFromExtension("apk") == "application/vnd.android.package-archive")
        check(map.guessMimeTypeFromExtension("flac") == "audio/flac")
        check(map.guessMimeTypeFromExtension("JPG") == "image/jpeg")
        // Android's overrides follow the Debian table (jpeg and asc there).
        check(map.guessExtensionFromMimeType("image/jpeg") == "jpg")
        check(map.guessExtensionFromMimeType("text/plain") == "txt")
        check(map.guessMimeTypeFromExtension("artzig-unknown-extension") == null)
        val tables = mapOf(
            "debian.mime.types" to "text/plain txt text\n",
            "android.mime.types" to "text/plain log\n",
            "vendor.mime.types" to "?text/alternative ?txt\n?text/plain ?vendor\n",
        )
        val custom = android.content.type.DefaultMimeMapFactory.create { name ->
            tables.getValue(name).byteInputStream()
        }
        check(custom.guessMimeTypeFromExtension("txt") == "text/plain")
        check(custom.guessExtensionFromMimeType("text/plain") == "log")
        check(custom.guessMimeTypeFromExtension("vendor") == "text/plain")
        val malformed = runCatching {
            android.content.type.DefaultMimeMapFactory.create { "text/plain  txt\n".byteInputStream() }
        }.exceptionOrNull()
        check(malformed is RuntimeException && malformed.cause is IllegalArgumentException)
    }

    private fun testWindowRootRegistry() {
        val context = android.app.ActivityThread.currentActivityThread().systemContext
        val global = android.view.WindowManagerGlobal.getInstance()
        val before = global.windowViews
        val changes = ArrayList<List<View>>()
        val listener = java.util.function.Consumer<List<View>> { changes.add(ArrayList(it)) }
        global.addWindowViewsListener(java.util.concurrent.Executor { it.run() }, listener)
        global.addWindowViewsListener(java.util.concurrent.Executor { it.run() }, listener)
        check(changes.size == 1 && changes.single() == before)
        val token = android.os.Binder()
        val parent = View(context)
        parent.layoutParams = android.view.WindowManager.LayoutParams().apply {
            this.token = token
            title = "Registry parent"
        }
        val parentRoot = HostedWindowRoots.attach(parent, 100, 100)
        HostedWindowStack.setActivityRoot(parentRoot)
        val manager = WindowManagerImpl(context)
        var childRoot: ViewRootImpl? = null
        try {
            val child = View(context)
            child.layoutParams = android.view.WindowManager.LayoutParams().apply {
                this.token = parent.windowToken
                type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
                title = "Registry child"
            }
            manager.addView(child, child.layoutParams)
            childRoot = checkNotNull(child.viewRootImpl).also { it.setSize(100, 100) }
            val overlay = checkNotNull(HostedWindowStack.topFocusable())
            check(overlay.view === child && overlay.root === childRoot)
            check(HostedWindowStack.entries().none { it.view === parent })
            overlay.frame.set(4, 5, 64, 65)
            val overlayUpdate = WindowManager.LayoutParams().apply {
                copyFrom(child.layoutParams as WindowManager.LayoutParams)
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                width = 60
            }
            manager.updateViewLayout(child, overlayUpdate)
            check(HostedWindowStack.topFocusable()?.view !== child)
            check(HostedWindowStack.topTouchable() === overlay)
            check(overlay.lp === overlayUpdate && overlay.frame == Rect(4, 5, 64, 65))
            overlayUpdate.flags = overlayUpdate.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            manager.updateViewLayout(child, overlayUpdate)
            check(HostedWindowStack.topFocusable() === overlay)
            check(changes.last() == before + listOf(parent, child))
            check(global.getWindowView(parent.windowToken) === parent)
            check(global.getRootViews(token) == listOf(parentRoot, childRoot))
            check(global.getRootViews(android.os.Binder()).isEmpty())
            val name = "Registry parent/android.view.ViewRootImpl@${Integer.toHexString(parentRoot.hashCode())}"
            check(name in global.viewRootNames && global.getRootView(name) === parent)
            check(global.getRootView("unknown") == null)
            global.windowViews.clear()
            check(global.windowViews.contains(parent))
            val oldAttributes = parentRoot.mWindowAttributes
            oldAttributes.systemUiVisibility = 0x100
            oldAttributes.subtreeSystemUiVisibility = 0x200
            oldAttributes.insetsFlags.appearance = 8
            oldAttributes.insetsFlags.behavior = 2
            oldAttributes.surfaceInsets.set(1, 2, 3, 4)
            oldAttributes.hasManualSurfaceInsets = true
            oldAttributes.softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            val changed = android.view.WindowManager.LayoutParams().apply {
                copyFrom(parent.layoutParams as android.view.WindowManager.LayoutParams)
                width = android.view.WindowManager.LayoutParams.MATCH_PARENT
                height = android.view.WindowManager.LayoutParams.MATCH_PARENT
                privateFlags = privateFlags or android.view.WindowManager.LayoutParams.PRIVATE_FLAG_EDGE_TO_EDGE_ENFORCED
                setFitInsetsTypes(0)
                setFitInsetsSides(0)
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                preservePreviousSurfaceInsets = true
            }
            global.updateViewLayout(parent, changed)
            check(parent.layoutParams === changed)
            check(oldAttributes.systemUiVisibility == 0x100 && oldAttributes.subtreeSystemUiVisibility == 0x200)
            check(oldAttributes.insetsFlags.appearance == 8 && oldAttributes.insetsFlags.behavior == 2)
            check(oldAttributes.surfaceInsets == Rect(1, 2, 3, 4) && oldAttributes.hasManualSurfaceInsets)
            check(oldAttributes.softInputMode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST ==
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            check(oldAttributes.layoutInDisplayCutoutMode == android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS)
            check(changed.layoutInDisplayCutoutMode == android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER)
            val sibling = View(context).apply {
                layoutParams = android.view.WindowManager.LayoutParams().apply { this.token = Binder() }
            }
            val siblingRoot = HostedWindowRoots.attach(sibling, 100, 100)
            try {
                parentRoot.doTraversal()
                childRoot.doTraversal()
                siblingRoot.doTraversal()
                check(parentRoot.`getRenderNode$artzig`() != null)
                check(childRoot.`getRenderNode$artzig`() != null)
                global.setStoppedState(token, true)
                check(parentRoot.`getRenderNode$artzig`() == null)
                check(childRoot.`getRenderNode$artzig`() == null)
                check(siblingRoot.`getRenderNode$artzig`() != null)
                check(parent.isAttachedToWindow && child.isAttachedToWindow)
                global.setStoppedState(token, false)
                parentRoot.doTraversal()
                childRoot.doTraversal()
                check(parentRoot.`getRenderNode$artzig`() != null)
                check(childRoot.`getRenderNode$artzig`() != null)
            } finally { siblingRoot.destroy() }
            val closed = ArrayList<String>()
            val leakedViews = (1..2).map { index ->
                object : View(context) {
                    override fun onDetachedFromWindow() {
                        closed.add("detach-$index")
                        super.onDetachedFromWindow()
                    }
                }.apply {
                    layoutParams = android.view.WindowManager.LayoutParams().apply { this.token = token }
                    HostedWindowRoots.attach(this, 20, 20).doTraversal()
                }
            }
            global.closeAllExceptView(token, parent, null, null)
            check(closed.isEmpty()) { "closeAll must defer destruction while iterating roots" }
            check(leakedViews.all { global.windowViews.contains(it) && it.parent == null })
            runHostedFrame()
            check(closed == listOf("detach-1", "detach-2")) { "deferred removals: $closed" }
            check(leakedViews.none { global.windowViews.contains(it) || it.isAttachedToWindow })
            check(global.windowViews.contains(parent) && global.windowViews.contains(child))
            manager.removeViewImmediate(child)
            check(HostedWindowStack.entries().none { it.view === child })
            check(changes.last() == before + parent)
            check(!global.windowViews.contains(child))
            parentRoot.destroy()
            check(changes.last() == before && global.windowViews == before)
        } finally {
            childRoot?.destroy()
            parentRoot.destroy()
            global.removeWindowViewsListener(listener)
        }
    }

    private fun testWindowConfigurationCallbacks() {
        val base = HostContexts.systemContext(System.getenv("ARTZIG_FRAMEWORK_RES"), 100, 100, 120, 0)
        val configurationContext = base.createConfigurationContext(Configuration())
        val context = object : ContextWrapper(configurationContext) {
            override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo(super.getApplicationInfo()).apply {
                flags = flags or ApplicationInfo.FLAG_SUPPORTS_RTL
                targetSdkVersion = android.os.Build.VERSION_CODES.CUR_DEVELOPMENT
            }
        }
        val resources = context.resources
        val original = Configuration(resources.configuration)
        val initial = Configuration(original).apply { seq = 100 }
        resources.updateConfiguration(initial, resources.displayMetrics)
        val events = ArrayList<String>()
        val view = object : View(context) {
            override fun onConfigurationChanged(config: Configuration) {
                events.add("view:${config.seq}")
                super.onConfigurationChanged(config)
            }
        }
        view.layoutDirection = View.LAYOUT_DIRECTION_INHERIT
        val root = HostedWindowRoots.attach(view, 100, 100)
        val first = ViewRootImpl.ConfigChangedCallback { events.add("first:${it.seq}") }
        val second = ViewRootImpl.ConfigChangedCallback { events.add("second:${it.seq}") }
        ViewRootImpl.addConfigCallback(first)
        ViewRootImpl.addConfigCallback(second)
        root.setActivityConfigCallback(object : ViewRootImpl.ActivityConfigCallback {
            override fun onConfigurationChanged(overrideConfig: Configuration, displayId: Int) {
                check(displayId == android.view.Display.INVALID_DISPLAY)
                events.add("activity")
                root.updateConfiguration(displayId)
            }
        })
        try {
            root.doTraversal()
            for ((sequence, locale, direction) in listOf(
                Triple(200, java.util.Locale.forLanguageTag("ar"), View.LAYOUT_DIRECTION_RTL),
                Triple(300, java.util.Locale.US, View.LAYOUT_DIRECTION_LTR))) {
                val updated = Configuration(initial).apply {
                    seq = sequence
                    setLocales(android.os.LocaleList(locale))
                }
                resources.updateConfiguration(updated, resources.displayMetrics)
                events.clear()
                android.view.WindowManagerGlobal.getInstance().reportNewConfiguration(updated)
                updated.seq = 999 // Stock global registry must queue its own copy.
                check(events.isEmpty())
                runHostedFrame()
                check(events == listOf("second:$sequence", "first:$sequence", "activity", "view:$sequence")) {
                    "configuration callback order: $events"
                }
                check(view.layoutDirection == direction) {
                    "layout direction: actual=${view.layoutDirection}, expected=$direction, raw=${view.rawLayoutDirection}"
                }
            }
            events.clear()
            root.requestUpdateConfiguration(initial)
            runHostedFrame()
            check(events == listOf("second:300", "first:300", "activity")) { events.toString() }
        } finally {
            ViewRootImpl.removeConfigCallback(first)
            ViewRootImpl.removeConfigCallback(second)
            root.destroy()
            resources.updateConfiguration(original, resources.displayMetrics)
        }
    }

    private fun testStockCompanionModelsAndLocalSockets() {
        fun device(address: String, type: Int): android.bluetooth.BluetoothDevice {
            val parcel = android.os.Parcel.obtain()
            try {
                parcel.writeString(address)
                parcel.writeInt(type)
                parcel.setDataPosition(0)
                return android.bluetooth.BluetoothDevice.CREATOR.createFromParcel(parcel)
            } finally { parcel.recycle() }
        }
        val bluetoothDevice = device("00:11:22:33:AA:BB", android.bluetooth.BluetoothDevice.ADDRESS_TYPE_RANDOM)
        check(bluetoothDevice.addressType == android.bluetooth.BluetoothDevice.ADDRESS_TYPE_RANDOM)
        check(bluetoothDevice.anonymizedAddress == "XX:XX:XX:XX:AA:BB")
        check(runCatching { device("00:11:22:33:aa:bb", 0) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { device("00:11:22:33:AA:BB", 9) }.exceptionOrNull() is IllegalArgumentException)
        val request = android.companion.AssociationRequest.Builder()
            .setSelfManaged(true).setDisplayName("Hosted companion").build()
        val parcel = android.os.Parcel.obtain()
        try {
            request.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val copy = android.companion.AssociationRequest.CREATOR.createFromParcel(parcel)
            check(copy.isSelfManaged && copy.displayName == "Hosted companion" && copy.deviceFilters.isEmpty())
        } finally { parcel.recycle() }
        check(runCatching { android.companion.AssociationRequest.Builder().setSelfManaged(true).build() }
            .exceptionOrNull() is IllegalStateException)
        val transport = java.util.concurrent.FutureTask { testLocalSocketTransport() }
        Thread(transport, "companion-local-socket-smoke").start()
        transport.get(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun testLocalSocketTransport() {
        val name = "artzig-local-socket-${android.os.Process.myPid()}-${System.nanoTime()}"
        android.net.LocalServerSocket(name).use { server ->
            android.net.LocalSocket().use { client ->
                client.connect(android.net.LocalSocketAddress(name))
                server.accept().use { peer ->
                    check(peer.peerCredentials.pid == android.os.Process.myPid())
                    check(peer.peerCredentials.uid == android.system.Os.getuid())
                    client.outputStream.write(byteArrayOf(3, 4, 5))
                    val bytes = ByteArray(3)
                    java.io.DataInputStream(peer.inputStream).readFully(bytes)
                    check(bytes.contentEquals(byteArrayOf(3, 4, 5)))
                    val pipe = android.os.ParcelFileDescriptor.createPipe()
                    try {
                        client.setFileDescriptorsForSend(arrayOf(pipe[0].fileDescriptor))
                        client.outputStream.write(7)
                        client.setFileDescriptorsForSend(null)
                        check(peer.inputStream.read() == 7)
                        val received = peer.ancillaryFileDescriptors
                        check(received != null && received.size == 1)
                        try {
                            android.system.Os.write(pipe[1].fileDescriptor, byteArrayOf(42), 0, 1)
                            val data = ByteArray(1)
                            check(android.system.Os.read(received[0], data, 0, 1) == 1 && data[0] == 42.toByte())
                        } finally { android.system.Os.close(received[0]) }
                        check(peer.ancillaryFileDescriptors == null)
                    } finally { pipe.forEach { it.close() } }
                    client.shutdownOutput()
                    check(peer.inputStream.read() == -1)
                }
            }
        }
    }

    private fun testStockMemoryIntArray() {
        check(android.util.MemoryIntArray.getMaxSize() == 1024)
        check(runCatching { android.util.MemoryIntArray(1025) }.exceptionOrNull() is IllegalArgumentException)
        for (size in intArrayOf(0, -1)) {
            check(runCatching { android.util.MemoryIntArray(size) }.exceptionOrNull() is java.io.IOException)
        }
        val writer = android.util.MemoryIntArray(3)
        var reader: android.util.MemoryIntArray? = null
        try {
            check(writer.isWritable && writer.size() == 3)
            check(writer.describeContents() == Parcelable.CONTENTS_FILE_DESCRIPTOR)
            check((0..2).all { writer.get(it) == 0 })
            writer.set(0, Int.MIN_VALUE)
            writer.set(2, Int.MAX_VALUE)
            for (index in intArrayOf(-1, 3)) {
                check(runCatching { writer.get(index) }.exceptionOrNull() is IndexOutOfBoundsException)
                check(runCatching { writer.set(index, 1) }.exceptionOrNull() is IndexOutOfBoundsException)
            }
            val parcel = Parcel.obtain()
            try {
                writer.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                // Inspect the exported capability, without touching private JNI
                // state: even a recipient bypassing Java cannot acquire writes.
                parcel.readParcelable(null, ParcelFileDescriptor::class.java)!!.use { exported ->
                    val fd = exported.fileDescriptor
                    check(android.system.Os.fstat(fd).st_size == 12L)
                    check(runCatching { android.system.Os.ftruncate(fd, 4) }.exceptionOrNull()
                        is android.system.ErrnoException)
                    check(runCatching { android.system.Os.write(fd, byteArrayOf(1), 0, 1) }.exceptionOrNull()
                        is android.system.ErrnoException)
                    val mapping = runCatching {
                        android.system.Os.mmap(0, 12, android.system.OsConstants.PROT_READ or
                            android.system.OsConstants.PROT_WRITE, android.system.OsConstants.MAP_SHARED, fd, 0)
                    }
                    mapping.getOrNull()?.let { android.system.Os.munmap(it, 12) }
                    check(mapping.exceptionOrNull() is android.system.ErrnoException)
                }
                parcel.setDataPosition(0)
                reader = android.util.MemoryIntArray.CREATOR.createFromParcel(parcel)
            } finally {
                parcel.recycle()
            }
            val recipient = checkNotNull(reader)
            check(!recipient.isWritable && recipient.size() == 3)
            check(recipient.get(0) == Int.MIN_VALUE && recipient.get(2) == Int.MAX_VALUE)
            check(runCatching { recipient.set(1, 1) }.exceptionOrNull() is UnsupportedOperationException)
            writer.set(1, 123456)
            check(recipient.get(1) == 123456)
            writer.close()
            check(writer.isClosed)
            check(recipient.get(1) == 123456)
            check(runCatching { writer.get(0) }.exceptionOrNull() is IllegalStateException)
            check(runCatching { writer.size() }.exceptionOrNull() is IllegalStateException)
            check(runCatching { writer.isWritable }.exceptionOrNull() is IllegalStateException)
        } finally {
            reader?.close()
            writer.close()
        }
        check(reader!!.isClosed)
        val invalidParcel = Parcel.obtain()
        try {
            invalidParcel.writeParcelable(null, 0)
            invalidParcel.setDataPosition(0)
            check(runCatching { android.util.MemoryIntArray.CREATOR.createFromParcel(invalidParcel) }
                .exceptionOrNull() is IllegalArgumentException)
        } finally {
            invalidParcel.recycle()
        }
    }

    private fun testStockApplicationSharedMemory() {
        val writer = com.android.internal.os.ApplicationSharedMemory.create()
        var reader: com.android.internal.os.ApplicationSharedMemory? = null
        try {
            check(writer.isMapped && writer.isMutable && writer.systemNonceBlock != 0L)
            check(writer.readSystemFeaturesCache().isEmpty())
            check(runCatching { writer.latestNetworkTimeUnixEpochMillisAtZeroElapsedRealtimeMillis }
                .exceptionOrNull() is java.time.DateTimeException)
            check(runCatching { writer.writeSystemFeaturesCache(IntArray(513)) }.exceptionOrNull()
                is IllegalArgumentException)
            val descriptor = writer.fileDescriptor
            val size = android.system.Os.fstat(descriptor).st_size
            check(runCatching { android.system.Os.ftruncate(descriptor, size + 1) }.exceptionOrNull()
                is android.system.ErrnoException)
            reader = com.android.internal.os.ApplicationSharedMemory.fromFileDescriptor(writer.readOnlyFileDescriptor, false)
            check(reader.isMapped && !reader.isMutable)
            check(runCatching { android.system.Os.write(reader.fileDescriptor, byteArrayOf(1), 0, 1) }.exceptionOrNull()
                is android.system.ErrnoException)
            // Protection applies to all future writes, including the original
            // descriptor; the already-established mutable mapping remains live.
            check(runCatching { android.system.Os.write(descriptor, byteArrayOf(1), 0, 1) }.exceptionOrNull()
                is android.system.ErrnoException)
            val sealedDuplicate = writer.readOnlyFileDescriptor
            check(runCatching {
                com.android.internal.os.ApplicationSharedMemory.fromFileDescriptor(sealedDuplicate, true)
            }.exceptionOrNull() is RuntimeException)
            check(runCatching { android.system.Os.fstat(sealedDuplicate) }.exceptionOrNull()
                is android.system.ErrnoException) // AOSP nativeMap closes the transferred FD on failure.
            writer.writeSystemFeaturesCache(intArrayOf(3, 8, -1))
            check(reader.readSystemFeaturesCache().contentEquals(intArrayOf(3, 8, -1)))
            val features = reader.readSystemFeaturesCache()
            features[0] = 99
            check(reader.readSystemFeaturesCache()[0] == 3)
            check(runCatching { writer.writeSystemFeaturesCache(intArrayOf(4)) }.exceptionOrNull() is IllegalStateException)
            check(runCatching { reader.writeSystemFeaturesCache(intArrayOf(4)) }.exceptionOrNull() is IllegalStateException)
            writer.setLatestNetworkTimeUnixEpochMillisAtZeroElapsedRealtimeMillis(123456)
            check(reader.latestNetworkTimeUnixEpochMillisAtZeroElapsedRealtimeMillis == 123456L)
            writer.closeFileDescriptor()
            check(!descriptor.valid())
            check(runCatching { writer.readOnlyFileDescriptor }.exceptionOrNull() is IllegalStateException)
            writer.setLatestNetworkTimeUnixEpochMillisAtZeroElapsedRealtimeMillis(654321)
            check(reader.latestNetworkTimeUnixEpochMillisAtZeroElapsedRealtimeMillis == 654321L)
            writer.clearLatestNetworkTimeUnixEpochMillisAtZeroElapsedRealtimeMillis()
            check(runCatching { reader.latestNetworkTimeUnixEpochMillisAtZeroElapsedRealtimeMillis }
                .exceptionOrNull() is java.time.DateTimeException)
            writer.close()
            check(!writer.isMapped && !writer.isMutable && writer.systemNonceBlock == 0L)
            check(reader.readSystemFeaturesCache().contentEquals(intArrayOf(3, 8, -1)))
        } finally {
            reader?.close()
            writer.close()
        }
    }

    private fun testStockJankAndWifiModels() {
        val states = android.app.jank.StateTracker(android.view.Choreographer.getInstance())
        val category = android.app.jank.AppJankStats.WIDGET_CATEGORY_SCROLL
        val state = android.app.jank.AppJankStats.WIDGET_STATE_SCROLLING
        states.putState(category, "list", state)
        states.putState(category, "list", state)
        val tracked = arrayListOf<android.app.jank.StateTracker.StateData>()
        states.retrieveAllStates(tracked)
        check(tracked.size == 1)
        val first = tracked.single().mVsyncIdStart
        val processor = android.app.jank.JankDataProcessor(states)
        processor.processJankData(listOf(
            android.view.SurfaceControl.JankData(first, android.view.SurfaceControl.JankData.JANK_APPLICATION,
                16_666_667, 16_666_667, 20_000_000),
            android.view.SurfaceControl.JankData(first + 1, android.view.SurfaceControl.JankData.JANK_COMPOSER,
                16_666_667, 16_666_667, 10_000_000),
        ), "Activity", 10001)
        val stat = checkNotNull(processor.pendingJankStats[states.getStateKey(category, "list", state)])
        check(stat.totalFrames == 2L && stat.jankyFrames == 1L)
        check(stat.uid == 10001 && stat.activityName == "Activity")
        check(stat.frameOverrunBuckets.sum() == 2)
        // The stock AttachedSurfaceControl fallback reports no compositor jank
        // subscription. App-state aggregation does not fabricate frame samples.
        val root = android.view.ViewRootImpl(android.app.ActivityThread.currentActivityThread().systemContext)
        check(root.choreographer === android.view.Choreographer.getInstance())
        check(root.registerOnJankDataListener(java.util.concurrent.Executor { it.run() }) { error("No compositor subscription") }
            === android.view.SurfaceControl.OnJankDataListenerRegistration.NONE)

        val configuration = android.net.wifi.SoftApConfiguration.Builder()
            .setSsid("fixture-ap").setBand(android.net.wifi.SoftApConfiguration.BAND_2GHZ)
            .setPassphrase("password123", android.net.wifi.SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
            .build()
        val parcel = Parcel.obtain()
        try {
            configuration.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val copy = android.net.wifi.SoftApConfiguration.CREATOR.createFromParcel(parcel)
            check(copy == configuration && copy.passphrase == "password123")
            check(parcel.dataAvail() == 0)
        } finally { parcel.recycle() }
        check(runCatching { android.net.wifi.SoftApConfiguration.Builder().setSsid("") }.exceptionOrNull()
            is IllegalArgumentException)
        check(android.os.PowerExemptionManager.getExemptionReasonForStatsd(android.os.PowerExemptionManager.REASON_SYSTEM_UID)
            == android.os.AppBackgroundRestrictionsInfo.REASON_SYSTEM_UID)
    }

    private fun testStockNetworkScoring() {
        val key = android.net.NetworkKey(android.net.WifiKey("\"network\"", "02:00:00:00:00:01"))
        val curve = android.net.RssiCurve(-100, 10, byteArrayOf(-10, 0, 10), 10)
        check(curve.lookupScore(-120).toInt() == -10)
        check(curve.lookupScore(-95, true).toInt() == 0)
        check(curve.lookupScore(-50).toInt() == 10)
        val attributes = Bundle().apply {
            putInt(android.net.ScoredNetwork.ATTRIBUTES_KEY_RANKING_SCORE_OFFSET, 7)
        }
        val scored = android.net.ScoredNetwork(key, curve, true, attributes)
        check(scored.calculateRankingScore(-80) == 2567)
        val parcel = Parcel.obtain()
        try {
            scored.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val copy = android.net.ScoredNetwork.CREATOR.createFromParcel(parcel)
            check(copy == scored && copy.meteredHint)
            check(parcel.dataAvail() == 0)
        } finally { parcel.recycle() }
        check(runCatching { android.net.WifiKey("unquoted", "invalid") }.exceptionOrNull()
            is IllegalArgumentException)

        // Exercise the real service with a local permission-granted Context. APK
        // callers' denied Binder requests are covered separately by system-test.
        val context = object : ContextWrapper(android.app.ActivityThread.currentActivityThread().systemContext) {
            override fun checkCallingOrSelfPermission(permission: String) = PackageManager.PERMISSION_GRANTED
            override fun enforceCallingOrSelfPermission(permission: String, message: String?) {}
        }
        val legacyClass = com.android.server.pm.permission.LegacyPermissionManagerInternal::class.java
        val ownsLegacy = com.android.server.LocalServices.getService(legacyClass) == null
        if (ownsLegacy) com.android.server.LocalServices.addService(legacyClass,
            dev.artzig.system.HostedLegacyPermissionManagerInternal(HostedPackageManagerService()))
        try {
            val service = com.android.server.NetworkScoreService(context)
            var invalidations = 0
            val cache = object : android.net.INetworkScoreCache.Stub() {
                override fun updateScores(scores: MutableList<android.net.ScoredNetwork>?) {
                    error("No active scorer may update the cache")
                }
                override fun clearScores() { invalidations++ }
            }
            service.registerNetworkScoreCache(android.net.NetworkKey.TYPE_WIFI, cache, 0)
            service.registerNetworkScoreCache(android.net.NetworkKey.TYPE_WIFI, cache, 0)
            check(service.clearScores() && invalidations == 1)
            service.disableScoring()
            check(invalidations == 1) // AOSP disableScoring currently only enforces permission.
            service.unregisterNetworkScoreCache(android.net.NetworkKey.TYPE_WIFI, cache)
            check(service.clearScores() && invalidations == 1)
            check(!service.isCallerActiveScorer(android.os.Process.myUid()))
            check(!service.requestScores(arrayOf(key)))
            check(runCatching { service.updateScores(arrayOf(scored)) }.exceptionOrNull() is SecurityException)
            check(!service.setActiveScorer("dev.artzig.nonexistent.scorer"))
            check(service.allValidScorers.isEmpty())
        } finally {
            if (ownsLegacy) com.android.server.LocalServices.removeServiceForTest(legacyClass)
        }
    }

    private fun testStockActivityManagerAndBackup() {
        val context: Context = android.app.ActivityThread.currentActivityThread().systemContext
        val manager = context.getSystemService(android.app.ActivityManager::class.java)
        check(android.app.ActivityManager.getCurrentUser() == 0)
        check(!android.app.ActivityManager.isUserAMonkey())
        // LocalServices belongs to system_server; exercise the generated stock adapter
        // with a separate in-memory service so these contracts do not depend on IPC identity.
        val packages = HostedPackageManagerService()
        val local = dev.artzig.system.HostActivityManagerInternalAccess(
            HostedActivityManagerService(), packages, dev.artzig.system.HostedUserLifecycle(),
        )
        val internal = Class.forName("android.app.HostedActivityManagerInternal")
            .getConstructor(dev.artzig.system.HostActivityManagerInternalAccess::class.java)
            .newInstance(local) as android.app.ActivityManagerInternal
        check(!internal.isSystemReady)
        local.setSystemReady()
        check(internal.isSystemReady)
        check(internal.getUidProcessState(12345) == ActivityManager.PROCESS_STATE_NONEXISTENT)
        internal.addPendingTopUid(12345, 100, null)
        check(internal.isPendingTopUid(12345))
        check(internal.getUidProcessState(12345) == ActivityManager.PROCESS_STATE_TOP)
        internal.deletePendingTopUid(12345, android.os.SystemClock.elapsedRealtime())
        check(!internal.isPendingTopUid(12345))
        check(internal.getUidProcessState(12345) == ActivityManager.PROCESS_STATE_NONEXISTENT)
        check(internal.getActivityInfoForUser(null, 0) == null)
        val original = android.content.pm.ActivityInfo().apply {
            name = "FixtureActivity"
            applicationInfo = android.content.pm.ApplicationInfo().apply {
                packageName = "org.example.permissions"
                uid = 10001
                initForUser(0)
            }
        }
        check(internal.getActivityInfoForUser(original, 0) === original)
        val remapped = internal.getActivityInfoForUser(original, 2)
        check(remapped !== original && remapped.applicationInfo !== original.applicationInfo)
        check(remapped.name == original.name && remapped.applicationInfo.uid == UserHandle.getUid(2, 10001))
        check(original.applicationInfo.uid == 10001 && remapped.applicationInfo.dataDir != original.applicationInfo.dataDir)
        check(internal.handleIncomingUser(1, 10001, 0, false, 2, "test", null) == 0)
        check(internal.handleIncomingUser(1, 0, UserHandle.USER_ALL, true, 2, "test", null) == UserHandle.USER_ALL)
        check(runCatching { internal.handleIncomingUser(1, 0, UserHandle.USER_ALL, false, 2, "test", null) }
            .exceptionOrNull() is IllegalArgumentException)
        check(runCatching { internal.handleIncomingUser(1, 10001, 1, false, 2, "test", null) }
            .exceptionOrNull() is SecurityException)
        check(internal.handleIncomingUser(1, UserHandle.getUid(1, 10001),
            UserHandle.USER_CURRENT_OR_SELF, false, 2, "test", null) == 1)

        val memory = android.app.ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        check(memory.totalMem > 0 && memory.availMem > 0 && memory.advertisedMem == memory.totalMem)
        check(memory.threshold == 0L && !memory.lowMemory)
        val process = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(process)
        check(process.pid == android.os.Process.myPid())
        check(process.uid == android.os.Process.myUid())
        check(process.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        check(manager.getHistoricalProcessExitReasons(null, Int.MAX_VALUE, 1).isEmpty())
        val task = android.app.ActivityManager.TaskDescription.Builder()
            .setLabel("Stock task").setPrimaryColor(android.graphics.Color.BLUE)
            .setBackgroundColor(android.graphics.Color.RED).build()
        val parcel = android.os.Parcel.obtain()
        try {
            task.writeToParcel(parcel, 0)
            memory.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val taskCopy = android.app.ActivityManager.TaskDescription.CREATOR.createFromParcel(parcel)
            check(taskCopy.label == "Stock task" && taskCopy.primaryColor == android.graphics.Color.BLUE)
            check(taskCopy.backgroundColor == android.graphics.Color.RED)
            val memoryCopy = android.app.ActivityManager.MemoryInfo.CREATOR.createFromParcel(parcel)
            check(memoryCopy.totalMem == memory.totalMem && memoryCopy.advertisedMem == memory.advertisedMem)
            check(parcel.dataAvail() == 0)
        } finally { parcel.recycle() }

        val root = java.nio.file.Files.createTempDirectory("azk-stock-backup").toFile()
        try {
            val file = java.io.File(root, "entities")
            java.io.FileOutputStream(file).use { stream ->
                val output = android.app.backup.BackupDataOutput(stream.fd, 4096, 3)
                check(output.quota == 4096L && output.transportFlags == 3)
                output.setKeyPrefix("scope")
                output.writeEntityHeader("key", 3)
                output.writeEntityData(byteArrayOf(1, 2, 3), 3)
                output.writeEntityHeader("delete", -1)
                output.setKeyPrefix("")
                output.writeEntityHeader("skip", 5)
                output.writeEntityData(byteArrayOf(4, 5, 6, 7, 8), 5)
                check(stream.fd.valid())
            }
            // Independently inspect the pinned BackupData wire header, not just a round-trip.
            val raw = java.nio.ByteBuffer.wrap(file.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            check(raw.int == 0x61746144 && raw.int == 9 && raw.int == 3)
            val key = ByteArray(10); raw.get(key)
            check(key.contentEquals("scope:key\u0000".toByteArray()))
            check(raw.get() == 0xbc.toByte() && raw.get() == 0xbc.toByte())
            check(raw.get() == 1.toByte() && raw.get() == 2.toByte() && raw.get() == 3.toByte())
            java.io.FileInputStream(file).use { stream ->
                val input = android.app.backup.BackupDataInput(stream.fd)
                check(runCatching { input.key }.exceptionOrNull() is IllegalStateException)
                check(input.readNextHeader() && input.key == "scope:key" && input.dataSize == 3)
                val bytes = ByteArray(7) { 99 }
                check(input.readEntityData(bytes, 2, 2) == 2)
                check(input.readEntityData(bytes, 4, 3) == 1)
                check(input.readEntityData(bytes, 0, 1) == 0)
                check(bytes.contentEquals(byteArrayOf(99, 99, 1, 2, 3, 99, 99)))
                check(input.readNextHeader() && input.key == "scope:delete" && input.dataSize == -1)
                check(input.readNextHeader() && input.key == "skip" && input.dataSize == 5)
                input.skipEntityData()
                check(!input.readNextHeader() && stream.fd.valid())
            }
            val payload = java.io.File(root, "payload.txt").apply { writeText("stock backup payload") }
            val measured = android.app.backup.FullBackupDataOutput(4096L)
            check(android.app.backup.FullBackup.backupToTar("example", "f", null,
                root.path, payload.path, measured) == 0)
            check(measured.size == 1024L)
            val archive = java.io.File(root, "archive")
            android.os.ParcelFileDescriptor.open(archive, android.os.ParcelFileDescriptor.MODE_CREATE or
                android.os.ParcelFileDescriptor.MODE_TRUNCATE or android.os.ParcelFileDescriptor.MODE_READ_WRITE).use { fd ->
                val output = android.app.backup.FullBackupDataOutput(fd, 4096L)
                check(android.app.backup.FullBackup.backupToTar("example", "f", null,
                    root.path, payload.path, output) == 0)
                check(output.size == measured.size && fd.fileDescriptor.valid())
            }
            // FullBackup emits big-endian transport chunk lengths around real tar blocks.
            val chunks = java.nio.ByteBuffer.wrap(archive.readBytes())
            val tar = java.io.ByteArrayOutputStream()
            while (chunks.hasRemaining()) {
                val chunk = ByteArray(chunks.int)
                chunks.get(chunk); tar.write(chunk)
            }
            val bytes = tar.toByteArray()
            check(bytes.size == 1024)
            check(String(bytes, 0, 100).trimEnd('\u0000') == "apps/example/f/payload.txt")
            check(String(bytes, 257, 5) == "ustar")
            check(String(bytes, 512, payload.length().toInt()) == payload.readText())
        } finally { root.deleteRecursively() }
    }

    private fun testStockUsbDevice() {
        // Exercise the public descriptor/parcel contract, including the serial
        // reader's permission boundary. No physical USB device is opened.
        val endpoint = UsbEndpoint(0x83, UsbConstants.USB_ENDPOINT_XFER_BULK, 512, 4)
        val first = UsbInterface(1, 0, "bulk", 0xff, 2, 3).apply {
            setEndpoints(arrayOf(endpoint))
        }
        val alternate = UsbInterface(1, 1, "alternate", 0xff, 2, 3).apply {
            setEndpoints(emptyArray())
        }
        val configurations = arrayOf(
            UsbConfiguration(1, "self powered", 0x60, 50).apply { setInterfaces(arrayOf(first)) },
            UsbConfiguration(2, "alternate config", 0, 25).apply { setInterfaces(arrayOf(alternate)) })
        var allowSerial = true
        val callingPackages = ArrayList<String?>()
        val reader = object : IUsbSerialReader.Stub() {
            override fun getSerial(packageName: String?): String {
                callingPackages.add(packageName)
                if (!allowSerial) throw SecurityException("USB serial permission denied")
                return "fixture-serial"
            }
        }
        fun build(version: String = "1.0"): UsbDevice = UsbDevice.Builder(
            "/dev/bus/usb/002/017", 0x1234, 0x5678, 0xef, 2, 1,
            "fixture vendor", "fixture device", version, configurations,
            "builder-only serial", true, false, true, false, true).build(reader)
        val device = build()
        check(device.deviceId == 2017)
        check(UsbDevice.getDeviceName(2017) == device.deviceName)
        check(UsbDevice.getDeviceId("not a usbfs path") == 0)
        check(device.configurationCount == 2 && device.interfaceCount == 2)
        check(device.getInterface(0) === first && device.getInterface(1) === alternate)
        check(device.serialNumber == "fixture-serial")
        val parcel = Parcel.obtain()
        val restored = try {
            device.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            UsbDevice.CREATOR.createFromParcel(parcel).also { check(parcel.dataAvail() == 0) }
        } finally { parcel.recycle() }
        check(restored == device && restored.hashCode() == device.hashCode())
        check(restored.vendorId == 0x1234 && restored.productId == 0x5678)
        check(restored.deviceClass == 0xef && restored.deviceSubclass == 2 && restored.deviceProtocol == 1)
        check(restored.manufacturerName == "fixture vendor" && restored.productName == "fixture device")
        check(restored.version == "1.0")
        check(restored.hasAudioPlayback && !restored.hasAudioCapture && restored.hasMidi)
        check(!restored.hasVideoPlayback && restored.hasVideoCapture)
        val config = restored.getConfiguration(0)
        check(config.isSelfPowered && config.isRemoteWakeup && config.maxPower == 100)
        val restoredEndpoint = restored.getInterface(0).getEndpoint(0)
        check(restoredEndpoint.direction == UsbConstants.USB_DIR_IN && restoredEndpoint.endpointNumber == 3)
        check(restoredEndpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK)
        check(restoredEndpoint.maxPacketSize == 512 && restoredEndpoint.interval == 4)
        check(restored.getInterface(1).alternateSetting == 1)
        check(restored.serialNumber == "fixture-serial")
        allowSerial = false
        check(runCatching { restored.serialNumber }.exceptionOrNull() is SecurityException)
        check(callingPackages.size == 3 && callingPackages.all { it == ActivityThread.currentPackageName() })
        check(runCatching { build("") }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun testStockVerity() {
        // Verbatim external fsverity-tool vector from AOSP VerityUtilsTest,
        // packaged into the fixture from coretests/res/raw and coretests/certs.
        val cert: ByteArray
        val signature: ByteArray
        java.util.zip.ZipFile(checkNotNull(permissionsFixtureApk)).use { apk ->
            cert = apk.getInputStream(checkNotNull(apk.getEntry("res/raw/fsverity_certificate.der"))).use { it.readBytes() }
            signature = apk.getInputStream(checkNotNull(apk.getEntry("res/raw/fsverity_sig"))).use { it.readBytes() }
        }
        val digest = java.util.HexFormat.of().parseHex(
            "3d248ca542a24fc62d1c43b916eae5016878e2533c88238480b26128a1f1af95")
        fun verify(sig: ByteArray, hash: ByteArray): Boolean =
            com.android.internal.security.VerityUtils.verifyPkcs7DetachedSignature(sig, hash,
                ByteArrayInputStream(cert))
        check(verify(signature, digest))
        check(!verify(signature, digest.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }))
        check(!verify(signature.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }, digest))
        check(!verify(signature, digest.copyOf(31)))
        check(!verify(signature, digest.copyOf(33)))
        val cms = CMSSignedData(signature)
        val certificateStore = CollectionStore(listOf(X509CertificateHolder(cert)))
        val embedded = CMSSignedData.replaceCertificatesAndCRLs(
            cms, certificateStore, null, null).encoded
        check(!verify(embedded, digest)) { "fs-verity signatures must use the caller's certificate" }

        val directory = java.nio.file.Files.createTempDirectory("artzig-verity-").toFile()
        try {
            val file = File(directory, "data").apply { writeText("fs-verity") }
            check(!com.android.internal.security.VerityUtils.hasFsverity(file.path))
            check(com.android.internal.security.VerityUtils.getFsverityDigest(file.path) == null)
            val enabled = runCatching { com.android.internal.security.VerityUtils.setUpFsverity(file.path) }
            if (enabled.isSuccess) {
                check(com.android.internal.security.VerityUtils.hasFsverity(file.path))
                check(com.android.internal.security.VerityUtils.getFsverityDigest(file.path)?.size == 32)
            } else {
                check(enabled.exceptionOrNull() is java.io.IOException)
                check(!com.android.internal.security.VerityUtils.hasFsverity(file.path))
            }
            check(file.readText() == "fs-verity")
            check(runCatching {
                com.android.internal.security.VerityUtils.setUpFsverity(File(directory, "absent").path)
            }.exceptionOrNull() is java.io.IOException)
            check(!com.android.internal.security.VerityUtils.hasFsverity(File(directory, "absent").path))
        } finally { directory.deleteRecursively() }
    }

    private fun testStockSurfaceSynchronization() {
        val events = ArrayList<String>()
        val immediate = java.util.concurrent.Executor { it.run() }
        val parent = android.window.SurfaceSyncGroup("module-parent") {
            check(it != null); events.add("parent-transaction")
        }.apply { toggleTimeout(false) }
        val child = android.window.SurfaceSyncGroup("module-child") {
            check(it == null); events.add("child-transaction")
        }.apply { toggleTimeout(false) }
        parent.addSyncCompleteCallback(immediate) { events.add("parent-complete") }
        child.addSyncCompleteCallback(immediate) { events.add("child-complete") }
        child.setAddedToSyncListener { events.add("child-added") }
        check(parent.add(child) { events.add("before-add") })
        check(events == listOf("before-add", "child-added"))
        parent.markSyncReady()
        check(events == listOf("before-add", "child-added"))
        var lateAddRan = false
        check(!parent.add(child) { lateAddRan = true } && !lateAddRan)
        check(parent.createTransactionReadyCallback(false) == null)
        child.markSyncReady()
        check(events == listOf("before-add", "child-added", "child-transaction", "child-complete",
            "parent-transaction", "parent-complete")) { events.toString() }
        parent.addSyncCompleteCallback(immediate) { events.add("late-complete") }
        parent.markSyncReady()
        check(events.last() == "late-complete" && events.size == 7)

        val service: android.view.IWindowManager = dev.artzig.system.HostedWindowManagerService(
            com.android.server.wm.HostedSurfaceSyncGroups())
        val token = Binder()
        val first = android.window.AddToSurfaceSyncGroupResult()
        val second = android.window.AddToSurfaceSyncGroupResult()
        var complete = 0
        val listener = object : android.window.ISurfaceSyncGroupCompletedListener.Stub() {
            override fun onSurfaceSyncGroupComplete() { complete++ }
        }
        check(service.addToSurfaceSyncGroup(token, false, listener, first))
        check(service.addToSurfaceSyncGroup(token, false, null, second))
        check(first.mParentSyncGroup.asBinder() == second.mParentSyncGroup.asBinder())
        service.markSurfaceSyncGroupReady(token)
        check(complete == 0)
        first.mTransactionReadyCallback.onTransactionReady(null)
        check(complete == 0)
        second.mTransactionReadyCallback.onTransactionReady(null)
        check(complete == 1)
        check(runCatching { service.markSurfaceSyncGroupReady(token) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { service.markSurfaceSyncGroupReady(Binder()) }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun testStockPixelCopy() {
        HostContexts.bootstrap(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")))
        StockReadbackSmoke.verifyGeometry()
        val dest = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        val invalid = android.view.Surface()
        // A real stock queue is valid but has no previously queued frame.
        val reader = android.media.ImageReader.newInstance(4, 4, android.graphics.PixelFormat.RGBA_8888, 2,
            android.hardware.HardwareBuffer.USAGE_CPU_READ_OFTEN or
                android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        val source = reader.surface
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        try {
            var delivered = false
            check(runCatching {
                android.view.PixelCopy.request(invalid, dest, { delivered = true }, handler)
            }.exceptionOrNull() is IllegalArgumentException)
            check(!delivered)
            check(runCatching {
                android.view.PixelCopy.request(source, Rect(), dest, { delivered = true }, handler)
            }.exceptionOrNull() is IllegalArgumentException)
            val immutable = checkNotNull(dest.copy(android.graphics.Bitmap.Config.ARGB_8888, false))
            try {
                check(runCatching {
                    android.view.PixelCopy.request(source, immutable, {}, handler)
                }.exceptionOrNull() is IllegalArgumentException)
            } finally { immutable.recycle() }
            val work = java.util.concurrent.LinkedBlockingQueue<Runnable>()
            val executor = java.util.concurrent.Executor { work.add(it) }
            val builder = android.view.PixelCopy.Request.Builder.ofSurface(source).setDestinationBitmap(dest)
            val request = builder.build()
            check(request.destinationBitmap === dest)
            check(runCatching { builder.build() }.exceptionOrNull() is IllegalStateException)
            android.view.PixelCopy.request(request, executor) { result ->
                check(result.status == android.view.PixelCopy.ERROR_SOURCE_NO_DATA) { "PixelCopy status=${result.status}" }
                check(runCatching { result.bitmap }.exceptionOrNull() is IllegalStateException)
                delivered = true
            }
            val completion = checkNotNull(work.poll(5, java.util.concurrent.TimeUnit.SECONDS))
            check(!delivered)
            completion.run()
            check(delivered)
            val canvas = source.lockCanvas(null)
            try { canvas.drawColor(android.graphics.Color.rgb(37, 83, 149)) }
            finally { source.unlockCanvasAndPost(canvas) }
            val produced = checkNotNull(reader.acquireLatestImage()).use { image ->
                val bytes = image.planes[0].buffer
                val actual = (0 until 4).map { bytes.get(it).toInt() and 255 }
                check(actual == listOf(37, 83, 149, 255)) { "Surface Canvas RGBA=$actual" }
                checkNotNull(image.hardwareBuffer)
            }
            produced.use { buffer ->
                // Hardware bitmap import must initialize HWUI before its first window.
                val hardware = checkNotNull(android.graphics.Bitmap.wrapHardwareBuffer(buffer,
                    android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)))
                try {
                    val copied = checkNotNull(hardware.copy(android.graphics.Bitmap.Config.ARGB_8888, false))
                    try {
                        val actual = copied.getPixel(0, 0)
                        check(actual == android.graphics.Color.rgb(37, 83, 149)) {
                            "Hardware bitmap readback=${Integer.toHexString(actual)}, expected ff255395"
                        }
                    } finally { copied.recycle() }
                } finally { hardware.recycle() }
                val deferred = StockReadbackSmoke.pixel(buffer)
                check(deferred == android.graphics.Color.rgb(37, 83, 149)) {
                    "Deferred hardware image readback=${Integer.toHexString(deferred)}, expected ff255395"
                }
            }
            delivered = false
            android.view.PixelCopy.request(
                android.view.PixelCopy.Request.Builder.ofSurface(source)
                    .setDestinationBitmap(dest).build(), executor,
            ) { result ->
                check(result.status == android.view.PixelCopy.SUCCESS) { "PixelCopy status=${result.status}" }
                check(result.bitmap === dest)
                for (y in 0 until 4) for (x in 0 until 4) {
                    val actual = dest.getPixel(x, y)
                    check(actual == android.graphics.Color.rgb(37, 83, 149)) {
                        "PixelCopy pixel ($x,$y)=${Integer.toHexString(actual)}, expected ff255395"
                    }
                }
                delivered = true
            }
            checkNotNull(work.poll(5, java.util.concurrent.TimeUnit.SECONDS)).run()
            check(delivered)
        } finally {
            source.release(); reader.close(); invalid.release(); dest.recycle()
        }
    }

    private fun testStockOverlayProperties() {
        val defaults = android.hardware.OverlayProperties.getDefault()
        fun checkDefault(value: android.hardware.OverlayProperties) {
            check(value.isMixedColorSpacesSupported)
            check(value.isCombinationSupported(android.hardware.DataSpace.DATASPACE_SRGB,
                android.hardware.HardwareBuffer.RGBA_8888))
            check(!value.isCombinationSupported(android.hardware.DataSpace.DATASPACE_DISPLAY_P3,
                android.hardware.HardwareBuffer.RGBA_8888))
            check(!value.isCombinationSupported(android.hardware.DataSpace.DATASPACE_SRGB,
                android.hardware.HardwareBuffer.RGB_565))
            check(value.lutProperties == null && value.describeContents() == 0)
        }
        check(defaults === android.hardware.OverlayProperties.getDefault())
        checkDefault(defaults)
        val parcel = Parcel.obtain()
        try {
            defaults.writeToParcel(parcel, 0)
            check(parcel.dataSize() > 4 && !parcel.hasFileDescriptors())
            parcel.setDataPosition(0)
            checkDefault(android.hardware.OverlayProperties.CREATOR.createFromParcel(parcel))
            check(parcel.dataAvail() == 0)
            parcel.setDataSize(0)
            parcel.setDataPosition(0)
            parcel.writeInt(0)
            parcel.setDataPosition(0)
            check(android.hardware.OverlayProperties.CREATOR.createFromParcel(parcel) == null)
            // A truncated native parcel creates an inert object, following the stock reader.
            parcel.setDataPosition(0)
            parcel.writeInt(1)
            parcel.setDataPosition(0)
            val malformed = android.hardware.OverlayProperties.CREATOR.createFromParcel(parcel)
            check(!malformed.isMixedColorSpacesSupported && malformed.lutProperties == null)
            check(!malformed.isCombinationSupported(android.hardware.DataSpace.DATASPACE_SRGB, 1))
            parcel.setDataSize(0)
            parcel.setDataPosition(0)
            // Pinned gui AIDL wire format: one 1D LUT with two sampling keys.
            // LutProperties' pinned Java constructor rejects every dimension;
            // JNI must propagate that exception safely instead of passing a native pointer as int[].
            for (word in intArrayOf(1, 44, 0, 1, 1, 1, 24, 1, 17, 2, 0, 2)) parcel.writeInt(word)
            parcel.setDataPosition(0)
            val withLut = android.hardware.OverlayProperties.CREATOR.createFromParcel(parcel)
            check(withLut.isMixedColorSpacesSupported && parcel.dataAvail() == 0)
            check(runCatching { withLut.lutProperties }.exceptionOrNull()?.let {
                it is IllegalArgumentException && it.message == "The dimension is either 1 or 3!"
            } == true)
        } finally { parcel.recycle() }
    }

    private fun testStockGraphicBuffers() {
        fun roundTrip(value: TaskSnapshot, creator: Parcelable.Creator<TaskSnapshot>): TaskSnapshot {
            val parcel = Parcel.obtain()
            return try {
                value.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                creator.createFromParcel(parcel)
            } finally { parcel.recycle() }
        }
        val usage = android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
            android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
            android.hardware.HardwareBuffer.USAGE_CPU_READ_OFTEN or
            android.hardware.HardwareBuffer.USAGE_CPU_WRITE_OFTEN
        check(runCatching { android.hardware.HardwareBuffer.isSupported(0, 8, 1, 1, usage) }
            .exceptionOrNull() is IllegalArgumentException)
        check(!android.hardware.HardwareBuffer.isSupported(8, 8, 1, 2, usage))
        if (!android.hardware.HardwareBuffer.isSupported(8, 8, 1, 1, usage)) {
            check(System.getenv("AZK_TEST_REQUIRE_EGL") != "1") { "GBM RGBA buffer is unavailable" }
            return
        }
        val graphic = checkNotNull(android.graphics.GraphicBuffer.create(8, 8, 1, usage.toInt()))
        var reader: android.hardware.HardwareBuffer? = null
        try {
            check(graphic.width == 8 && graphic.height == 8 && graphic.format == 1 && graphic.usage == usage.toInt())
            val dirty = Rect(2, 2, 6, 6)
            val canvas = checkNotNull(graphic.lockCanvas(dirty))
            check(canvas.width == 8 && canvas.height == 8 && canvas.clipBounds == dirty)
            canvas.drawColor(android.graphics.Color.RED)
            graphic.unlockCanvasAndPost(canvas)
            android.hardware.HardwareBuffer.createFromGraphicBuffer(graphic).use { owner ->
                check(owner.id != 0L && owner.width == 8 && owner.height == 8 && owner.usage == usage)
                check(owner.describeContents() == Parcelable.CONTENTS_FILE_DESCRIPTOR)
                val snapshot = stockTaskSnapshot(owner)
                check(snapshot.hardwareBuffer === owner)
                val snapshotCopy = roundTrip(snapshot, TaskSnapshot.CREATOR)
                check(snapshotCopy.id == 42L && snapshotCopy.captureTime > 1L)
                check(snapshotCopy.topActivityComponent == ComponentName("org.example", "org.example.Main"))
                check(snapshotCopy.taskSize == Point(800, 480) && snapshotCopy.contentInsets == Rect(1, 2, 3, 4))
                check(snapshotCopy.letterboxInsets == Rect(0, 5, 0, 5))
                check(snapshotCopy.rotation == android.view.Surface.ROTATION_90 && snapshotCopy.isRealSnapshot)
                check(snapshotCopy.hardwareBuffer.id == owner.id)
                val localListener = object : TaskStackListener() {}.apply { setIsLocal() }
                localListener.onTaskSnapshotChanged(601, snapshot)
                check(!owner.isClosed)
                object : TaskStackListener() {}.onTaskSnapshotChanged(601, snapshotCopy)
                check(snapshotCopy.hardwareBuffer.isClosed) { "stock remote task listener must release its snapshot buffer" }
                check(roundTrip(snapshotCopy, TaskSnapshot.CREATOR).hardwareBuffer == null)
                val legacySnapshot = snapshot.snapshot
                try { check(legacySnapshot.width == 8 && legacySnapshot.height == 8) }
                finally { legacySnapshot.destroy() }
                val parcel = Parcel.obtain()
                try {
                    owner.writeToParcel(parcel, 0)
                    check(parcel.hasFileDescriptors())
                    parcel.setDataPosition(0)
                    reader = android.hardware.HardwareBuffer.CREATOR.createFromParcel(parcel)
                    check(reader!!.id == owner.id && reader!!.width == owner.width && reader!!.usage == owner.usage)
                } finally { parcel.recycle() }
                val converted = android.graphics.GraphicBuffer.createFromHardwareBuffer(owner)
                try {
                    check(converted.width == graphic.width && converted.usage == graphic.usage)
                    val copyParcel = Parcel.obtain()
                    try {
                        converted.writeToParcel(copyParcel, 0)
                        copyParcel.setDataPosition(0)
                        val copy = checkNotNull(android.graphics.GraphicBuffer.CREATOR.createFromParcel(copyParcel))
                        try {
                            check(copy.width == 8 && copy.height == 8 && copy.format == 1)
                        } finally { copy.destroy() }
                    } finally { copyParcel.recycle() }
                } finally { converted.destroy() }
            }
            graphic.destroy()
            check(graphic.isDestroyed && graphic.lockCanvas() == null)
            check(reader!!.width == 8) { "parcel recipient must retain its imported allocation" }
        } finally {
            reader?.close()
            graphic.destroy()
        }
        check(reader!!.isClosed)
        check(runCatching { reader!!.width }.exceptionOrNull() is IllegalStateException)
        val malformed = Parcel.obtain()
        try {
            malformed.writeInt(0)
            malformed.writeInt(0)
            malformed.setDataPosition(0)
            check(runCatching { android.hardware.HardwareBuffer.CREATOR.createFromParcel(malformed) }
                .exceptionOrNull() is android.os.BadParcelableException)
        } finally { malformed.recycle() }
    }

    private fun stockTaskSnapshot(buffer: android.hardware.HardwareBuffer?): TaskSnapshot =
        TaskSnapshot(42, 1, ComponentName("org.example", "org.example.Main"), buffer,
            android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB),
            Configuration.ORIENTATION_LANDSCAPE, android.view.Surface.ROTATION_90, Point(800, 480),
            Rect(1, 2, 3, 4), Rect(0, 5, 0, 5), false, true,
            android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM, 0, true, false,
            Configuration.UI_MODE_TYPE_NORMAL)

    private fun testStockEgl() {
        val egl = android.opengl.EGL14::class.java
        check(egl.getDeclaredMethod("eglGetDisplay", Long::class.javaPrimitiveType) != null)
        check(android.opengl.EGL14.EGL_NO_DISPLAY.nativeHandle == 0L)
        check(android.opengl.EGL14.EGL_NO_CONTEXT.nativeHandle == 0L)
        check(android.opengl.EGL14.eglGetCurrentContext() === android.opengl.EGL14.EGL_NO_CONTEXT)
        check(runCatching { android.opengl.EGL14.eglGetDisplay(1) }.exceptionOrNull()
            is UnsupportedOperationException)
        check(!android.opengl.EGL14.eglInitialize(android.opengl.EGL14.EGL_NO_DISPLAY, null, 0, null, 0))
        check(android.opengl.EGL14.eglGetError() == android.opengl.EGL14.EGL_BAD_DISPLAY)
        check(android.opengl.EGL14.eglGetError() == android.opengl.EGL14.EGL_SUCCESS)
        val display = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
        check(display == android.opengl.EGL14.eglGetDisplay(0L))
        check(runCatching {
            android.opengl.EGL14.eglChooseConfig(display, intArrayOf(), 0, null, 0, 0, IntArray(1), 0)
        }.exceptionOrNull()?.message == "attrib_list must contain EGL_NONE!")
        check(runCatching {
            android.opengl.EGL14.eglInitialize(display, IntArray(1), -1, null, 0)
        }.exceptionOrNull()?.message == "majorOffset < 0")
        val major = intArrayOf(-1, -1, -1)
        val minor = intArrayOf(-2, -2, -2)
        val initialized = android.opengl.EGL14.eglInitialize(display, major, 1, minor, 1)
        if (!initialized) {
            check(System.getenv("AZK_TEST_REQUIRE_EGL") != "1") { "configured EGL driver did not initialize" }
            println("stock EGL: no configured driver; argument and failure contracts checked")
            return
        }
        check(major[0] == -1 && major[1] >= 1 && major[2] == -1)
        check(minor[0] == -2 && minor[2] == -2)
        var context = android.opengl.EGL14.EGL_NO_CONTEXT
        var surface = android.opengl.EGL14.EGL_NO_SURFACE
        try {
            check(!android.opengl.EGL14.eglQueryString(display, android.opengl.EGL14.EGL_VERSION).isNullOrBlank())
            val number = intArrayOf(-4, -4, -4)
            val first = arrayOfNulls<android.opengl.EGLConfig>(1)
            check(android.opengl.EGL14.eglGetConfigs(display, first, 0, 1, number, 1))
            val original = checkNotNull(first[0])
            check(number[0] == -4 && number[1] == 1 && number[2] == -4)
            val configs = arrayOf(original, original, original)
            val attributes = intArrayOf(android.opengl.EGL14.EGL_RENDERABLE_TYPE,
                android.opengl.EGL14.EGL_OPENGL_ES2_BIT, android.opengl.EGL14.EGL_SURFACE_TYPE,
                android.opengl.EGL14.EGL_PBUFFER_BIT, android.opengl.EGL14.EGL_NONE)
            check(android.opengl.EGL14.eglChooseConfig(display, attributes, 0, configs, 1, 1, number, 1))
            check(configs[0] === original && configs[2] === original && number[1] == 1)
            val types = IntArray(1)
            check(android.opengl.EGL14.eglGetConfigAttrib(display, configs[1], android.opengl.EGL14.EGL_SURFACE_TYPE, types, 0))
            if (types[0] and android.opengl.EGL14.EGL_WINDOW_BIT != 0) {
                check(types[0] and android.opengl.EGL14.EGL_PBUFFER_BIT != 0)
                // Android clients commonly leave surface type at its window
                // default and then create an offscreen pbuffer with that config.
                check(android.opengl.EGL14.eglChooseConfig(display,
                    intArrayOf(android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                        android.opengl.EGL14.EGL_NONE), 0, configs, 1, 1, number, 1))
                check(number[1] == 1)
            }
            check(android.opengl.EGL14.eglBindAPI(android.opengl.EGL14.EGL_OPENGL_ES_API))
            check(runCatching {
                android.opengl.EGL14.eglCreateContext(display, configs[1], android.opengl.EGL14.EGL_NO_CONTEXT, null, 0)
            }.exceptionOrNull()?.message == "attrib_list == null")
            context = android.opengl.EGL14.eglCreateContext(display, configs[1], android.opengl.EGL14.EGL_NO_CONTEXT,
                intArrayOf(android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE), 0)
            check(context != android.opengl.EGL14.EGL_NO_CONTEXT && context.nativeHandle != 0L)
            surface = android.opengl.EGL14.eglCreatePbufferSurface(display, configs[1],
                intArrayOf(-8, android.opengl.EGL14.EGL_WIDTH, 16, android.opengl.EGL14.EGL_HEIGHT, 8,
                    android.opengl.EGL14.EGL_NONE), 1)
            check(surface != android.opengl.EGL14.EGL_NO_SURFACE)
            check(android.opengl.EGL14.eglMakeCurrent(display, surface, surface, context))
            check(android.opengl.EGL14.eglGetCurrentContext() == context)
            check(android.opengl.EGL14.eglGetCurrentContext().hashCode() == context.hashCode())
            check(android.opengl.EGL14.eglGetCurrentDisplay() == display)
            check(android.opengl.EGL14.eglGetCurrentSurface(android.opengl.EGL14.EGL_DRAW) == surface)
            testStockGlesContext()
            StockEgl15Smoke.run(display, context)
            StockGlUtilitiesSmoke.run()
        com.android.server.wm.StockTaskFpsSmoke.denied()
        dev.artzig.window.StockTunnelModeSmoke.denied()
        dev.artzig.window.StockInputTransferSmoke.run()
            val value = intArrayOf(-5, -5, -5)
            check(android.opengl.EGL14.eglQuerySurface(display, surface, android.opengl.EGL14.EGL_WIDTH, value, 1))
            check(value.contentEquals(intArrayOf(-5, 16, -5)))
            check(android.opengl.EGL14.eglQueryContext(display, context, android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, value, 1))
            check(value[1] >= 2)
            check(!android.opengl.EGL14.eglQuerySurface(display, surface, -99, value, 1))
            check(android.opengl.EGL14.eglGetError() == android.opengl.EGL14.EGL_BAD_ATTRIBUTE)
            check(android.opengl.EGL14.eglGetError() == android.opengl.EGL14.EGL_SUCCESS)
            if (context.nativeHandle ushr 32 != 0L) {
                check(runCatching { context.handle }.exceptionOrNull() is UnsupportedOperationException)
            }
            check(android.opengl.EGL14.eglReleaseThread())
            check(android.opengl.EGL14.eglGetCurrentContext() === android.opengl.EGL14.EGL_NO_CONTEXT)
            println("stock EGL: real context and 16x8 pbuffer contracts passed")
        } finally {
            android.opengl.EGL14.eglMakeCurrent(display, android.opengl.EGL14.EGL_NO_SURFACE,
                android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT)
            if (surface != android.opengl.EGL14.EGL_NO_SURFACE) android.opengl.EGL14.eglDestroySurface(display, surface)
            if (context != android.opengl.EGL14.EGL_NO_CONTEXT) android.opengl.EGL14.eglDestroyContext(display, context)
            android.opengl.EGL14.eglTerminate(display)
            android.opengl.EGL14.eglReleaseThread()
        }
    }

    private fun testStockGlesContext() {
        val gl = android.opengl.GLES20::class.java
        check(gl.getDeclaredMethod("glGetError").let { java.lang.reflect.Modifier.isNative(it.modifiers) })
        for (name in listOf("android.opengl.GLES30", "android.opengl.GLES31", "android.opengl.GLES32")) {
            Class.forName(name)
        }
        check(!android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VERSION).isNullOrEmpty())
        check(android.opengl.GLES20.glGetError() == android.opengl.GLES20.GL_NO_ERROR)
        android.opengl.GLES20.glViewport(0, 0, 16, 8)
        val viewport = java.nio.IntBuffer.wrap(intArrayOf(-1, -1, -1, -1, -1, -1))
        viewport.position(1).limit(5)
        android.opengl.GLES20.glGetIntegerv(android.opengl.GLES20.GL_VIEWPORT, viewport)
        check(viewport.array().contentEquals(intArrayOf(-1, 0, 0, 16, 8, -1)))
        check(viewport.position() == 1 && viewport.limit() == 5)
        check(runCatching {
            android.opengl.GLES20.glGetIntegerv(android.opengl.GLES20.GL_VIEWPORT, java.nio.IntBuffer.allocate(3))
        }.exceptionOrNull() is IllegalArgumentException)
        android.opengl.GLES20.glClearColor(1f, 0f, 0f, 1f)
        android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT)
        val pixel = java.nio.ByteBuffer.allocateDirect(8)
        pixel.put(0, 17).position(4)
        android.opengl.GLES20.glReadPixels(0, 0, 1, 1, android.opengl.GLES20.GL_RGBA,
            android.opengl.GLES20.GL_UNSIGNED_BYTE, pixel)
        check(pixel.position() == 4 && pixel.get(0).toInt() == 17)
        check((pixel.get(4).toInt() and 255) == 255 && pixel.get(5).toInt() == 0 && pixel.get(6).toInt() == 0)
        check(android.opengl.GLES20.glGetError() == android.opengl.GLES20.GL_NO_ERROR)

        val textures = intArrayOf(-7, -7, -7)
        android.opengl.GLES20.glGenTextures(1, textures, 1)
        check(textures[0] == -7 && textures[1] > 0 && textures[2] == -7)
        try {
            check(!android.opengl.GLES20.glIsTexture(textures[1]))
            android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, textures[1])
            check(android.opengl.GLES20.glIsTexture(textures[1]))
            android.opengl.GLES20.glBindTexture(-1, textures[1])
            check(android.opengl.GLES20.glGetError() == android.opengl.GLES20.GL_INVALID_ENUM)
            check(android.opengl.GLES20.glGetError() == android.opengl.GLES20.GL_NO_ERROR)
        } finally {
            android.opengl.GLES20.glDeleteTextures(1, textures, 1)
        }
        check(!android.opengl.GLES20.glIsTexture(textures[1]))
        check(runCatching { android.opengl.GLES20.glGenTextures(1, IntArray(0), 0) }
            .exceptionOrNull() is IllegalArgumentException)
        check(runCatching { android.opengl.GLES20.glGenTextures(2, java.nio.IntBuffer.allocate(1)) }
            .exceptionOrNull() is IllegalArgumentException)

        val shader = android.opengl.GLES20.glCreateShader(android.opengl.GLES20.GL_VERTEX_SHADER)
        check(shader != 0)
        try {
            android.opengl.GLES20.glShaderSource(shader, "attribute vec4 position; void main() { gl_Position = position; }")
            android.opengl.GLES20.glCompileShader(shader)
            val status = java.nio.IntBuffer.wrap(intArrayOf(-3, -3, -3))
            status.position(1)
            android.opengl.GLES20.glGetShaderiv(shader, android.opengl.GLES20.GL_COMPILE_STATUS, status)
            check(status.get(0) == -3 && status.get(1) == android.opengl.GLES20.GL_TRUE && status.get(2) == -3) {
                android.opengl.GLES20.glGetShaderInfoLog(shader)
            }
            check(status.position() == 1)
            check(runCatching {
                android.opengl.GLES20.glGetShaderiv(shader, android.opengl.GLES20.GL_COMPILE_STATUS,
                    java.nio.IntBuffer.allocate(0))
            }.exceptionOrNull() is IllegalArgumentException)
        } finally { android.opengl.GLES20.glDeleteShader(shader) }
        check(android.opengl.GLES20.glGetError() == android.opengl.GLES20.GL_NO_ERROR)
        println("stock GLES: pixel readback, texture lifetime, shader compilation, buffer offsets, and GL errors passed")
    }

    private fun testStockDebugMemory() {
        val pid = android.os.Process.myPid()
        val info = android.os.Debug.MemoryInfo()
        check(android.os.Debug.getMemoryInfo(pid, info))
        check(info.totalPss > 0 && info.totalRss > 0)
        val parcel = Parcel.obtain()
        try {
            info.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = android.os.Debug.MemoryInfo.CREATOR.createFromParcel(parcel)
            check(restored.totalPss == info.totalPss && restored.totalRss == info.totalRss)
            check(restored.memoryStats == info.memoryStats)
        } finally {
            parcel.recycle()
        }
        val outputs = LongArray(5) { -7 }
        val memtrack = LongArray(5) { -9 }
        check(android.os.Debug.getPss(pid, outputs, memtrack) > 0)
        check(outputs[0] > 0 && outputs[2] >= outputs[0])
        check(outputs[3] == -7L && outputs[4] == -7L)
        check(memtrack.take(4).all { it == 0L } && memtrack[4] == -9L)
        check(android.os.Debug.getRss(pid, memtrack) > 0 && memtrack[4] == -9L)
        outputs.fill(-7)
        memtrack.fill(-9)
        check(android.os.Debug.getPss(-1, outputs, memtrack) == 0L)
        check(android.os.Debug.getRss(-1, memtrack) == 0L)
        check(outputs.all { it == -7L } && memtrack.all { it == -9L })
        val oldPss = info.totalPss
        check(!android.os.Debug.getMemoryInfo(-1, info) && info.totalPss == oldPss)
        val system = LongArray(android.os.Debug.MEMINFO_COUNT + 1) { -1 }
        android.os.Debug.getMemInfo(system)
        check(system[android.os.Debug.MEMINFO_TOTAL] > system[android.os.Debug.MEMINFO_FREE])
        check(system.last() == -1L)
        check(runCatching { android.os.Debug.getMemInfo(LongArray(1)) }.exceptionOrNull()
            ?.message == "outLen < MEMINFO_COUNT")
        check(android.os.Debug.getNativeHeapSize() > 0)
        check(android.os.Debug.getNativeHeapAllocatedSize() > 0)
        check(android.os.Debug.getNativeHeapFreeSize() >= 0)
        check(android.os.Debug.threadCpuTimeNanos() > 0)
        val report = java.io.File.createTempFile("allocator-report", ".xml")
        try {
            java.io.FileOutputStream(report).use { stream ->
                android.os.Debug.dumpNativeMallocInfo(stream.fd)
                check(stream.fd.valid())
                stream.write("\n<!-- caller still owns descriptor -->".toByteArray())
            }
            val contents = report.readText()
            check(contents.contains("<malloc version="))
            check(contents.endsWith("<!-- caller still owns descriptor -->"))
        } finally {
            report.delete()
        }
        val binder = Binder()
        val binderParcel = Parcel.obtain()
        val localCount = android.os.Debug.getBinderLocalObjectCount()
        try {
            binderParcel.writeStrongBinder(binder)
            check(android.os.Debug.getBinderLocalObjectCount() == localCount + 1)
            binderParcel.writeStrongBinder(binder)
            check(android.os.Debug.getBinderLocalObjectCount() == localCount + 1)
        } finally {
            binderParcel.recycle()
        }
        check(android.os.Debug.getBinderLocalObjectCount() == localCount)
        check(android.os.Debug.getBinderProxyObjectCount() >= 0)
        check(android.os.Debug.getBinderDeathObjectCount() >= 0)
    }

    private fun testStockInstrumentation() {
        val context = HostContexts.systemContext(
            checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 320, 240, 120, 0,
        )
        val instrumentation = Instrumentation()
        instrumentation.basicInit(context, context, null)
        val token = Binder()
        val application = android.app.Application()
        val intent = Intent().setComponent(ComponentName("android", InstrumentedActivity::class.java.name))
        val info = ActivityInfo().apply {
            packageName = "android"
            name = InstrumentedActivity::class.java.name
            applicationInfo = context.applicationInfo
        }
        val activity = instrumentation.newActivity(
            InstrumentedActivity::class.java, context, token, application, intent, info,
            "instrumented", null, null, null,
        ) as InstrumentedActivity
        check(activity.application === application && activity.activityToken === token)
        check(activity.intent === intent && activity.title == "instrumented")
        val monitor = instrumentation.addMonitor(InstrumentedActivity::class.java.name, null, false)
        val state = Bundle().apply { putString("restored", "bundle") }
        val persistent = PersistableBundle().apply { putString("restored", "persistent") }
        instrumentation.callActivityOnCreate(activity, state, persistent)
        check(monitor.lastActivity === activity)
        instrumentation.callActivityOnStart(activity)
        instrumentation.callActivityOnRestoreInstanceState(activity, state, persistent)
        instrumentation.callActivityOnPostCreate(activity, state, persistent)
        instrumentation.callActivityOnResume(activity)
        check(activity.isResumed)
        instrumentation.callActivityOnUserLeaving(activity)
        val saved = Bundle()
        val savedPersistent = PersistableBundle()
        instrumentation.callActivityOnSaveInstanceState(activity, saved, savedPersistent)
        check(saved.getString("saved") == "bundle")
        check(savedPersistent.getString("saved") == "persistent")
        instrumentation.callActivityOnPause(activity)
        check(!activity.isResumed)
        instrumentation.callActivityOnStop(activity)
        instrumentation.callActivityOnDestroy(activity)
        check(activity.events == listOf("create:persistent", "create", "start", "restore:persistent",
            "restore", "post-create", "resume", "interaction", "leave", "save:persistent", "save",
            "pause", "stop", "destroy")) { activity.events.toString() }
        instrumentation.removeMonitor(monitor)
        val result = Instrumentation.ActivityResult(Activity.RESULT_OK, Intent("result"))
        val blocked = instrumentation.addMonitor(InstrumentedActivity::class.java.name, result, true)
        val thread: android.app.IApplicationThread = ActivityThread.currentActivityThread().applicationThread
        check(instrumentation.execStartActivity(context, thread.asBinder(), token, activity, intent, 7, null) === result)
        check(instrumentation.execStartActivity(context, thread.asBinder(), token, activity, intent, -1, null) == null)
        check(blocked.hits == 2)
        check(intent.getParcelableExtra(Intent.EXTRA_REFERRER, Uri::class.java) == Uri.parse("android-app://fixture"))
        instrumentation.removeMonitor(blocked)
    }

    class InstrumentedActivity : Activity() {
        override fun onProvideReferrer(): Uri = Uri.parse("android-app://fixture")
        val events = mutableListOf<String>()
        override fun onCreate(state: Bundle?, persistentState: PersistableBundle?) {
            check(persistentState?.getString("restored") == "persistent")
            events += "create:persistent"
            super.onCreate(state, persistentState)
        }
        override fun onCreate(state: Bundle?) { events += "create"; super.onCreate(state) }
        override fun onStart() { events += "start"; super.onStart() }
        override fun onRestoreInstanceState(state: Bundle?, persistentState: PersistableBundle?) {
            check(persistentState?.getString("restored") == "persistent")
            events += "restore:persistent"
            super.onRestoreInstanceState(state, persistentState)
        }
        override fun onRestoreInstanceState(state: Bundle) {
            check(state.getString("restored") == "bundle")
            events += "restore"
            super.onRestoreInstanceState(state)
        }
        override fun onPostCreate(state: Bundle?) { events += "post-create"; super.onPostCreate(state) }
        override fun onResume() { events += "resume"; super.onResume() }
        override fun onUserInteraction() { events += "interaction" }
        override fun onUserLeaveHint() { events += "leave" }
        override fun onSaveInstanceState(state: Bundle, persistentState: PersistableBundle) {
            events += "save:persistent"
            persistentState.putString("saved", "persistent")
            super.onSaveInstanceState(state, persistentState)
        }
        override fun onSaveInstanceState(state: Bundle) {
            events += "save"
            state.putString("saved", "bundle")
            super.onSaveInstanceState(state)
        }
        override fun onPause() { events += "pause"; super.onPause() }
        override fun onStop() { events += "stop"; super.onStop() }
        override fun onDestroy() { events += "destroy"; super.onDestroy() }
    }

    private fun testWindowContextTransactionContracts() {
        // These stock WM messages must retain token identity over our Parcel and
        // snapshot configuration before the sender mutates its next update.
        val token = Binder()
        val configuration = Configuration().apply { densityDpi = 240 }
        val change = android.app.servertransaction.WindowContextInfoChangeItem(
            token, configuration, 7,
        )
        configuration.densityDpi = 320
        val parcel = Parcel.obtain()
        try {
            change.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            check(parcel.readStrongBinder() === token)
            val info = checkNotNull(parcel.readTypedObject(android.window.WindowContextInfo.CREATOR))
            check(info.displayId == 7)
            check(info.configuration.densityDpi == 240)
            check(parcel.dataAvail() == 0)
            parcel.setDataPosition(0)
            check(android.app.servertransaction.WindowContextInfoChangeItem.CREATOR
                .createFromParcel(parcel) == change)
        } finally {
            parcel.recycle()
        }
        val removal = android.app.servertransaction.WindowContextWindowRemovalItem(token)
        val removalParcel = Parcel.obtain()
        try {
            removal.writeToParcel(removalParcel, 0)
            removalParcel.setDataPosition(0)
            check(removalParcel.readStrongBinder() === token)
            check(removalParcel.dataAvail() == 0)
            removalParcel.setDataPosition(0)
            check(android.app.servertransaction.WindowContextWindowRemovalItem.CREATOR
                .createFromParcel(removalParcel) == removal)
        } finally {
            removalParcel.recycle()
        }
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

    private fun testStockModuleClientContracts() {
        check(InetAddresses.parseNumericAddress("192.0.2.1").hostAddress == "192.0.2.1")

        val capabilities = NetworkCapabilities.Builder.withoutDefaultCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        check(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        check(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))

        val request = NetworkRequest.Builder()
            .clearCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        check(request.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        check(!request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))

        val proxy = ProxyInfo.buildDirectProxy("proxy.example", 8080, listOf("localhost", "*.test"))
        check(proxy.host == "proxy.example")
        check(proxy.port == 8080)
        check(proxy.exclusionListAsString == "localhost,*.test")
        check(proxy.isValid)
        val proxyParcel = Parcel.obtain()
        val restoredProxy = try {
            proxy.writeToParcel(proxyParcel, 0)
            proxyParcel.setDataPosition(0)
            ProxyInfo.CREATOR.createFromParcel(proxyParcel)
        } finally {
            proxyParcel.recycle()
        }
        check(restoredProxy == proxy)
        check(ProxyInfo.buildPacProxy(Uri.parse("https://proxy.example/proxy.pac")).isValid)

        val configuration = InterfaceConfiguration().apply {
            hardwareAddress = "02:00:00:00:00:01"
            setInterfaceUp()
        }
        val configurationParcel = Parcel.obtain()
        val restoredConfiguration = try {
            configuration.writeToParcel(configurationParcel, 0)
            configurationParcel.setDataPosition(0)
            InterfaceConfiguration.CREATOR.createFromParcel(configurationParcel)
        } finally {
            configurationParcel.recycle()
        }
        check(restoredConfiguration.hardwareAddress == "02:00:00:00:00:01")
        check(restoredConfiguration.isUp)
        check(
            INetworkManagementService.Stub::class.java
                .getDeclaredMethod("asInterface", IBinder::class.java) != null,
        )
        check(
            INetworkManagementEventObserver.Stub::class.java
                .getDeclaredMethod("asInterface", IBinder::class.java) != null,
        )

        check(android.hardware.thermal.TemperatureType.CPU == 0)
        check(android.hardware.vibrator.Braking.CLAB == 1)
    }

    private fun testTaskInfoParcelableContracts() {
        fun <T : Parcelable> roundTrip(value: T, creator: Parcelable.Creator<T>): T {
            val parcel = Parcel.obtain()
            return try {
                value.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                creator.createFromParcel(parcel)
            } finally {
                parcel.recycle()
            }
        }

        val recent = ActivityManager.RecentTaskInfo().apply {
            id = 401
            persistentId = 402
            populateTaskInfo(this, 4)
        }
        val recentCopy = roundTrip(recent, ActivityManager.RecentTaskInfo.CREATOR)
        check(recentCopy.id == 401 && recentCopy.persistentId == 402)
        assertTaskInfoCopy(recentCopy, 4)

        val running = ActivityManager.RunningTaskInfo().apply {
            id = 501
            populateTaskInfo(this, 5)
        }
        val runningCopy = roundTrip(running, ActivityManager.RunningTaskInfo.CREATOR)
        check(runningCopy.id == 501)
        assertTaskInfoCopy(runningCopy, 5)

        val root = ActivityTaskManager.RootTaskInfo().apply {
            bounds = Rect(6, 7, 608, 609)
            childTaskIds = intArrayOf(61, 62)
            childTaskNames = arrayOf("child-6-a", "child-6-b")
            childTaskBounds = arrayOf(Rect(1, 2, 3, 4), Rect(5, 6, 7, 8))
            childTaskUserIds = intArrayOf(63, 64)
            visible = true
            position = 65
            populateTaskInfo(this, 6)
        }
        val rootCopy = roundTrip(root, ActivityTaskManager.RootTaskInfo.CREATOR)
        check(rootCopy.bounds == Rect(6, 7, 608, 609))
        check(checkNotNull(rootCopy.childTaskIds).contentEquals(intArrayOf(61, 62)))
        check(checkNotNull(rootCopy.childTaskNames).contentEquals(
            arrayOf("child-6-a", "child-6-b"),
        ))
        check(checkNotNull(rootCopy.childTaskBounds).contentEquals(
            arrayOf(Rect(1, 2, 3, 4), Rect(5, 6, 7, 8)),
        ))
        check(checkNotNull(rootCopy.childTaskUserIds).contentEquals(intArrayOf(63, 64)))
        check(rootCopy.visible && rootCopy.position == 65)
        assertTaskInfoCopy(rootCopy, 6)

        object : TaskStackListener() {}.onTaskSnapshotChanged(601, stockTaskSnapshot(null))
    }

    private fun populateTaskInfo(task: TaskInfo, marker: Int) {
        task.userId = marker
        task.taskId = marker * 10 + 1
        task.effectiveUid = marker * 10 + 2
        task.displayId = marker * 10 + 3
        task.isRunning = true
        task.baseIntent = Intent("org.example.BASE.$marker")
            .setData(Uri.parse("https://example.test/base/$marker"))
        task.baseActivity = ComponentName("org.example", "Base$marker")
        task.topActivity = ComponentName("org.example", "Top$marker")
        task.origActivity = ComponentName("org.example", "Orig$marker")
        task.realActivity = ComponentName("org.example", "Real$marker")
        task.numActivities = marker * 10 + 4
        task.lastActiveTime = marker * 1_000L + 5
        task.taskDescription = ActivityManager.TaskDescription("task-$marker").apply {
            setIcon(android.graphics.drawable.Icon.createWithResource("org.example", marker + 6))
        }
        task.supportsMultiWindow = true
        task.resizeMode = ActivityInfo.RESIZE_MODE_RESIZEABLE
        task.configuration.orientation = Configuration.ORIENTATION_LANDSCAPE
        task.configuration.densityDpi = 300 + marker
        task.configuration.windowConfiguration.setBounds(
            Rect(marker, marker + 1, marker + 200, marker + 300),
        )
        task.token = WindowContainerToken(object : IWindowContainerToken.Stub() {})
        task.topActivityType = marker + 7
        task.pictureInPictureParams = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(marker + 1, marker + 2))
            .build()
        task.shouldDockBigOverlays = true
        task.launchIntoPipHostTaskId = marker * 10 + 8
        task.lastParentTaskIdBeforePip = marker * 10 + 9
        task.displayCutoutInsets = Rect(marker + 1, marker + 2, marker + 3, marker + 4)
        task.topActivityInfo = ActivityInfo().apply {
            packageName = "org.example"
            name = "TopInfo$marker"
            resizeMode = ActivityInfo.RESIZE_MODE_RESIZEABLE
            applicationInfo = ApplicationInfo().apply {
                packageName = "org.example"
                processName = "org.example"
            }
        }
        task.isResizeable = true
        task.minWidth = marker + 10
        task.minHeight = marker + 11
        task.defaultMinSize = marker + 12
        task.launchCookies.add(Binder())
        task.positionInParent = Point(marker + 13, marker + 14)

        // Keep the complete current TaskInfo tail non-default. These fields have historically
        // drifted independently of the public Recent/Running/Root subclass parcelers.
        task.parentTaskId = marker * 10 + 15
        task.isFocused = true
        task.isVisible = true
        task.isVisibleRequested = true
        task.isTopActivityNoDisplay = true
        task.isSleeping = true
        task.mTopActivityLocusId = LocusId("locus-$marker")
        task.displayAreaFeatureId = marker + 16
        task.isTopActivityTransparent = true
        task.isActivityStackTransparent = true
        task.lastNonFullscreenBounds = Rect(marker + 17, marker + 18, marker + 117, marker + 118)
        task.capturedLink = Uri.parse("https://example.test/captured/$marker")
        task.capturedLinkTimestamp = marker * 1_000L + 19
        task.requestedVisibleTypes = marker + 20
        task.topActivityRequestOpenInBrowserEducationTimestamp = marker * 1_000L + 21
        task.appCompatTaskInfo.apply {
            topActivityLetterboxVerticalPosition = marker + 22
            topActivityLetterboxHorizontalPosition = marker + 23
            topActivityLetterboxWidth = marker + 124
            topActivityLetterboxHeight = marker + 225
            topActivityAppBounds.set(marker + 1, marker + 2, marker + 301, marker + 402)
            topActivityLetterboxBounds = Rect(marker + 3, marker + 4, marker + 303, marker + 404)
            setTopActivityInSizeCompat(true)
            setTopActivityLetterboxed(true)
            setLetterboxDoubleTapEnabled(true)
            setHasMinAspectRatioOverride(true)
            cameraCompatTaskInfo.freeformCameraCompatMode =
                android.app.CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_PORTRAIT_DEVICE_IN_LANDSCAPE
        }
        task.topActivityMainWindowFrame =
            Rect(marker + 24, marker + 25, marker + 324, marker + 425)
    }

    private fun assertTaskInfoCopy(task: TaskInfo, marker: Int) {
        check(task.userId == marker)
        check(task.taskId == marker * 10 + 1)
        check(task.effectiveUid == marker * 10 + 2)
        check(task.displayId == marker * 10 + 3)
        check(task.isRunning)
        check(task.baseIntent.action == "org.example.BASE.$marker")
        check(task.baseIntent.data == Uri.parse("https://example.test/base/$marker"))
        check(task.baseActivity == ComponentName("org.example", "Base$marker"))
        check(task.topActivity == ComponentName("org.example", "Top$marker"))
        check(task.origActivity == ComponentName("org.example", "Orig$marker"))
        check(task.realActivity == ComponentName("org.example", "Real$marker"))
        check(task.numActivities == marker * 10 + 4)
        check(task.lastActiveTime == marker * 1_000L + 5)
        check(task.taskDescription?.label == "task-$marker")
        check(task.supportsMultiWindow)
        check(task.resizeMode == ActivityInfo.RESIZE_MODE_RESIZEABLE)
        check(task.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        check(task.configuration.densityDpi == 300 + marker)
        check(task.configuration.windowConfiguration.bounds ==
            Rect(marker, marker + 1, marker + 200, marker + 300))
        check(task.token.asBinder() != null)
        check(task.topActivityType == marker + 7)
        check(task.pictureInPictureParams?.aspectRatio == Rational(marker + 1, marker + 2))
        check(task.shouldDockBigOverlays)
        check(task.launchIntoPipHostTaskId == marker * 10 + 8)
        check(task.lastParentTaskIdBeforePip == marker * 10 + 9)
        check(task.displayCutoutInsets == Rect(marker + 1, marker + 2, marker + 3, marker + 4))
        check(task.topActivityInfo?.name == "TopInfo$marker")
        check(task.isResizeable)
        check(task.minWidth == marker + 10)
        check(task.minHeight == marker + 11)
        check(task.defaultMinSize == marker + 12)
        check(task.launchCookies.size == 1)
        check(task.positionInParent == Point(marker + 13, marker + 14))
        check(task.parentTaskId == marker * 10 + 15)
        check(task.isFocused)
        check(task.isVisible)
        check(task.isVisibleRequested)
        check(task.isTopActivityNoDisplay)
        check(task.isSleeping)
        check(task.mTopActivityLocusId?.id == "locus-$marker")
        check(task.displayAreaFeatureId == marker + 16)
        check(task.isTopActivityTransparent)
        check(task.isActivityStackTransparent)
        check(task.lastNonFullscreenBounds ==
            Rect(marker + 17, marker + 18, marker + 117, marker + 118))
        check(task.capturedLink == Uri.parse("https://example.test/captured/$marker"))
        check(task.capturedLinkTimestamp == marker * 1_000L + 19)
        check(task.requestedVisibleTypes == marker + 20)
        check(task.topActivityRequestOpenInBrowserEducationTimestamp == marker * 1_000L + 21)
        check(task.appCompatTaskInfo.topActivityLetterboxVerticalPosition == marker + 22)
        check(task.appCompatTaskInfo.topActivityLetterboxHorizontalPosition == marker + 23)
        check(task.appCompatTaskInfo.topActivityLetterboxWidth == marker + 124)
        check(task.appCompatTaskInfo.topActivityLetterboxHeight == marker + 225)
        check(task.appCompatTaskInfo.topActivityAppBounds ==
            Rect(marker + 1, marker + 2, marker + 301, marker + 402))
        check(task.appCompatTaskInfo.topActivityLetterboxBounds ==
            Rect(marker + 3, marker + 4, marker + 303, marker + 404))
        check(task.appCompatTaskInfo.isTopActivityInSizeCompat)
        check(task.appCompatTaskInfo.isTopActivityLetterboxed)
        check(task.appCompatTaskInfo.isLetterboxDoubleTapEnabled)
        check(task.appCompatTaskInfo.hasMinAspectRatioOverride())
        check(task.appCompatTaskInfo.cameraCompatTaskInfo.freeformCameraCompatMode ==
            android.app.CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_PORTRAIT_DEVICE_IN_LANDSCAPE)
        check(task.topActivityMainWindowFrame ==
            Rect(marker + 24, marker + 25, marker + 324, marker + 425))
    }

    private fun testAccessibilityHtmlFiltering() {
        val filter = android.accessibilityservice.util.AccessibilityUtils::getFilteredHtmlText
        check(filter("<a>link</a>") == "<invalidtag link</invalidtag>")
        check(filter("<img alt=\"bad\" src=\"elsewhere\"/>").startsWith("<invalidtag "))
        check(filter("<img src=\"R.drawable.preview\"/>") ==
            "<img src=\"R.drawable.preview\"/>")
    }

    private fun testFrameworkServiceValueContracts() {
        check(SettingsLibFlags.writeSystemPreferencePermissionEnabled())
        for (labels in listOf(null, "attribution")) {
            val usage = android.permission.PermissionGroupUsage("dev.artzig.fixture", 12345,
                987654L, android.Manifest.permission_group.MICROPHONE, true, false,
                labels, labels, labels, "virtual-device-7")
            val permissionParcel = Parcel.obtain()
            val copy = try {
                usage.writeToParcel(permissionParcel, 0)
                permissionParcel.setDataPosition(0)
                android.permission.PermissionGroupUsage.CREATOR.createFromParcel(permissionParcel)
            } finally { permissionParcel.recycle() }
            check(copy == usage && copy.hashCode() == usage.hashCode())
            check(copy.packageName == "dev.artzig.fixture" && copy.uid == 12345 &&
                copy.lastAccessTimeMillis == 987654L && copy.isActive && !copy.isPhoneCall)
            check(copy.permissionGroupName == android.Manifest.permission_group.MICROPHONE &&
                copy.persistentDeviceId == "virtual-device-7" && copy.attributionTag == labels &&
                copy.attributionLabel == labels && copy.proxyLabel == labels)
        }
        val visualResult = VisualQueryDetectedResult.Builder()
            .setPartialQuery("weather tomorrow")
            .setSpeakerId(3)
            .setAccessibilityDetectionData(*byteArrayOf(1, 2, 3))
            .build()
        check(visualResult.partialQuery == "weather tomorrow")
        check(visualResult.speakerId == 3)
        check(visualResult.accessibilityDetectionData.contentEquals(byteArrayOf(1, 2, 3)))
        check(runCatching {
            VisualQueryDetectedResult.Builder()
                .setSpeakerId(VisualQueryDetectedResult.getMaxSpeakerId() + 1)
                .build()
        }.exceptionOrNull() is IllegalArgumentException)

        val response = Bundle()
        TextClassifierService.putResponse(response, visualResult)
        val restoredResponse = TextClassifierService.getResponse<VisualQueryDetectedResult>(response)
        check(restoredResponse == visualResult)

        val captureOptions = android.content.ContentCaptureOptions(
            1000,
            1001,
            1002,
            1003,
            1004,
            true,
            false,
            android.content.ContentCaptureOptions.ContentProtectionOptions(
                true,
                2001,
                listOf(listOf("required")),
                listOf(listOf("optional")),
                1,
            ),
            android.util.ArraySet<ComponentName>().apply {
                add(ComponentName("dev.artzig", "CapturedActivity"))
            },
        )
        val parcel = Parcel.obtain()
        val restoredOptions = try {
            captureOptions.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            android.content.ContentCaptureOptions.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
        check(restoredOptions.loggingLevel == 1000)
        check(restoredOptions.maxBufferSize == 1001)
        check(restoredOptions.disableFlushForViewTreeAppearing)
        check(!restoredOptions.enableReceiver)
        check(restoredOptions.contentProtectionOptions.bufferSize == 2001)
        check(restoredOptions.contentProtectionOptions.requiredGroups ==
            listOf(listOf("required")))
        check(restoredOptions.whitelistedComponents == captureOptions.whitelistedComponents)
    }

    private fun testContentCaptureContracts() {
        val service = object : ContentCaptureService() {}
        service.onCreate()
        check(service.onBind(Intent(ContentCaptureService.SERVICE_INTERFACE)) != null)
        check(service.onBind(Intent(ContentCaptureService.PROTECTION_SERVICE_INTERFACE)) != null)
        check(service.onBind(Intent("dev.artzig.WRONG_CONTENT_CAPTURE_ACTION")) == null)

        var flushedEvents: List<ContentCaptureEvent>? = null
        val manager = Proxy.newProxyInstance(
            IContentCaptureManager::class.java.classLoader,
            arrayOf(IContentCaptureManager::class.java),
        ) { _, method, arguments ->
            if (method.name == "onLoginDetected") {
                val events = arguments?.get(0) as android.content.pm.ParceledListSlice<*>
                flushedEvents = events.list.filterIsInstance<ContentCaptureEvent>()
            }
            null
        } as IContentCaptureManager
        val options = android.content.ContentCaptureOptions.ContentProtectionOptions(
            true,
            16,
            listOf(listOf("username"), listOf("password")),
            listOf(listOf("sign in")),
            1,
        )
        val processor = ContentProtectionEventProcessor(
            RingBuffer(ContentCaptureEvent::class.java, 16),
            Handler(Looper.getMainLooper()),
            manager,
            "dev.artzig.capture",
            options,
        )
        fun appeared(text: String) =
            ContentCaptureEvent(123, ContentCaptureEvent.TYPE_VIEW_APPEARED).setText(text)

        processor.processEvent(appeared("USERNAME"))
        processor.processEvent(appeared("PASSWORD"))
        drainMainQueue()
        check(flushedEvents == null)
        processor.processEvent(appeared("Tap to SIGN IN"))
        drainMainQueue()
        check(flushedEvents?.size == 3)
        check(flushedEvents?.all {
            it.viewNode?.textIdEntry == "dev.artzig.capture"
        } == true)
    }

    private fun testTraceBufferContracts() {
        val first = ProtoOutputStream().apply { write(0x10500000001L, 7) }
        val second = ProtoOutputStream().apply { write(0x10500000001L, 11) }
        val firstBytes = first.bytes
        val secondBytes = second.bytes
        val trace = TraceBuffer<ProtoOutputStream, ProtoOutputStream, ProtoOutputStream>(
            firstBytes.size + 1,
        )
        trace.add(first)
        check(trace.contains(firstBytes))
        trace.add(second)
        check(trace.size() == 1)
        check(!trace.contains(firstBytes))
        check(trace.contains(secondBytes))
        trace.resetBuffer()
        check(trace.size() == 0)
    }

    private fun testProviderContracts() {
        check(Downloads.Impl.CONTENT_URI.toString() == "content://downloads/my_downloads")
        check(Downloads.Impl.isStatusInformational(Downloads.Impl.STATUS_RUNNING))
        check(Downloads.Impl.isStatusSuccess(Downloads.Impl.STATUS_SUCCESS))
        check(Downloads.Impl.isStatusError(Downloads.Impl.STATUS_BAD_REQUEST))
        check(Downloads.Impl.statusToString(Downloads.Impl.STATUS_WAITING_FOR_NETWORK) ==
            "WAITING_FOR_NETWORK")

        val digest = ByteArray(Pin.getDigestLength("SHA-256")) { it.toByte() }
        val pin = Pin("SHA-256", digest)
        val pinSet = PinSet(setOf(pin), 1234L)
        val parent = NetworkSecurityConfig.Builder()
            .setCleartextTrafficPermitted(false)
            .setHstsEnforced(true)
        val config = NetworkSecurityConfig.Builder()
            .setParent(parent)
            .setPinSet(pinSet)
            .build()
        check(!config.isCleartextTrafficPermitted)
        check(config.isHstsEnforced)
        check(config.pins === pinSet)
        check(config.pins.pins == setOf(pin))

        val child = NetworkSecurityConfig.Builder().setParent(parent)
        check(runCatching { parent.setParent(child) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { Pin.getDigestLength("MD5") }.exceptionOrNull() is
            IllegalArgumentException)

        // Compare stock directory lookup against the existing Conscrypt host-bundle parser.
        // This catches missing files, PEM labels and subject-hash collision suffixes without
        // depending on any external HTTPS endpoint or hard-coded distribution certificate.
        val referenceFactory = javax.net.ssl.TrustManagerFactory.getInstance("PKIX", "HarmonyJSSE")
        referenceFactory.init(null as java.security.KeyStore?)
        val reference = (referenceFactory.trustManagers.single() as X509TrustManager).acceptedIssuers.toSet()
        check(reference.isNotEmpty())
        val systemSource = SystemCertificateSource.getInstance()
        check(systemSource.certificates == reference) { "Stock system CA source differs from host trust bundle" }
        for (certificate in reference) {
            check(systemSource.findBySubjectAndPublicKey(certificate) != null) {
                "Stock subject-hash lookup failed for ${certificate.subjectX500Principal}"
            }
        }
        val trusted = NetworkSecurityConfig.Builder().addCertificatesEntryRef(
            CertificatesEntryRef(systemSource, false, false),
        ).build()
        check(trusted.trustManager.acceptedIssuers.toSet() == reference)
        // A domain configuration with no anchors must not silently inherit the host bundle.
        val isolated = NetworkSecurityConfig.Builder().build()
        check(isolated.trustManager.acceptedIssuers.isEmpty())
        check(runCatching {
            isolated.trustManager.checkServerTrusted(arrayOf(reference.first()), "RSA", "example.com")
        }.exceptionOrNull() is java.security.cert.CertificateException)

        val provider = NetworkSecurityConfigProvider()
        check(provider.name == "AndroidNSSP")
        check(provider["Alg.Alias.TrustManagerFactory.X509"] == "PKIX")
        val service = checkNotNull(provider.getService("TrustManagerFactory", "PKIX"))
        check(service.className ==
            "android.security.net.config.RootTrustManagerFactorySpi")
        provider.javaClass.classLoader.loadClass(service.className)

        val plainTrustManager = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
            override fun checkClientTrusted(
                chain: Array<java.security.cert.X509Certificate>,
                authType: String,
            ) = Unit
            override fun checkServerTrusted(
                chain: Array<java.security.cert.X509Certificate>,
                authType: String,
            ) = Unit
        }
        check(runCatching {
            X509TrustManagerExtensions(plainTrustManager)
        }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun testStatFsContracts() {
        val stat = StatFs(".")
        check(stat.blockSizeLong > 0)
        check(stat.blockCountLong > 0)
        check(stat.totalBytes == stat.blockCountLong * stat.blockSizeLong)
        check(stat.availableBytes == stat.availableBlocksLong * stat.blockSizeLong)
        check(stat.freeBytes >= stat.availableBytes)
        stat.restat("/tmp")
        check(stat.totalBytes > 0)
        check(runCatching {
            StatFs("/path-that-does-not-exist/artzig-statfs")
        }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun testHostedPermissionGrants() {
        val frameworkResources =
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk"
        // Server-side install of the fixture APK: WAKE_LOCK (normal) is
        // granted at install, CAMERA (dangerous) is not.
        val fixture = checkNotNull(permissionsFixtureApk) { "permission fixture APK argument missing" }
        val installer = checkNotNull(dev.artzig.HostedServiceClient.installerOrNull())
        check(installer.installPackage(fixture, null) == "org.example.permissions")

        val wakeLock = "android.permission.WAKE_LOCK"
        val camera = "android.permission.CAMERA"
        // AOSP checks the calling uid's grants: this process is bound to the
        // fixture package, so its package context answers for it.
        dev.artzig.HostedServiceClient.attachApplication("org.example.permissions")
        val base = packageIdentityContext(
            HostContexts.systemContext(frameworkResources, 200, 200, 120, 0),
            "org.example.permissions",
        )
        check(java.io.File(checkNotNull(base.applicationInfo.nativeLibraryDir)).isAbsolute)
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
        // AppGlobals exposes the application installed by the bindApplication callback.
        check(android.app.AppGlobals.getInitialPackage() == ActivityThread.currentPackageName())
        check(android.app.AppGlobals.getInitialApplication() === ActivityThread.currentApplication())
        check(android.app.AppGlobals.getPackageManager() === ActivityThread.getPackageManager())
        check(android.app.AppGlobals.getIntCoreSetting("dev.artzig.missing.setting", 41) == 41)
        check(android.app.AppGlobals.getFloatCoreSetting("dev.artzig.missing.setting", 1.25f) == 1.25f)
        val permissions = checkNotNull(android.app.AppGlobals.getPermissionManager())
        check(permissions === ActivityThread.getPermissionManager())
        val uid = android.os.Process.myUid()
        val defaultDevice = android.companion.virtual.VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT
        check(permissions.checkUidPermission(uid, wakeLock, Context.DEVICE_ID_DEFAULT) == PackageManager.PERMISSION_GRANTED)
        check(permissions.checkUidPermission(uid, camera, Context.DEVICE_ID_DEFAULT) == PackageManager.PERMISSION_DENIED)
        check(permissions.checkUidPermission(android.os.Process.ROOT_UID, null, Context.DEVICE_ID_DEFAULT) == PackageManager.PERMISSION_DENIED)
        check(permissions.checkUidPermission(UserHandle.getUid(1, UserHandle.getAppId(uid)), wakeLock, 0) == PackageManager.PERMISSION_DENIED)
        check(permissions.checkPermission("org.example.permissions", wakeLock, defaultDevice, 0) == PackageManager.PERMISSION_GRANTED)
        check(permissions.checkPermission("org.example.permissions", camera, defaultDevice, 0) == PackageManager.PERMISSION_DENIED)
        check(permissions.checkPermission("org.example.permissions", wakeLock, defaultDevice, 1) == PackageManager.PERMISSION_DENIED)
        check(permissions.checkPermission(null, wakeLock, defaultDevice, 0) == PackageManager.PERMISSION_DENIED)
        check(permissions.checkPermission("org.example.missing", wakeLock, defaultDevice, 0) == PackageManager.PERMISSION_DENIED)
        // WAKE_LOCK is not device-aware; AOSP reads its default-device grant.
        check(permissions.checkUidPermission(uid, wakeLock, 777) == PackageManager.PERMISSION_GRANTED)
        StockPermissionClientsSmoke.run(base, fixture)
        val permissionState = android.permission.PermissionManager.PermissionState(true, 0x1234)
        val stateParcel = Parcel.obtain()
        try {
            permissionState.writeToParcel(stateParcel, 0)
            stateParcel.setDataPosition(0)
            check(stateParcel.readBoolean() && stateParcel.readInt() == 0x1234 && stateParcel.dataAvail() == 0)
            stateParcel.setDataPosition(0)
            val copy = android.permission.PermissionManager.PermissionState.CREATOR.createFromParcel(stateParcel)
            check(copy == permissionState && copy.hashCode() == permissionState.hashCode())
            check(copy.isGranted() && copy.getFlags() == 0x1234)
            check(copy.toString() == "PermissionState{mGranted=true, mFlags=4660}")
        } finally {
            stateParcel.recycle()
        }
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
        ).let { packageIdentityContext(it, "org.example.permissions") }
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
        ).let { packageIdentityContext(it, "org.example.permissions") }
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
            val dm = checkNotNull(context.getSystemService(android.hardware.display.DisplayManager::class.java))
            check(dm === context.getSystemService(Context.DISPLAY_SERVICE)) { "display manager not cached" }
            check(dm.displays.size == 1) { "expected one display" }
            val display = checkNotNull(dm.getDisplay(android.view.Display.DEFAULT_DISPLAY))
            // Stock Display uses the context's configured maximum bounds. Resize both
            // the display service and resources through the production host entry point.
            HostContexts.configureDisplay(800, 600, 120)
            val size = android.graphics.Point()
            checkNotNull(dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)).getRealSize(size)
            check(size.x == 800 && size.y == 600) { "display size $size after configure" }
            check(display.displayId == android.view.Display.DEFAULT_DISPLAY)
            val im = checkNotNull(context.getSystemService(android.hardware.input.InputManager::class.java))
            check(im.inputDeviceIds.contentEquals(intArrayOf(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD))) {
                "unexpected input devices"
            }
            checkNotNull(android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD))
            val inputMethods = checkNotNull(
                context.getSystemService(android.view.inputmethod.InputMethodManager::class.java),
            )
            check(inputMethods.inputMethodList.isEmpty()) { "host must expose no software IMEs" }
            check(inputMethods.enabledInputMethodList.isEmpty()) { "host must enable no software IMEs" }
            check(!inputMethods.isStylusHandwritingAvailable) {
                "a device with no selected IME cannot provide stylus handwriting"
            }
            check(!inputMethods.isConnectionlessStylusHandwritingAvailable) {
                "a device with no selected IME cannot provide connectionless handwriting"
            }
            val permissionEnforcer = checkNotNull(
                context.getSystemService(android.os.PermissionEnforcer::class.java),
            )
            check(permissionEnforcer === context.getSystemService(Context.PERMISSION_ENFORCER_SERVICE)) {
                "permission enforcer not cached"
            }
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
        val activityTaskManager = context.getSystemService(ActivityTaskManager::class.java)
        check(activityTaskManager === ActivityTaskManager.getInstance())
        val activityTaskService = ActivityTaskManager.getService()
        check(activityTaskService != null)
        check(activityTaskService.activityClientController != null)
        check(activityTaskService.getTasks(4, false, true, Display.DEFAULT_DISPLAY).isEmpty())
        check(activityTaskService.getRecentTasks(4, 0, UserHandle.USER_SYSTEM).list.isEmpty())
        check(runCatching { activityTaskService.focusedRootTaskInfo }
            .exceptionOrNull() is SecurityException)
        check(runCatching { activityTaskService.getTaskBounds(Int.MAX_VALUE) }
            .exceptionOrNull() is SecurityException)
        check(activityTaskService.startActivity(
            null,
            null,
            null,
            Intent().setComponent(ComponentName("org.example.missing", "MissingActivity")),
            null,
            null,
            null,
            -1,
            0,
            null,
            null,
        ) == ActivityManager.START_CLASS_NOT_FOUND)
        val taskListener = object : TaskStackListener() {}
        check(runCatching { activityTaskService.registerTaskStackListener(taskListener) }
            .exceptionOrNull() is SecurityException)

        val statusBarManager =
            checkNotNull(context.getSystemService(android.app.StatusBarManager::class.java))
        check(statusBarManager === context.getSystemService(Context.STATUS_BAR_SERVICE))
        statusBarManager.disable(android.app.StatusBarManager.DISABLE_NONE)
        statusBarManager.collapsePanels()
        val statusBarService = com.android.internal.statusbar.IStatusBarService.Stub.asInterface(
            android.os.ServiceManager.getServiceOrThrow(Context.STATUS_BAR_SERVICE),
        )
        check(statusBarService.getDisableFlags(android.os.Binder(), 0).contentEquals(intArrayOf(0, 0)))
        check(statusBarService.lastSystemKey == -1)
        check(statusBarService.navBarMode == android.app.StatusBarManager.NAV_BAR_MODE_DEFAULT)
        val tileResult = AtomicInteger(Int.MIN_VALUE)
        val tileResultDelivered = java.util.concurrent.CountDownLatch(1)
        statusBarService.requestAddTile(
            ComponentName("dev.artzig", "HostedTile"),
            "Hosted tile",
            null,
            0,
            object : com.android.internal.statusbar.IAddTileResultCallback.Stub() {
                override fun onTileRequest(userResponse: Int) {
                    tileResult.set(userResponse)
                    tileResultDelivered.countDown()
                }
            },
        )
        check(tileResultDelivered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        check(tileResult.get() ==
            android.app.StatusBarManager.TILE_ADD_REQUEST_ERROR_NO_STATUS_BAR_SERVICE)

        val incidentManager = checkNotNull(
            context.getSystemService(android.os.IncidentManager::class.java),
        )
        check(incidentManager === context.getSystemService(Context.INCIDENT_SERVICE))
        incidentManager.reportIncident(android.os.IncidentReportArgs())
        check(incidentManager.pendingReports.isEmpty())
        check(incidentManager.getIncidentReportList("HostedReceiver").isEmpty())
        val incidentDenied = java.util.concurrent.CountDownLatch(1)
        incidentManager.requestAuthorization(
            android.os.Process.myUid(),
            context.packageName,
            0,
            { command -> command.run() },
            object : android.os.IncidentManager.AuthListener() {
                override fun onReportDenied() {
                    incidentDenied.countDown()
                }
            },
        )
        check(incidentDenied.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // Bind this JVM to the installed fixture before exercising clients that authenticate the
        // context package against the Binder caller UID (clipboard, PendingIntent, and peers).
        val fixture = checkNotNull(permissionsFixtureApk) { "permission fixture APK argument missing" }
        val fixtureInstaller = checkNotNull(dev.artzig.HostedServiceClient.installerOrNull())
        check(fixtureInstaller.installPackage(fixture, null) == "org.example.permissions")
        val fixtureApplication = dev.artzig.HostedServiceClient.attachApplication("org.example.permissions")
        val appContext = packageIdentityContext(context, "org.example.permissions")

        checkNotNull(appContext.getSystemService(android.content.ClipboardManager::class.java))
        val clipboardManager = android.content.ClipboardManager(
            appContext,
            Handler(appContext.mainLooper),
        )
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
        val registeredGrammaticalInflectionManager =
            checkNotNull(appContext.getSystemService(GrammaticalInflectionManager::class.java))
        check(registeredGrammaticalInflectionManager ===
            appContext.getSystemService(Context.GRAMMATICAL_INFLECTION_SERVICE))
        // A package context in this headless JVM smoke has no Application object (APK launch
        // tests do). Give the stock client's application-context read a normal self-returning
        // wrapper while retaining the package, resources, user and attribution identity.
        val managerContext = object : ContextWrapper(appContext) {
            override fun getApplicationContext(): Context = this
        }
        val grammaticalInflectionManager = GrammaticalInflectionManager(
            managerContext,
            android.app.IGrammaticalInflectionManager.Stub.asInterface(
                android.os.ServiceManager.getServiceOrThrow(Context.GRAMMATICAL_INFLECTION_SERVICE),
            ),
        )
        fun grammaticalGenderBecomes(expected: Int): Boolean {
            repeat(250) {
                drainMainQueue()
                if (grammaticalInflectionManager.applicationGrammaticalGender == expected) return true
                Thread.sleep(20)
            }
            return false
        }
        try {
            grammaticalInflectionManager.setRequestedApplicationGrammaticalGender(
                Configuration.GRAMMATICAL_GENDER_FEMININE,
            )
            check(grammaticalGenderBecomes(Configuration.GRAMMATICAL_GENDER_FEMININE))
            check(runCatching {
                grammaticalInflectionManager.setRequestedApplicationGrammaticalGender(Int.MIN_VALUE)
            }.exceptionOrNull() is IllegalArgumentException)
            check(runCatching {
                grammaticalInflectionManager.systemGrammaticalGender
            }.exceptionOrNull() is SecurityException)
            check(grammaticalInflectionManager.peekSystemGrammaticalGenderByUserId(0) ==
                Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED)
            check(runCatching {
                grammaticalInflectionManager.setSystemWideGrammaticalGender(
                    Configuration.GRAMMATICAL_GENDER_FEMININE,
                )
            }.exceptionOrNull() is SecurityException)
        } finally {
            grammaticalInflectionManager.setRequestedApplicationGrammaticalGender(
                Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED,
            )
            check(grammaticalGenderBecomes(Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED))
        }
        StockDownloadManagerSmoke.run(fixtureApplication)
    }

    private fun testHostedVirtualDeviceDefaults() {
        val service = dev.artzig.system.HostedVirtualDeviceManagerInternal()
        check(service.getDeviceOwnerUid(42) == -1)
        check(service.getDeviceIdsForUid(android.os.Process.SYSTEM_UID).isEmpty())
        check(service.getDisplayIdsForDevice(42).isEmpty())
        for (displayId in intArrayOf(Display.INVALID_DISPLAY, Display.DEFAULT_DISPLAY, 42)) {
            check(service.getDeviceIdForDisplayId(displayId) == android.content.Context.DEVICE_ID_DEFAULT)
        }
        // LocalService queries return snapshots; a caller cannot publish state by retaining one.
        service.getDeviceIdsForUid(android.os.Process.SYSTEM_UID).add(42)
        check(service.getDeviceIdsForUid(android.os.Process.SYSTEM_UID).isEmpty())
        check(service.getVirtualSensor(42, 1) == null)
        check(service.getPreferredLocaleListForUid(android.os.Process.SYSTEM_UID) == null)
        check(!service.isAppRunningOnAnyVirtualDevice(android.os.Process.SYSTEM_UID))
        check(!service.isInputDeviceOwnedByVirtualDevice(42))
        check(!service.isValidVirtualDeviceId(android.content.Context.DEVICE_ID_DEFAULT))
        check(service.getDeviceIdForDisplayId(42) == android.content.Context.DEVICE_ID_DEFAULT)
        check(service.getDimDurationMillisForDeviceId(42) == -1L)
        check(service.getScreenOffTimeoutMillisForDeviceId(42) == -1L)
        check(service.getPersistentIdForDevice(android.content.Context.DEVICE_ID_DEFAULT) ==
            android.companion.virtual.VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT)
        check(service.getPersistentIdForDevice(42) == null && service.getVirtualDevice(42) == null)
        val appListener = VirtualDeviceManagerInternal.AppsOnVirtualDeviceListener {
            error("The unchanged empty UID set must not trigger a callback")
        }
        service.registerAppsOnVirtualDeviceListener(appListener)
        service.onAppsOnVirtualDeviceChanged()
        service.onAuthenticationPrompt(android.os.Process.SYSTEM_UID)
        service.unregisterAppsOnVirtualDeviceListener(appListener)
        val removals = ArrayList<String>()
        val listener = java.util.function.Consumer<String> { id ->
            check(android.os.Looper.myLooper() === android.os.Looper.getMainLooper())
            removals.add(id)
        }
        service.registerPersistentDeviceIdRemovedListener(listener)
        service.registerPersistentDeviceIdRemovedListener(listener)
        service.onPersistentDeviceIdsRemoved(linkedSetOf("first", "second"))
        service.unregisterPersistentDeviceIdRemovedListener(listener)
        check(removals.isEmpty())
        drainMainQueue()
        check(removals == listOf("first", "first", "second", "second"))
        service.onPersistentDeviceIdsRemoved(setOf("third"))
        drainMainQueue()
        check(removals.last() == "third" && removals.size == 5)
        service.unregisterPersistentDeviceIdRemovedListener(listener)
        service.onPersistentDeviceIdsRemoved(setOf("fourth"))
        drainMainQueue()
        check(removals.size == 5)
        val nullParams = runCatching {
            service.javaClass.getMethod("createVirtualDevice", android.companion.virtual.VirtualDeviceParams::class.java)
                .invoke(service, *arrayOfNulls<Any>(1))
        }.exceptionOrNull()
        check(nullParams is java.lang.reflect.InvocationTargetException && nullParams.cause is NullPointerException)
        val nameless = runCatching { service.createVirtualDevice(android.companion.virtual.VirtualDeviceParams.Builder().build()) }.exceptionOrNull()
        check(nameless is NullPointerException && nameless.message == "virtual device name must not be null")
    }

    private fun testHostedActivityTaskServiceTransactions() {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            320,
            240,
            120,
            0,
        )
        android.app.PropertyInvalidatedCache.disableForTestMode()
        StockPackageSnapshotsSmoke.run(checkNotNull(permissionsFixtureApk))
        val packages = HostedPackageManagerService().apply {
            installPlatform(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")))
        }
        val callerPackage = "org.example.permissions"
        check(packages.installPackage(checkNotNull(permissionsFixtureApk)) == callerPackage)
        testTaskRemovalServices(context, packages, callerPackage)
        val activityManager = HostedActivityManagerService(context).apply { packageService = packages }
        val processOne = "android:hosted-task-one"
        val processTwo = "android:hosted-task-two"
        val resolver: (Intent, String?) -> ActivityInfo? = { intent, _ ->
            intent.component?.let { component ->
                val selectedProcess = if (component.className.contains("Two") ||
                    component.className.contains("Unhandled")
                ) {
                    processTwo
                } else if (component.className.contains("Missing")) {
                    "android:hosted-task-missing"
                } else {
                    processOne
                }
                ActivityInfo().apply {
                    packageName = component.packageName
                    name = component.className
                    processName = selectedProcess
                    exported = true
                    configChanges = if (component.className.contains("Handled")) {
                        ActivityInfo.CONFIG_ORIENTATION
                    } else {
                        0
                    }
                    applicationInfo = ApplicationInfo().apply {
                        packageName = component.packageName
                        processName = selectedProcess
                        uid = android.os.Process.SYSTEM_UID
                        targetSdkVersion = android.os.Build.VERSION_CODES.CUR_DEVELOPMENT
                    }
                }
            }
        }
        val service = HostedActivityTaskManagerService(
            context,
            activityManager,
            packages,
            resolver,
        )
        activityManager.activityTaskManager = service
        val threadOne = CapturingApplicationThread()
        val threadTwo = CapturingApplicationThread()
        val callingUid = Binder.getCallingUid()
        packages.bindPackageUid(callerPackage, callingUid)
        activityManager.noteProcessPackage(callingUid, callerPackage, processOne)
        activityManager.attachApplication(threadOne, 0)
        var readyCallbacks = 0
        check(activityManager.attachedProcessForPackage(callerPackage, processOne) == null)
        check(activityManager.withApplicationThread(callerPackage, processOne, false) { readyCallbacks++ })
        check(readyCallbacks == 0 && threadOne.transactions.isEmpty())
        check(runCatching { activityManager.finishAttachApplication(1, 0) }
            .exceptionOrNull() is SecurityException)
        check(readyCallbacks == 0)
        activityManager.finishAttachApplication(0, 0)
        check(readyCallbacks == 1)
        activityManager.finishAttachApplication(0, 0)
        check(readyCallbacks == 1) { "duplicate attach acknowledgement replayed component work" }
        activityManager.noteProcessPackage(callingUid, callerPackage, processTwo)
        activityManager.attachApplication(threadTwo, 0)
        activityManager.finishAttachApplication(0, 0)
        check(threadOne.boundProcessName == processOne && threadTwo.boundProcessName == processTwo)
        check(threadOne.boundPackageName == callerPackage && threadTwo.boundPackageName == callerPackage)
        val controller = service.activityClientController

        fun component(name: String) = ComponentName(callerPackage, "dev.artzig.$name")
        fun launch(
            name: String,
            resultTo: android.os.IBinder? = null,
            requestCode: Int = -1,
        ): Int = service.startActivity(
            null,
            callerPackage,
            null,
            Intent().setComponent(component(name)),
            null,
            resultTo,
            null,
            requestCode,
            0,
            null,
            null,
        )
        fun launchToken(transaction: ClientTransaction): android.os.IBinder =
            (transaction.transactionItems.first { it is LaunchActivityItem } as LaunchActivityItem)
                .activityToken
        fun hasItems(transaction: ClientTransaction, vararg types: Class<*>): Boolean =
            types.all { type -> transaction.transactionItems.any(type::isInstance) }

        check(launch("HandledOne") == ActivityManager.START_SUCCESS)
        val firstLaunch = threadOne.removeFirstTransaction()
        check(hasItems(firstLaunch, LaunchActivityItem::class.java, ResumeActivityItem::class.java))
        val firstToken = launchToken(firstLaunch)
        val permissionRequest = Intent(PackageManager.ACTION_REQUEST_PERMISSIONS)
            .setPackage(packages.permissionControllerPackageName)
            .putExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_NAMES,
                arrayOf(android.Manifest.permission.WAKE_LOCK, "", android.Manifest.permission.CAMERA))
            // The caller may not choose whose grants are returned.
            .putExtra(Intent.EXTRA_PACKAGE_NAME, "android")
        check(packages.resolveActivityInfo(permissionRequest, null)?.packageName == "android")
        fun requestPermissions(caller: IApplicationThread, token: android.os.IBinder) = service.startActivity(
            caller, callerPackage, null, permissionRequest, null, token, "permission-test", 91, 0, null, null)
        check(requestPermissions(threadOne, firstToken) == ActivityManager.START_SUCCESS)
        check(threadOne.transactions.isEmpty()) { "permission result escaped the initial resume gate" }
        check(runCatching { requestPermissions(threadTwo, firstToken) }.exceptionOrNull() is SecurityException)
        check(requestPermissions(threadOne, Binder()) == ActivityManager.START_CANCELED)
        controller.activityResumed(firstToken, false)
        val permissionItem = threadOne.removeFirstTransaction().transactionItems.single() as ActivityResultItem
        val permissionResultField = ActivityResultItem::class.java.getDeclaredField("mResultInfoList")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val permissionResult = (permissionResultField.get(permissionItem) as List<ResultInfo>).single()
        check(permissionResult.mRequestCode == 91 && permissionResult.mResultWho == "permission-test")
        check(permissionResult.mResultCode == Activity.RESULT_OK && permissionResult.mCallerToken != null)
        check(permissionResult.mData.getStringExtra(Intent.EXTRA_PACKAGE_NAME) == callerPackage)
        check(permissionResult.mData.getStringArrayExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_NAMES)
            .contentEquals(arrayOf(android.Manifest.permission.WAKE_LOCK, android.Manifest.permission.CAMERA)))
        check(permissionResult.mData.getIntArrayExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_RESULTS)
            .contentEquals(intArrayOf(PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_DENIED)))
        check(packages.checkPermission(android.Manifest.permission.CAMERA, callerPackage, 0) == PackageManager.PERMISSION_DENIED)

        threadOne.transactions.clear()
        threadTwo.transactions.clear()
        check(launch("Two", firstToken, 42) == ActivityManager.START_SUCCESS)
        check(threadOne.transactions.single().transactionItems.single() is PauseActivityItem)
        check(threadTwo.transactions.isEmpty()) {
            "cross-process launch escaped the source activityPaused gate"
        }
        controller.activityPaused(firstToken)
        val secondLaunch = threadTwo.removeFirstTransaction()
        check(hasItems(secondLaunch, LaunchActivityItem::class.java, ResumeActivityItem::class.java))
        val secondToken = launchToken(secondLaunch)
        controller.activityResumed(secondToken, false)
        check(threadOne.transactions.last().transactionItems.single() is StopActivityItem)
        controller.activityStopped(firstToken, null, null, null)

        val task = service.getTasks(1, false, true, Display.DEFAULT_DISPLAY).single()
        check(task.topActivity == component("Two"))
        check(task.numActivities == 2 && task.baseActivity == component("HandledOne"))
        check(service.getRecentTasks(1, 0, UserHandle.USER_SYSTEM).list.single().taskId == task.taskId)
        val appTask = android.app.IAppTask.Stub.asInterface(service.getAppTasks(callerPackage).single())
        check(appTask.taskInfo.taskId == task.taskId && appTask.taskInfo.numActivities == 2)
        appTask.setExcludeFromRecents(true)
        check(appTask.taskInfo.baseIntent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0)
        appTask.setExcludeFromRecents(false)
        check(appTask.taskInfo.baseIntent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS == 0)
        var taskPackageDenied = false
        try { service.getAppTasks("dev.artzig.unowned") } catch (_: SecurityException) {
            taskPackageDenied = true
        }
        check(taskPackageDenied)
        check(service.getAppTasks(callerPackage).size == 1)

        threadOne.transactions.clear()
        threadTwo.transactions.clear()
        check(controller.finishActivity(
            secondToken,
            Activity.RESULT_OK,
            Intent("dev.artzig.HOSTED_RESULT"),
            0,
        ))
        check(threadTwo.transactions.single().transactionItems.single() is PauseActivityItem)
        check(threadOne.transactions.isEmpty()) { "finish result escaped the source pause gate" }
        controller.activityPaused(secondToken)
        val reveal = threadOne.removeFirstTransaction()
        check(reveal.transactionItems[0] is ActivityResultItem)
        check(reveal.transactionItems.last() is ResumeActivityItem)
        check(threadTwo.transactions.none { tx ->
            tx.transactionItems.any { it is DestroyActivityItem }
        }) { "source destroyed before result target resumed" }
        controller.activityResumed(firstToken, false)
        check(threadTwo.transactions.last().transactionItems.single() is DestroyActivityItem)
        controller.activityDestroyed(secondToken)

        // Pending activity results have no ComponentCaller attribution and wait while paused.
        controller.activityPaused(firstToken)
        threadOne.transactions.clear()
        val resultSender = checkNotNull(activityManager.getIntentSenderWithFeature(
            ActivityManager.INTENT_SENDER_ACTIVITY_RESULT,
            callerPackage,
            null,
            firstToken,
            "hosted",
            73,
            arrayOf(Intent("dev.artzig.PENDING_RESULT")),
            arrayOf(null),
            PendingIntent.FLAG_IMMUTABLE,
            null,
            UserHandle.USER_SYSTEM,
        ))
        check(activityManager.sendIntentSender(
            null, resultSender, null, Activity.RESULT_FIRST_USER, null, null, null, null, null,
        ) == ActivityManager.START_SUCCESS)
        check(threadOne.transactions.isEmpty())
        controller.activityResumed(firstToken, false)
        val pendingResultItem = threadOne.removeFirstTransaction().transactionItems.single()
            as ActivityResultItem
        val resultField = ActivityResultItem::class.java.getDeclaredField("mResultInfoList").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val pendingResults = resultField.get(pendingResultItem) as List<ResultInfo>
        check(pendingResults.single().mCallerToken == null)
        // A stop report posted before the resume must not hide the resumed receiver from
        // result dispatch. This is the server-side half of ActivityThread's delayed stop IPC.
        controller.activityStopped(firstToken, null, null, null)
        check(activityManager.sendIntentSender(
            null, resultSender, null, Activity.RESULT_FIRST_USER, null, null, null, null, null,
        ) == ActivityManager.START_SUCCESS)
        check(threadOne.removeFirstTransaction().transactionItems.single() is ActivityResultItem) {
            "late stop acknowledgement stranded a resumed activity result"
        }

        threadOne.transactions.clear()
        val initialOrientation = context.resources.configuration.orientation
        val firstOrientation = if (initialOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            Configuration.ORIENTATION_PORTRAIT
        } else {
            Configuration.ORIENTATION_LANDSCAPE
        }
        check(service.updateConfigurationFromHost(
            Configuration().apply { orientation = firstOrientation },
        ))
        check(threadOne.transactions.any { tx ->
            tx.transactionItems.any { it is ActivityConfigurationChangeItem }
        })

        // An unhandled change preserves resumed state with Relaunch + Resume in one request.
        threadOne.transactions.clear()
        threadTwo.transactions.clear()
        check(launch("UnhandledTwo") == ActivityManager.START_SUCCESS)
        check(threadTwo.transactions.isEmpty())
        controller.activityPaused(firstToken)
        val thirdLaunch = threadTwo.removeFirstTransaction()
        val thirdToken = launchToken(thirdLaunch)
        controller.activityResumed(thirdToken, false)
        check(threadOne.transactions.last().transactionItems.single() is StopActivityItem)
        controller.activityStopped(firstToken, null, null, null)
        appTask.setExcludeFromRecents(true)
        check(service.getRecentTasks(10, 0, 0).list.none { it.taskId == task.taskId })
        check(service.getRecentTasks(10, ActivityManager.RECENT_WITH_EXCLUDED, 0).list.any { it.taskId == task.taskId })
        check(service.getAppTasks(callerPackage).any {
            android.app.IAppTask.Stub.asInterface(it).taskInfo.taskId == task.taskId
        })
        appTask.setExcludeFromRecents(false)
        threadOne.transactions.clear()
        threadTwo.transactions.clear()
        val secondOrientation = if (firstOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            Configuration.ORIENTATION_PORTRAIT
        } else {
            Configuration.ORIENTATION_LANDSCAPE
        }
        check(service.updateConfigurationFromHost(
            Configuration().apply { orientation = secondOrientation },
        ))
        val relaunch = threadTwo.transactions.first { tx ->
            tx.transactionItems.any { it is ActivityRelaunchItem }
        }
        check(relaunch.transactionItems.any { it is ResumeActivityItem })
        controller.activityRelaunched(thirdToken)
        controller.activityResumed(thirdToken, false)

        // Resource-size reports suppress relaunches within a qualifier bucket but retain
        // them when a declared width boundary is crossed, even without configChanges flags.
        val width = context.resources.configuration.screenWidthDp.coerceAtLeast(1)
        controller.reportSizeConfigurations(firstToken, android.window.SizeConfigurationBuckets(emptyArray()))
        controller.reportSizeConfigurations(thirdToken, android.window.SizeConfigurationBuckets(
            arrayOf(Configuration().apply { screenWidthDp = width + 100 }),
        ))
        threadTwo.transactions.clear()
        check(service.updateConfigurationFromHost(Configuration().apply { screenWidthDp = width + 1 }))
        check(threadTwo.transactions.any { tx -> tx.transactionItems.any { it is ActivityConfigurationChangeItem } })
        check(threadTwo.transactions.none { tx -> tx.transactionItems.any { it is ActivityRelaunchItem } })
        threadTwo.transactions.clear()
        check(service.updateConfigurationFromHost(Configuration().apply { screenWidthDp = width + 100 }))
        check(threadTwo.transactions.any { tx -> tx.transactionItems.any { it is ActivityRelaunchItem } })
        controller.activityRelaunched(thirdToken)
        controller.activityResumed(thirdToken, false)
        controller.reportSizeConfigurations(Binder(), null) // A late report for a removed token.

        // Death of the focused process exposes and resumes the stopped task below it.
        threadOne.transactions.clear()
        service.onProcessDied(threadTwo.asBinder())
        check(threadOne.transactions.single().transactionItems.last() is ResumeActivityItem)
        controller.activityResumed(firstToken, false)

        // Failed process bring-up removes its provisional activity/task record immediately.
        val beforeFailedLaunch = service.getTasks(10, false, true, Display.DEFAULT_DISPLAY).size
        var headlessLauncherCalls = 0
        activityManager.processLauncher = dev.artzig.system.HostedProcessStarts.Launcher { _, _ ->
            headlessLauncherCalls++
            object : dev.artzig.system.HostedProcessStarts.Child { override fun terminate() {} }
        }
        check(launch("MissingProcess") == ActivityManager.START_CANCELED)
        check(headlessLauncherCalls == 0) {
            "activity launch delegated an exact secondary-process target to the headless launcher"
        }
        activityManager.processLauncher = null
        check(service.getTasks(10, false, true, Display.DEFAULT_DISPLAY).size == beforeFailedLaunch)

        // One-shot getActivities launches the full creator stack and cancels before dispatch.
        val stackSender = checkNotNull(activityManager.getIntentSenderWithFeature(
            ActivityManager.INTENT_SENDER_ACTIVITY,
            callerPackage,
            null,
            null,
            null,
            91,
            arrayOf(
                Intent().setComponent(component("StackOne")),
                Intent().setComponent(component("StackTwo")),
            ),
            arrayOf(null, null),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
            null,
            UserHandle.USER_SYSTEM,
        ))
        check(activityManager.sendIntentSender(
            null, stackSender, null, 0, null, null, null, null, null,
        ) == ActivityManager.START_SUCCESS)
        val stackTask = service.getTasks(1, false, true, Display.DEFAULT_DISPLAY).single()
        check(stackTask.numActivities == 2 && stackTask.topActivity == component("StackTwo"))
        check(activityManager.sendIntentSender(
            null, stackSender, null, 0, null, null, null, null, null,
        ) == ActivityManager.START_CANCELED)

        // Key identity uses only the last intent/type. UPDATE_CURRENT replaces the stored stack
        // while retaining and updating the existing final request intent.
        val original = checkNotNull(activityManager.getIntentSenderWithFeature(
            ActivityManager.INTENT_SENDER_ACTIVITY,
            callerPackage,
            null,
            null,
            null,
            92,
            arrayOf(
                Intent("dev.artzig.OLD_PREFIX").setComponent(component("StackOne")),
                Intent("dev.artzig.LAST").setComponent(component("StackTwo"))
                    .putExtra("generation", 1),
            ),
            arrayOf(null, "type/last"),
            PendingIntent.FLAG_IMMUTABLE,
            null,
            UserHandle.USER_SYSTEM,
        ))
        val updated = checkNotNull(activityManager.getIntentSenderWithFeature(
            ActivityManager.INTENT_SENDER_ACTIVITY,
            callerPackage,
            null,
            null,
            null,
            92,
            arrayOf(
                Intent("dev.artzig.NEW_PREFIX").setComponent(component("StackOne")),
                Intent("dev.artzig.LAST").setComponent(component("StackTwo"))
                    .putExtra("generation", 2),
            ),
            arrayOf(null, "type/last"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            null,
            UserHandle.USER_SYSTEM,
        ))
        check(original === updated)
        check(runCatching {
            activityManager.getIntentForIntentSender(original)
        }.exceptionOrNull() is SecurityException)
        threadOne.transactions.clear()
        threadTwo.transactions.clear()
        check(activityManager.sendIntentSender(
            null, original, null, 0, null, null, null, null, null,
        ) == ActivityManager.START_SUCCESS)
        controller.activityPaused(firstToken)
        val updatedLaunchIntents = (threadOne.transactions + threadTwo.transactions)
            .flatMap { transaction -> transaction.transactionItems }
            .filterIsInstance<LaunchActivityItem>()
            .map { item ->
                LaunchActivityItem::class.java.getDeclaredField("mIntent").run {
                    isAccessible = true
                    get(item) as Intent
                }
            }
        check(updatedLaunchIntents.any { it.action == "dev.artzig.NEW_PREFIX" })
        check(updatedLaunchIntents.any { intent ->
            intent.action == "dev.artzig.LAST" && intent.getIntExtra("generation", -1) == 2
        })

        // A newer cross-process start supersedes an undispatched launch that is waiting for the
        // same resumed predecessor to acknowledge pause. Only the newest target may be enqueued.
        val raceActivityManager = HostedActivityManagerService(context).apply { packageService = packages }
        val raceService = HostedActivityTaskManagerService(
            context,
            raceActivityManager,
            packages,
            resolver,
        )
        raceActivityManager.activityTaskManager = raceService
        val raceThreadOne = CapturingApplicationThread()
        val raceThreadTwo = CapturingApplicationThread()
        raceActivityManager.noteProcessPackage(callingUid, callerPackage, processOne)
        raceActivityManager.attachApplication(raceThreadOne, 0)
        raceActivityManager.finishAttachApplication(0, 0)
        raceActivityManager.noteProcessPackage(callingUid, callerPackage, processTwo)
        raceActivityManager.attachApplication(raceThreadTwo, 0)
        raceActivityManager.finishAttachApplication(0, 0)
        fun raceLaunch(name: String): Int = raceService.startActivity(
            null,
            callerPackage,
            null,
            Intent().setComponent(component(name)),
            null,
            null,
            null,
            -1,
            0,
            null,
            null,
        )
        check(raceLaunch("RaceOne") == ActivityManager.START_SUCCESS)
        val raceFirstToken = launchToken(raceThreadOne.removeFirstTransaction())
        raceService.activityClientController.activityResumed(raceFirstToken, false)
        raceThreadOne.transactions.clear()
        raceThreadTwo.transactions.clear()
        check(raceLaunch("RaceBTwo") == ActivityManager.START_SUCCESS)
        check(raceThreadTwo.transactions.isEmpty())
        check(raceLaunch("RaceCTwo") == ActivityManager.START_SUCCESS)
        check(raceThreadTwo.transactions.isEmpty())
        raceService.activityClientController.activityPaused(raceFirstToken)
        val survivingLaunches = raceThreadTwo.transactions.filter { transaction ->
            transaction.transactionItems.any { it is LaunchActivityItem }
        }
        check(survivingLaunches.size == 1)
        val survivingItem = survivingLaunches.single().transactionItems
            .first { it is LaunchActivityItem } as LaunchActivityItem
        val launchedIntent = LaunchActivityItem::class.java.getDeclaredField("mIntent").run {
            isAccessible = true
            get(survivingItem) as Intent
        }
        check(launchedIntent.component == component("RaceCTwo"))
        check(raceService.getTasks(10, false, true, Display.DEFAULT_DISPLAY)
            .none { it.topActivity == component("RaceBTwo") })
        val raceController = raceService.activityClientController
        val raceTopToken = survivingItem.activityToken
        raceController.activityResumed(raceTopToken, false)
        raceController.activityStopped(raceFirstToken, null, null, null)
        val ownedTask = android.app.IAppTask.Stub.asInterface(raceService.getAppTasks(callerPackage).first())
        val ownedTaskId = ownedTask.taskInfo.taskId
        raceThreadOne.transactions.clear()
        raceThreadTwo.transactions.clear()
        ownedTask.moveToFront(raceThreadTwo, callerPackage)
        check(raceThreadOne.transactions.isEmpty() && raceThreadTwo.transactions.isEmpty()) {
            "moving the resumed task to front must not deliver onResume twice"
        }
        check(ownedTask.startActivity(raceThreadTwo.asBinder(), callerPackage, null,
            Intent().setComponent(component("AppTaskChildOne")), null, null) == ActivityManager.START_SUCCESS)
        check(raceThreadOne.transactions.isEmpty())
        check(raceThreadTwo.transactions.single().transactionItems.single() is PauseActivityItem)
        raceController.activityPaused(raceTopToken)
        val appTaskChild = launchToken(raceThreadOne.removeFirstTransaction())
        check(ownedTask.taskInfo.taskId == ownedTaskId && ownedTask.taskInfo.numActivities == 2)
        raceController.activityResumed(appTaskChild, false)
        raceController.activityStopped(raceTopToken, null, null, null)
        raceThreadOne.transactions.clear()
        raceThreadTwo.transactions.clear()
        ownedTask.finishAndRemoveTask()
        check(raceThreadOne.transactions.single().transactionItems.single() is PauseActivityItem)
        check(raceService.getAppTasks(callerPackage).none {
            android.app.IAppTask.Stub.asInterface(it).taskInfo.taskId == ownedTaskId
        })
        raceController.activityPaused(appTaskChild)
        raceController.activityResumed(raceFirstToken, false)
        check((raceThreadOne.transactions + raceThreadTwo.transactions).flatMap { it.transactionItems }
            .filterIsInstance<DestroyActivityItem>().size == 2)
        raceController.activityDestroyed(appTaskChild)
        raceController.activityDestroyed(raceTopToken)
        check(runCatching { ownedTask.taskInfo }.exceptionOrNull() is IllegalArgumentException)

        // A child can be started from onCreate/onResume before the parent's oneway resume
        // report arrives. That parent is a live result recipient, not a superseded launch.
        raceThreadOne.transactions.clear()
        raceThreadTwo.transactions.clear()
        check(raceLaunch("ReentrantParentOne") == ActivityManager.START_SUCCESS)
        val reentrantParent = raceThreadOne.transactions.flatMap { it.transactionItems }
            .filterIsInstance<LaunchActivityItem>().single().activityToken
        raceController.activityPaused(raceFirstToken)
        raceThreadOne.transactions.clear()
        raceThreadTwo.transactions.clear()
        check(raceService.startActivity(null, callerPackage, null,
            Intent().setComponent(component("ReentrantChildTwo")), null,
            reentrantParent, null, 99, 0, null, null) == ActivityManager.START_SUCCESS)
        val reentrantChild = raceThreadTwo.transactions.flatMap { it.transactionItems }
            .filterIsInstance<LaunchActivityItem>().single().activityToken
        raceController.activityResumed(reentrantParent, false)
        check((raceThreadOne.transactions + raceThreadTwo.transactions).flatMap { it.transactionItems }
            .none { it is DestroyActivityItem && it.activityToken == reentrantParent }) {
            "late initial resume report destroyed an activity that had already launched its child"
        }
        raceController.activityStopped(reentrantParent, null, null, null)
        raceController.activityResumed(reentrantChild, false)
        check(raceService.getTasks(10, false, true, Display.DEFAULT_DISPLAY)
            .single { it.topActivity == component("ReentrantChildTwo") }.numActivities == 2)
        raceThreadOne.transactions.clear()
        raceThreadTwo.transactions.clear()
        check(raceController.finishActivity(reentrantChild, Activity.RESULT_OK,
            Intent("dev.artzig.REENTRANT_RESULT"), 0))
        raceController.activityPaused(reentrantChild)
        val reentrantResult = raceThreadOne.transactions.single().transactionItems
        check(reentrantResult.first() is ActivityResultItem && reentrantResult.last() is ResumeActivityItem)
        raceController.activityResumed(reentrantParent, false)
        check(raceThreadTwo.transactions.last().transactionItems.single() is DestroyActivityItem)
        raceController.activityDestroyed(reentrantChild)

        // An external start may arrive after dispatch but before the target's oneway
        // resume acknowledgement. Preserve that activity and background it normally.
        raceThreadOne.transactions.clear()
        raceThreadTwo.transactions.clear()
        check(raceLaunch("DispatchedTwo") == ActivityManager.START_SUCCESS)
        raceController.activityPaused(reentrantParent)
        val dispatched = raceThreadTwo.transactions.flatMap { it.transactionItems }
            .filterIsInstance<LaunchActivityItem>().single().activityToken
        raceThreadOne.transactions.clear()
        raceThreadTwo.transactions.clear()
        check(raceLaunch("LaterOne") == ActivityManager.START_SUCCESS)
        raceController.activityResumed(dispatched, false)
        check((raceThreadOne.transactions + raceThreadTwo.transactions).flatMap { it.transactionItems }
            .none { it is DestroyActivityItem && it.activityToken == dispatched })
        check(raceService.getTasks(20, false, true, Display.DEFAULT_DISPLAY)
            .any { it.topActivity == component("DispatchedTwo") })
        check(raceThreadTwo.transactions.flatMap { it.transactionItems }.any { it is StopActivityItem })
    }

    private fun testHostedTaskReuse() {
        val context = HostContexts.systemContext(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 320, 240, 120, 0)
        val packages = HostedPackageManagerService().apply {
            installPlatform(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")))
        }
        val pkg = checkNotNull(packages.installPackage(checkNotNull(permissionsFixtureApk)))
        val uid = Binder.getCallingUid()
        packages.bindPackageUid(pkg, uid)
        com.android.server.uri.StockUriPermissionSmoke.withService(packages) {
            val am = HostedActivityManagerService(context).apply { packageService = packages }
            val infos = HashMap<String, ActivityInfo>()
            fun component(name: String) = ComponentName(pkg, "dev.artzig.$name")
            fun define(name: String, affinity: String? = "shared", mode: Int = ActivityInfo.LAUNCH_MULTIPLE,
                       document: Int = ActivityInfo.DOCUMENT_LAUNCH_NONE, target: String? = null) {
                infos[component(name).className] = ActivityInfo().apply {
                    packageName = pkg
                    this.name = component(name).className
                    processName = if (name.endsWith("Two")) "task-reuse:two" else "task-reuse:one"
                    exported = true
                    taskAffinity = affinity
                    launchMode = mode
                    documentLaunchMode = document
                    targetActivity = target?.let { component(it).className }
                    applicationInfo = ApplicationInfo().apply { packageName = pkg; this.uid = uid }
                }
            }
            define("RootOne")
            define("ChildOne")
            define("OtherTwo", "other")
            define("CandidateTwo")
            define("AliasOne", target = "RootOne")
            define("NoneOne", null)
            define("NoneTwo", null)
            define("SingleOne", mode = ActivityInfo.LAUNCH_SINGLE_TASK)
            define("DocumentOne", document = ActivityInfo.DOCUMENT_LAUNCH_INTO_EXISTING)
            define("AlwaysOne", document = ActivityInfo.DOCUMENT_LAUNCH_ALWAYS)
            val service = HostedActivityTaskManagerService(context, am, packages) { intent, _ ->
                intent.component?.className?.let(infos::get)
            }
            am.activityTaskManager = service
            val threads = listOf(CapturingApplicationThread(), CapturingApplicationThread())
            for ((thread, process) in threads.zip(listOf("task-reuse:one", "task-reuse:two"))) {
                am.noteProcessPackage(uid, pkg, process)
                am.attachApplication(thread, 0)
                am.finishAttachApplication(0, 0)
            }
            val controller = service.activityClientController
            val observed = ArrayList<android.app.servertransaction.ClientTransactionItem>()
            fun queued() = threads.flatMap { it.transactions }.flatMap { it.transactionItems }
            fun drain() {
                var count = 0
                while (threads.any { it.transactions.isNotEmpty() }) {
                    check(++count < 100) { "task lifecycle failed to settle" }
                    for (thread in threads) {
                        if (thread.transactions.isEmpty()) continue
                        for (item in thread.removeFirstTransaction().transactionItems) {
                            observed += item
                            when (item) {
                                is LaunchActivityItem -> controller.activityResumed(item.activityToken, false)
                                is ResumeActivityItem -> controller.activityResumed(item.activityToken, false)
                                is PauseActivityItem -> controller.activityPaused(item.activityToken)
                                is StopActivityItem -> controller.activityStopped(item.activityToken, null, null, null)
                                is DestroyActivityItem -> controller.activityDestroyed(item.activityToken)
                            }
                        }
                    }
                }
            }
            fun intent(name: String, flags: Int = 0, action: String = Intent.ACTION_VIEW, data: String? = null) =
                Intent(action).setComponent(component(name)).setFlags(flags).apply { if (data != null) this.data = Uri.parse(data) }
            fun start(value: Intent, parent: android.os.IBinder? = null, request: Int = -1): Int =
                service.startActivity(null, pkg, null, value, null, parent, null, request, 0, null, null)
            fun launch(value: Intent, parent: android.os.IBinder? = null, request: Int = -1): android.os.IBinder {
                observed.clear()
                check(start(value, parent, request) == ActivityManager.START_SUCCESS)
                drain()
                return observed.filterIsInstance<LaunchActivityItem>().single().activityToken
            }
            fun top() = service.getTasks(1, false, true, Display.DEFAULT_DISPLAY).single()
            fun taskId(token: android.os.IBinder) = controller.getTaskForActivity(token, false)
            val newTask = Intent.FLAG_ACTIVITY_NEW_TASK
            val multiple = newTask or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            val root = launch(intent("RootOne", newTask))
            val rootTask = taskId(root)
            val child = launch(intent("ChildOne"), root)
            check(taskId(child) == rootTask && top().numActivities == 2)
            val other = launch(intent("OtherTwo", newTask))
            observed.clear()
            check(start(intent("RootOne", newTask).putExtra("ignored-for-task-identity", 42)) == ActivityManager.START_TASK_TO_FRONT)
            check(queued().single() is PauseActivityItem) { "task resume escaped the predecessor pause gate" }
            check((queued().single() as PauseActivityItem).activityToken == other)
            drain()
            check(top().taskId == rootTask && top().topActivity == component("ChildOne") && top().numActivities == 2)
            check(observed.none { it is LaunchActivityItem || it is android.app.servertransaction.NewIntentItem })
            check(observed.filterIsInstance<ResumeActivityItem>().single().activityToken == child)
            observed.clear()
            check(start(intent("RootOne", newTask)) == ActivityManager.START_DELIVERED_TO_TOP)
            drain()
            check(observed.isEmpty()) { "repeated front-door launch disturbed an already foreground task" }
            val changed = launch(intent("RootOne", newTask, "dev.artzig.DIFFERENT"))
            check(taskId(changed) == rootTask && top().numActivities == 3)
            val candidate = launch(intent("CandidateTwo", multiple))
            observed.clear()
            check(start(intent("RootOne", newTask)) == ActivityManager.START_TASK_TO_FRONT)
            drain()
            check(top().taskId == rootTask && observed.none { it is LaunchActivityItem }) { "affinity outranked an exact root match" }
            val distinct = launch(intent("RootOne", multiple))
            check(taskId(distinct) != rootTask && taskId(distinct) != taskId(candidate))
            observed.clear()
            check(start(intent("AliasOne", newTask).setPackage(pkg)) == ActivityManager.START_DELIVERED_TO_TOP)
            drain()
            check(top().taskId == taskId(distinct) && observed.isEmpty()) { "alias/package normalization failed" }
            val noneOne = launch(intent("NoneOne", newTask))
            val noneTwo = launch(intent("NoneTwo", newTask))
            check(taskId(noneOne) != taskId(noneTwo)) { "null task affinities matched" }

            // SINGLE_TOP in a background task must pause the foreground process, deliver, then resume.
            observed.clear()
            check(start(intent("RootOne", newTask or Intent.FLAG_ACTIVITY_SINGLE_TOP)) == ActivityManager.START_TASK_TO_FRONT)
            check(queued().single() is PauseActivityItem)
            drain()
            val delivery = observed.indexOfFirst { it is android.app.servertransaction.NewIntentItem }
            val resume = observed.indexOfFirst { it is ResumeActivityItem }
            check(delivery >= 0 && resume > delivery && observed.none { it is LaunchActivityItem })
            check(top().taskId == taskId(distinct))

            // Standard CLEAR_TOP recreates its matching activity; SINGLE_TOP retains it.
            val cover = launch(intent("ChildOne"), distinct)
            val retainedTask = taskId(cover)
            observed.clear()
            check(start(intent("RootOne", Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), cover) == ActivityManager.START_DELIVERED_TO_TOP)
            drain()
            check(observed.none { it is LaunchActivityItem } && top().numActivities == 1 && top().taskId == retainedTask)
            check(observed.filterIsInstance<DestroyActivityItem>().single().activityToken == cover)
            val recreated = launch(intent("RootOne", Intent.FLAG_ACTIVITY_CLEAR_TOP), distinct)
            check(recreated !== distinct && top().taskId == retainedTask && top().numActivities == 1)
            check(observed.filterIsInstance<DestroyActivityItem>().single().activityToken == distinct)
            val replacement = launch(intent("RootOne", newTask or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            check(taskId(replacement) == retainedTask && top().numActivities == 1)
            check(observed.filterIsInstance<DestroyActivityItem>().single().activityToken == recreated)

            val single = launch(intent("SingleOne", newTask))
            val singleChild = launch(intent("ChildOne"), single)
            observed.clear()
            check(start(intent("SingleOne", newTask)) == ActivityManager.START_DELIVERED_TO_TOP)
            drain()
            check(top().taskId == taskId(single) && observed.none { it is LaunchActivityItem })
            check(observed.filterIsInstance<DestroyActivityItem>().single().activityToken == singleChild)

            val reorderRoot = launch(intent("RootOne", multiple))
            val reorderChild = launch(intent("ChildOne"), reorderRoot)
            val reorderCover = launch(intent("OtherTwo"), reorderChild)
            observed.clear()
            check(start(intent("ChildOne", Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), reorderCover) == ActivityManager.START_DELIVERED_TO_TOP)
            drain()
            check(top().taskId == taskId(reorderRoot) && top().numActivities == 3 && top().topActivity == component("ChildOne"))
            check(observed.none { it is LaunchActivityItem || it is DestroyActivityItem })
            check(observed.filterIsInstance<android.app.servertransaction.NewIntentItem>().single().activityToken == reorderChild)

            val doc = launch(intent("DocumentOne", data = "content://documents/one"))
            val docChild = launch(intent("ChildOne"), doc)
            val otherDoc = launch(intent("DocumentOne", data = "content://documents/two"))
            check(taskId(doc) != taskId(otherDoc))
            observed.clear()
            check(start(intent("DocumentOne", data = "content://documents/one")) == ActivityManager.START_TASK_TO_FRONT)
            drain()
            check(top().taskId == taskId(doc) && top().numActivities == 1 && observed.none { it is LaunchActivityItem })
            check(observed.filterIsInstance<DestroyActivityItem>().single().activityToken == docChild)
            val always = launch(intent("AlwaysOne", data = "content://documents/always"))
            val alwaysAgain = launch(intent("AlwaysOne", data = "content://documents/always"))
            check(taskId(always) != taskId(alwaysAgain))
            // Affinity finishing removes a contiguous downward range, preserving both
            // a different-affinity boundary and activities above the caller.
            val affinityRoot = launch(intent("RootOne", multiple))
            val boundary = launch(intent("OtherTwo"), affinityRoot)
            val middle = launch(intent("RootOne"), boundary, 51)
            val affinityCaller = launch(intent("ChildOne"), middle, 52)
            val affinityCover = launch(intent("OtherTwo"), affinityCaller)
            val affinityTask = taskId(affinityRoot)
            observed.clear()
            check(controller.finishActivityAffinity(affinityCaller))
            drain()
            check(observed.filterIsInstance<DestroyActivityItem>().map { it.activityToken }.toSet() ==
                setOf(middle, affinityCaller))
            check(observed.none { it is ResumeActivityItem || it is PauseActivityItem || it is ActivityResultItem })
            check(top().taskId == affinityTask && top().numActivities == 3)
            check(taskId(affinityRoot) == affinityTask && taskId(boundary) == affinityTask &&
                taskId(affinityCover) == affinityTask && taskId(middle) == -1 && taskId(affinityCaller) == -1)
            observed.clear()
            check(controller.finishActivity(affinityCover, Activity.RESULT_CANCELED, null,
                Activity.DONT_FINISH_TASK_WITH_ACTIVITY))
            drain()
            val resultIndex = observed.indexOfFirst { it is ActivityResultItem }
            check(resultIndex >= 0 && observed.indexOfFirst { it is ResumeActivityItem } > resultIndex)
            check((observed[resultIndex] as ActivityResultItem).activityToken == boundary)
            val parcel = Parcel.obtain()
            try {
                observed[resultIndex].writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                check(parcel.readStrongBinder() == boundary)
                val results = checkNotNull(parcel.createTypedArrayList(ResultInfo.CREATOR))
                check(results.size == 1 && results.single().mRequestCode == 51 &&
                    results.single().mResultCode == Activity.RESULT_CANCELED)
            } finally { parcel.recycle() }
            check(top().numActivities == 2 && top().topActivity == component("OtherTwo"))
            check(!controller.finishActivityAffinity(Binder()))
            check(controller.finishActivityAffinity(boundary))
            drain()
            check(top().numActivities == 1 && taskId(affinityRoot) == affinityTask)

            val nullRoot = launch(intent("NoneOne", multiple))
            val nullChild = launch(intent("NoneTwo"), nullRoot)
            check(controller.finishActivityAffinity(nullChild))
            drain()
            check(taskId(nullRoot) == -1 && taskId(nullChild) == -1)
            check(taskId(affinityRoot) == affinityTask)
            System.err.println("stock-affinity-finish: bounded range, null affinity, preserved top/boundary and canceled-result ordering passed")
            System.err.println("stock-task-reuse: identity/affinity, front-door preservation, single-top, task clearing, single-task and documents passed")
        }
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
        val inputIndices = ArrayList<Int>()
        val callbackHandler = Handler(checkNotNull(Looper.getMainLooper()))
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_MPEG)
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) { inputIndices.add(index) }
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
            // Media3's asynchronous seek sequence. Observe buffer ownership/callbacks,
            // independently of the native producer thread or Java Handler message count.
            repeat(20) {
                codec.start()
                codec.flush()
            }
            Thread.sleep(20)
            drainMainQueue()
            inputIndices.clear()
            codec.start()
            val deadline = System.nanoTime() + 2_000_000_000L
            while (inputIndices.isEmpty() && System.nanoTime() < deadline) {
                drainMainQueue(); Thread.sleep(2)
            }
            Thread.sleep(20); drainMainQueue()
            check(inputIndices.isNotEmpty() && inputIndices.distinct().size == inputIndices.size) { inputIndices }
            codec.flush()
            Thread.sleep(20); drainMainQueue(); inputIndices.clear()
            Thread.sleep(20); drainMainQueue()
            check(inputIndices.isEmpty()) { "Flushed codec continued requesting buffers: $inputIndices" }
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
        // Stock WebChromeClient supplies default denial/cancellation and legacy
        // callback forwarding, which the former narrow stub did not expose.
        val chrome = android.webkit.WebChromeClient()
        var denied = 0
        val permissionRequest = object : android.webkit.PermissionRequest() {
            override fun getOrigin(): Uri = Uri.parse("https://video.example")
            override fun getResources(): Array<String> = arrayOf(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            override fun grant(resources: Array<String>) { error("Default client granted web permission") }
            override fun deny() { denied++ }
        }
        chrome.onPermissionRequest(permissionRequest)
        check(denied == 1)
        chrome.onPermissionRequestCanceled(permissionRequest)
        check(denied == 1)
        var quota = -1L
        chrome.onExceededDatabaseQuota("https://video.example", "db", 17, 99, 200) { quota = it }
        check(quota == 17L)
        chrome.onReachedMaxAppCacheSize(99, 23) { quota = it }
        check(quota == 23L)
        var selected = 0
        chrome.openFileChooser({ value -> check(value == null); selected++ }, "image/*", "camera")
        check(selected == 1)
        var legacyConsole: String? = null
        val legacy = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(message: String, lineNumber: Int, sourceID: String) {
                legacyConsole = "$message:$lineNumber:$sourceID"
            }
        }
        check(!legacy.onConsoleMessage(android.webkit.ConsoleMessage("message", "script.js", 42,
            android.webkit.ConsoleMessage.MessageLevel.LOG)))
        check(legacyConsole == "message:42:script.js")
        var completed: android.webkit.JsResult? = null
        val prompt = android.webkit.JsPromptResult { completed = it }
        check(!chrome.onJsPrompt(null, "https://video.example", "Name", "default", prompt))
        check(completed == null) // The provider must show its default dialog.
        prompt.confirm("answer")
        check(completed === prompt && prompt.result && prompt.stringResult == "answer")
        val cancelled = android.webkit.JsPromptResult { completed = it }
        cancelled.cancel()
        check(completed === cancelled && !cancelled.result && cancelled.stringResult == null)

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

    }

    private fun testAlertDialogContracts() {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            320,
            240,
            120,
            0,
        )
        var accepted = false
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle("Stock dialog")
            .setMessage("AOSP AlertController content")
            .setPositiveButton("Accept") { _, which ->
                check(which == android.app.AlertDialog.BUTTON_POSITIVE)
                accepted = true
            }
            .create()

        dialog.create()
        val button = checkNotNull(dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE))
        check(button.text.toString() == "Accept")
        button.performClick()
        drainMainQueue()
        check(accepted) { "stock AlertDialog did not dispatch its positive button callback" }
    }

    private fun testDialogLifecycleAndStateContracts() {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            320,
            240,
            120,
            0,
        )
        val listenerEvents = mutableListOf<String>()
        val dialog = RecordingDialog(
            context,
            android.R.style.Theme_DeviceDefault_Dialog_NoActionBar,
        )
        dialog.setOnShowListener { listenerEvents += "show" }
        dialog.setOnCancelListener { listenerEvents += "cancel" }
        dialog.setOnDismissListener { listenerEvents += "dismiss" }
        try {
            dialog.show()
            check(dialog.isShowing)
            check(dialog.lifecycleEvents == listOf("create", "start"))
            check(listenerEvents.isEmpty()) { "Dialog show listener ran synchronously" }
            runHostedFrame()
            check(listenerEvents == listOf("show")) { "Dialog show events: $listenerEvents" }

            dialog.dismiss()
            check(!dialog.isShowing)
            check(dialog.lifecycleEvents == listOf("create", "start", "stop"))
            check(listenerEvents == listOf("show")) { "Dialog dismiss listener ran synchronously" }
            runHostedFrame()
            check(listenerEvents == listOf("show", "dismiss")) {
                "Dialog dismiss events: $listenerEvents"
            }

            dialog.show()
            runHostedFrame()
            check(dialog.lifecycleEvents == listOf("create", "start", "stop", "start")) {
                "Dialog recreated on reshow: ${dialog.lifecycleEvents}"
            }
            dialog.cancel()
            check(!dialog.isShowing)
            runHostedFrame()
            check(listenerEvents.takeLast(3) == listOf("show", "cancel", "dismiss")) {
                "Dialog cancel listener order: $listenerEvents"
            }

            listenerEvents.clear()
            dialog.show()
            runHostedFrame()
            listenerEvents.clear()
            dialog.setCancelable(false)
            dialog.onBackPressed()
            runHostedFrame()
            check(dialog.isShowing) { "non-cancelable Dialog was dismissed by back" }
            check(listenerEvents.isEmpty())
            dialog.setCancelable(true)
            dialog.onBackPressed()
            check(!dialog.isShowing)
            runHostedFrame()
            check(listenerEvents == listOf("cancel", "dismiss"))

            val stateSource = RecordingDialog(
                context,
                android.R.style.Theme_DeviceDefault_Dialog_NoActionBar,
            )
            stateSource.show()
            runHostedFrame()
            stateSource.input.setText("preserved text")
            val savedState = stateSource.onSaveInstanceState()
            stateSource.dismiss()
            runHostedFrame()

            val restored = RecordingDialog(
                context,
                android.R.style.Theme_DeviceDefault_Dialog_NoActionBar,
            )
            restored.onRestoreInstanceState(savedState)
            runHostedFrame()
            check(restored.isShowing)
            check(restored.input.text.toString() == "preserved text")
            check(restored.lifecycleEvents == listOf("create:saved", "start")) {
                "Dialog restore lifecycle: ${restored.lifecycleEvents}"
            }
            restored.dismiss()
            runHostedFrame()

            val defaultDialog = android.app.Dialog(context)
            val floating = android.util.TypedValue()
            check(defaultDialog.context.theme.resolveAttribute(
                android.R.attr.windowIsFloating,
                floating,
                true,
            ) && floating.data != 0) { "default Dialog context did not resolve a floating theme" }

            val actionBarDialog = RecordingDialog(context, android.R.style.Theme_DeviceDefault)
            actionBarDialog.show()
            runHostedFrame()
            checkNotNull(actionBarDialog.actionBar) {
                "action-bar Dialog did not construct WindowDecorActionBar"
            }
            actionBarDialog.dismiss()
            runHostedFrame()

            val applicationInfo = context.applicationInfo
            val predictiveBackWasEnabled = applicationInfo.isOnBackInvokedCallbackEnabled
            val activityActiveField = ApkHost::class.java.getDeclaredField("sActivityActive")
            activityActiveField.isAccessible = true
            val activityWasActive = activityActiveField.getBoolean(null)
            val activityRootField = ApkHost::class.java.getDeclaredField("sRoot")
            activityRootField.isAccessible = true
            val activityRootWas = activityRootField.get(null)
            val physicalBackField = ApkHost::class.java.getDeclaredField("sPhysicalBackRoute")
            physicalBackField.isAccessible = true
            activityActiveField.setBoolean(null, true)
            try {
                applicationInfo.setEnableOnBackInvokedCallback(false)
                val legacyDialog = RecordingDialog(
                    context,
                    android.R.style.Theme_DeviceDefault_Dialog_NoActionBar,
                )
                legacyDialog.setCancelable(false)
                legacyDialog.show()
                runHostedFrame()
                activityRootField.set(
                    null,
                    checkNotNull(dev.artzig.HostedWindowStack.topFocusable()).root,
                )
                ApkHost.physicalKey(0, 158, 0, 0)
                ApkHost.physicalKey(1, 158, 0, 0)
                runHostedFrame()
                check(legacyDialog.isShowing) {
                    "legacy physical Back dismissed a non-cancelable Dialog"
                }
                legacyDialog.setCancelable(true)
                ApkHost.physicalKey(0, 158, 0, 0)
                ApkHost.physicalKey(1, 158, 0, 0)
                check(!legacyDialog.isShowing) {
                    "legacy physical Back did not dismiss a cancelable Dialog"
                }
                runHostedFrame()

                applicationInfo.setEnableOnBackInvokedCallback(true)
                fun showPredictiveDialog(): RecordingDialog {
                    val predictiveDialog = RecordingDialog(
                        context,
                        android.R.style.Theme_DeviceDefault_Dialog_NoActionBar,
                    )
                    predictiveDialog.show()
                    runHostedFrame()
                    val predictiveWindow = checkNotNull(
                        dev.artzig.HostedWindowStack.topFocusable(),
                    )
                    checkNotNull(predictiveWindow.root.onBackInvokedDispatcher.topCallback) {
                        "predictive Dialog callback did not reach the hosted ViewRoot"
                    }
                    return predictiveDialog
                }

                repeat(2) {
                    val predictiveDialog = showPredictiveDialog()
                    ApkHost.physicalKey(0, 158, 0, 0)
                    check(predictiveDialog.isShowing) {
                        "predictive physical Back fired before key release"
                    }
                    ApkHost.physicalKey(1, 158, 0, 0)
                    check(!predictiveDialog.isShowing) {
                        "predictive physical Back did not dismiss Dialog"
                    }
                    runHostedFrame()
                }

                var canceledAfterDismiss = false
                val dismissedDuringBack = showPredictiveDialog()
                dismissedDuringBack.setOnCancelListener { canceledAfterDismiss = true }
                ApkHost.physicalKey(0, 158, 0, 0)
                dismissedDuringBack.dismiss()
                runHostedFrame()
                ApkHost.physicalKey(1, 158, 0, 0)
                runHostedFrame()
                check(!canceledAfterDismiss) {
                    "stale predictive Back callback canceled an already-dismissed Dialog"
                }

                var sharedBackInvocations = 0
                val sharedBackCallback = android.window.OnBackInvokedCallback {
                    sharedBackInvocations++
                }
                val lowerDialog = showPredictiveDialog()
                lowerDialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                    sharedBackCallback,
                )
                val lowerRoot = checkNotNull(dev.artzig.HostedWindowStack.topFocusable()).root
                val upperDialog = showPredictiveDialog()
                upperDialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                    sharedBackCallback,
                )
                val upperRoot = checkNotNull(dev.artzig.HostedWindowStack.topFocusable()).root
                check(upperRoot !== lowerRoot)
                ApkHost.physicalKey(0, 158, 0, 0)
                upperDialog.dismiss()
                runHostedFrame()
                check(checkNotNull(dev.artzig.HostedWindowStack.topFocusable()).root === lowerRoot)
                ApkHost.physicalKey(1, 158, 0, 0)
                check(sharedBackInvocations == 0) {
                    "predictive Back crossed from its captured window to a replacement"
                }
                check(lowerDialog.isShowing)
                lowerDialog.dismiss()
                runHostedFrame()

                val resetDialog = showPredictiveDialog()
                ApkHost.physicalKey(0, 158, 0, 0)
                check(physicalBackField.get(null) != null) {
                    "predictive physical Back route was not captured"
                }
                ApkHost.inputReset(1)
                ApkHost.physicalKey(1, 158, 0, 0)
                check(physicalBackField.get(null) == null) {
                    "keyboard reset retained predictive physical Back route"
                }
                check(resetDialog.isShowing) {
                    "keyboard reset invoked predictive physical Back"
                }
                resetDialog.dismiss()
                runHostedFrame()

                repeat(2) {
                    val syntheticDialog = showPredictiveDialog()
                    ApkHost.key(0, android.view.KeyEvent.KEYCODE_BACK, 0)
                    check(syntheticDialog.isShowing) {
                        "predictive synthetic Back fired before key release"
                    }
                    ApkHost.key(1, android.view.KeyEvent.KEYCODE_BACK, 0)
                    check(!syntheticDialog.isShowing) {
                        "predictive synthetic Back did not dismiss Dialog"
                    }
                    runHostedFrame()
                }
            } finally {
                activityRootField.set(null, activityRootWas)
                activityActiveField.setBoolean(null, activityWasActive)
                applicationInfo.setEnableOnBackInvokedCallback(predictiveBackWasEnabled)
            }
        } finally {
            if (dialog.isShowing) dialog.dismiss()
            drainMainQueue()
            dev.artzig.HostedWindowStack.clear()
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

    private fun testNetworkServiceDiscovery() { StockNsdSmoke.run() }

    private fun testSurfaceViewLifecycle() {
        ArtzigVideoLayer.setNativeCallsEnabledForTests(false)
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            200,
            200,
            120,
            0,
        )
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
            awaitChoreographerFrame()
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
        awaitChoreographerFrame()
        check(floodCallbacks == 100) {
            "stock frame dispatch lost callbacks: $floodCallbacks"
        }

        val observed = mutableListOf<String>()
        choreographer.postCallback(Choreographer.CALLBACK_COMMIT, { observed += "commit" }, null)
        choreographer.postCallback(Choreographer.CALLBACK_TRAVERSAL, { observed += "traversal" }, null)
        choreographer.postCallback(Choreographer.CALLBACK_INSETS_ANIMATION, { observed += "insets" }, null)
        choreographer.postCallback(Choreographer.CALLBACK_INPUT, { observed += "input" }, null)
        choreographer.postCallback(Choreographer.CALLBACK_ANIMATION, { observed += "animation" }, null)
        awaitChoreographerFrame()
        check(observed == listOf("input", "animation", "insets", "traversal", "commit")) {
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

    private fun awaitChoreographerFrame() = StockFrameTestDriver.awaitFrame(::drainMainQueue)

    private fun runHostedFrame() {
        drainMainQueue()
        awaitChoreographerFrame()
        drainMainQueue()
    }

    private fun settleActivityFrames() = StockFrameTestDriver.settle(::drainMainQueue)

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

    private fun testSimpleRendererContracts() {
        val context = HostContexts.systemContext(System.getenv("ARTZIG_FRAMEWORK_RES"), 200, 200, 120, 0)
        val renderer = android.view.ThreadedRenderer.SimpleRenderer(context, "stock-simple-renderer", null)
        try {
            val root = renderer.rootNode
            root.setPosition(0, 0, 16, 16)
            root.beginRecording(16, 16).drawColor(android.graphics.Color.RED)
            root.endRecording()
            check(root.hasDisplayList())
            check(renderer.createRenderRequest() != null)
            renderer.setLightCenter(context.getSystemService(android.hardware.display.DisplayManager::class.java)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY), 0, 0)
        } finally {
            renderer.destroy()
        }
    }

    private fun testAccessibilityVirtualNodes() {
        val context = HostContexts.systemContext(System.getenv("ARTZIG_FRAMEWORK_RES"), 200, 200, 120, 0)
        val root = ViewRootImpl(context)
        var actionNode = 0
        lateinit var host: View
        val provider = object : android.view.accessibility.AccessibilityNodeProvider() {
            override fun createAccessibilityNodeInfo(id: Int): android.view.accessibility.AccessibilityNodeInfo {
                return android.view.accessibility.AccessibilityNodeInfo.obtain().apply {
                    setSource(host, id)
                    className = "android.widget.EditText"
                    isVisibleToUser = true
                    isEnabled = true
                    if (id == HOST_VIEW_ID) {
                        addChild(host, 7)
                    } else {
                        isPassword = true
                        text = "private password"
                        contentDescription = "private description"
                        hintText = "private hint"
                        addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                        addChild(host, 7) // A broken provider must not make inspection loop forever.
                    }
                }
            }
            override fun performAction(id: Int, action: Int, arguments: Bundle?): Boolean {
                actionNode = id
                return action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
            }
        }
        host = object : View(context) {
            override fun getAccessibilityNodeProvider() = provider
        }
        try {
            root.setView(host, 200, 200)
            ArtzigAccessibilityBridge.setActivityRoot(root)
            val snapshot = org.json.JSONObject(ArtzigAccessibilityBridge.snapshot())
            val nodes = snapshot.getJSONArray("nodes")
            check(nodes.length() == 2 && !snapshot.getBoolean("truncated"))
            val child = nodes.getJSONObject(1)
            check(child.getBoolean("virtual") && child.getInt("virtual_id") == 7)
            check(child.isNull("text") && child.isNull("content_description") && child.isNull("hint"))
            check(ArtzigAccessibilityBridge.perform(snapshot.getLong("revision"), child.getLong("window_id"),
                child.getInt("view_id"), 7, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null))
            check(actionNode == 7)
        } finally {
            root.destroy()
            ArtzigAccessibilityBridge.setActivityRoot(null)
            drainMainQueue()
        }
    }

    private fun testAccessibilityInspectorContracts() {
        val context = HostContexts.systemContext(System.getenv("ARTZIG_FRAMEWORK_RES"), 200, 200, 120, 0)
        val root = ViewRootImpl(context)
        val button = android.widget.Button(context).apply {
            layoutParams = WindowManager.LayoutParams(200, 200)
            setAllCaps(false)
            text = "first"
        }
        var clicks = 0
        button.setOnClickListener { clicks++ }
        try {
            root.setView(button, 200, 200)
            ArtzigAccessibilityBridge.setActivityRoot(root)
            check(!ArtzigAccessibilityBridge.isIdle())
            awaitChoreographerFrame()
            drainMainQueue()
            root.doTraversal()
            drainMainQueue()
            root.doTraversal()
            check(ArtzigAccessibilityBridge.isIdle())
            val snapshot = org.json.JSONObject(ArtzigAccessibilityBridge.snapshot())
            val revision = snapshot.getLong("revision")
            val window = snapshot.getJSONArray("windows").getJSONObject(0).getLong("id")
            check(snapshot.getJSONArray("nodes").getJSONObject(0).getString("text") == "first") { snapshot.toString() }
            button.text = "second"
            check(!ArtzigAccessibilityBridge.perform(revision, window, button.accessibilityViewId,
                android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID,
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null))
            check(clicks == 0) { "an unobserved view mutation must invalidate a saved action" }
            val fresh = ArtzigAccessibilityBridge.revision()
            check(ArtzigAccessibilityBridge.perform(fresh, window, button.accessibilityViewId,
                android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID,
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null))
            check(clicks == 1)
            val eventMark = ArtzigAccessibilityBridge.revision()
            root.notifySubtreeAccessibilityStateChanged(button, button,
                android.view.accessibility.AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT)
            button.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
            val journal = org.json.JSONObject(ArtzigAccessibilityBridge.diagnostics(eventMark)).getJSONArray("events")
            val reasons = (0 until journal.length()).map { journal.getJSONObject(it).getString("reason") }
            check(reasons.indexOf("event:2048") in 0 until reasons.indexOf("event:1")) {
                "pending content changes must be sent before the click event: $reasons"
            }
            val drawMark = ArtzigAccessibilityBridge.revision()
            val beforeDraw = org.json.JSONObject(ArtzigAccessibilityBridge.snapshot()).getJSONArray("nodes").toString()
            root.invalidate()
            root.doTraversal()
            check(ArtzigAccessibilityBridge.revision() > drawMark) { "draw-only activity must reset the idle cursor" }
            check(org.json.JSONObject(ArtzigAccessibilityBridge.snapshot()).getJSONArray("nodes").toString() == beforeDraw)
            check(ArtzigAccessibilityBridge.perform(drawMark, window, button.accessibilityViewId,
                android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID,
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null)) {
                "A paint-only redraw invalidated an unchanged accessibility target"
            }
            check(clicks == 2)
            check(!ArtzigAccessibilityBridge.perform(drawMark, window, button.accessibilityViewId,
                android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID,
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null))
            repeat(140) { ArtzigAccessibilityBridge.markChanged("bounded:$it") }
            val bounded = org.json.JSONObject(ArtzigAccessibilityBridge.diagnostics(0))
            check(bounded.getJSONArray("events").length() == 128)
            check(bounded.getLong("events_dropped_through") > 0)
            val environment = com.android.internal.ravenwood.RavenwoodEnvironment.getInstance()
            check(!environment.isRunningOnRavenwood())
            check(runCatching { environment.fromAddress<Any>(1) }.exceptionOrNull() is UnsupportedOperationException)
        } finally {
            root.destroy()
            ArtzigAccessibilityBridge.setActivityRoot(null)
            drainMainQueue()
        }
        check(!ArtzigAccessibilityBridge.isIdle())
    }

    private fun testStockMediaProjectionCallbacks() {
        val context = HostContexts.systemContext(System.getenv("ARTZIG_FRAMEWORK_RES"), 200, 200, 120, 0)
        val calls = mutableListOf<String>()
        lateinit var remoteCallback: android.media.projection.IMediaProjectionCallback
        val service = object : android.media.projection.IMediaProjection.Stub(),
            android.media.projection.IMediaProjection by dev.artzig.system.unsupported<android.media.projection.IMediaProjection>() {
            override fun asBinder(): IBinder = this
            override fun start(callback: android.media.projection.IMediaProjectionCallback) {
                calls += "start"
                remoteCallback = callback
            }
            override fun getDisplayId(): Int = android.view.Display.DEFAULT_DISPLAY
            override fun stop(reason: Int) {
                check(reason == android.media.projection.StopReason.STOP_HOST_APP)
                calls += "stop-request"
                remoteCallback.onStop()
            }
        }
        val projection = android.media.projection.MediaProjection(context, service)
        check(calls == listOf("start"))
        val callback = object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() { calls += "stopped" }
            override fun onCapturedContentResize(width: Int, height: Int) { calls += "resize:$width:$height" }
            override fun onCapturedContentVisibilityChanged(visible: Boolean) { calls += "visible:$visible" }
        }
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        remoteCallback.onCapturedContentResize(640, 480)
        remoteCallback.onCapturedContentVisibilityChanged(true)
        projection.stop()
        check(calls == listOf("start", "stop-request")) { "callbacks must be posted to the handler" }
        drainMainQueue()
        check(calls == listOf("start", "stop-request", "resize:640:480", "visible:true", "stopped"))
        projection.unregisterCallback(callback)
        remoteCallback.onStop()
        drainMainQueue()
        check(calls.count { it == "stopped" } == 1)
    }

    private fun testStockMediaContracts() {
        fun <T : Parcelable> roundTrip(value: T, creator: Parcelable.Creator<T>): T {
            val parcel = Parcel.obtain()
            try {
                value.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                return creator.createFromParcel(parcel)
            } finally {
                parcel.recycle()
            }
        }
        check(runCatching { android.media.AudioFocusRequest.Builder(0) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching {
            android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAcceptsDelayedFocusGain(true).build()
        }.exceptionOrNull() is IllegalStateException)
        check(runCatching {
            android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setWillPauseWhenDucked(true).build()
        }.exceptionOrNull() is IllegalStateException)
        val metadata = Bundle().apply { putString("source", "original") }
        val attributeBuilder = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).addBundle(metadata)
        val attributes = attributeBuilder.build()
        metadata.putString("source", "changed")
        attributeBuilder.addBundle(Bundle().apply { putString("source", "builder") })
        check(attributes.bundle.getString("source") == "original")
        check(AudioAttributes.Builder(attributes).build().bundle == null)
        var focusChanges = 0
        val focusListener = android.media.AudioManager.OnAudioFocusChangeListener { focusChanges++ }
        val focusHandler = Handler(Looper.getMainLooper())
        val focus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener(focusListener, focusHandler)
            .setAcceptsDelayedFocusGain(true).setWillPauseWhenDucked(true).setLocksFocus(true)
            .setForceDucking(true).build()
        check(focus.focusGain == android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        check(focus.acceptsDelayedFocusGain() && focus.willPauseWhenDucked() && focus.locksFocus())
        check(focus.onAudioFocusChangeListener === focusListener && focus.onAudioFocusChangeListenerHandler === focusHandler)
        check(focusChanges == 0)
        val forced = focus.audioAttributes.bundle
        check(forced.getString("source") == "original")
        check(forced.getBoolean("a11y_force_ducking"))
        forced.clear()
        check(focus.audioAttributes.bundle.getBoolean("a11y_force_ducking"))
        check(!attributes.bundle.getBoolean("a11y_force_ducking"))
        val copiedFocus = android.media.AudioFocusRequest.Builder(focus).build()
        check(copiedFocus.audioAttributes === focus.audioAttributes)
        check(copiedFocus.acceptsDelayedFocusGain() && copiedFocus.willPauseWhenDucked())
        check(!copiedFocus.locksFocus()) // The pinned copy builder deliberately omits the lock flag.
        check(roundTrip(focus.audioAttributes, AudioAttributes.CREATOR).bundle.getBoolean("a11y_force_ducking"))
        val encodedAttributes = Parcel.obtain()
        try {
            focus.audioAttributes.writeToParcel(encodedAttributes, AudioAttributes.FLATTEN_TAGS)
            encodedAttributes.setDataPosition(0)
            check(encodedAttributes.readInt() == AudioAttributes.USAGE_MEDIA)
            check(encodedAttributes.readInt() == AudioAttributes.CONTENT_TYPE_UNKNOWN)
            check(encodedAttributes.readInt() == -1) // AOSP's unset capture preset.
            // Stock Builder mMuteHapticChannels defaults true; the old stub omitted this bit.
            check(encodedAttributes.readInt() == AudioAttributes.FLAG_MUTE_HAPTIC)
            check(encodedAttributes.readInt() == AudioAttributes.FLATTEN_TAGS)
            check(encodedAttributes.readString() == "")
            check(encodedAttributes.readInt() == 1980) // AOSP's non-null bundle marker.
            check(encodedAttributes.readBundle()!!.getBoolean("a11y_force_ducking"))
            check(encodedAttributes.dataAvail() == 0)
        } finally {
            encodedAttributes.recycle()
        }
        check(runCatching { android.media.MediaRoute2Info.Builder("", "route") }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { android.media.MediaRoute2Info.Builder("route", "").build() }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { android.media.MediaRoute2Info.Builder("route", "Route").build() }.exceptionOrNull() is IllegalArgumentException)
        val routeMetadata = Bundle().apply { putString("origin", "test") }
        val route = android.media.MediaRoute2Info.Builder("route", "Test route")
            .addFeature(android.media.MediaRoute2Info.FEATURE_LIVE_AUDIO)
            .setType(android.media.MediaRoute2Info.TYPE_HDMI_EARC)
            .setProviderId("provider").setProviderPackageName("example.provider")
            .setVisibilityRestricted(setOf("example.allowed"))
            .setDeduplicationIds(setOf("same-device"))
            .setVolumeHandling(android.media.MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
            .setVolumeMax(20).setVolume(7).setExtras(routeMetadata).build()
        routeMetadata.clear()
        val routeCopy = roundTrip(route, android.media.MediaRoute2Info.CREATOR)
        check(routeCopy == route && routeCopy.hashCode() == route.hashCode())
        check(routeCopy.id == "provider:route" && routeCopy.originalId == "route")
        check(routeCopy.volume == 7 && routeCopy.volumeMax == 20)
        check(routeCopy.type == android.media.MediaRoute2Info.TYPE_HDMI_EARC)
        check(routeCopy.extras!!.getString("origin") == "test")
        check(routeCopy.deduplicationIds == setOf("same-device"))
        check(routeCopy.isVisibleTo("example.allowed") && routeCopy.isVisibleTo("example.provider"))
        check(!routeCopy.isVisibleTo("example.other"))
        check(routeCopy.hasAllFeatures(listOf(android.media.MediaRoute2Info.FEATURE_LIVE_AUDIO)))
        check(dev.artzig.system.HostedMediaRoutes.builtInAudio.isSystemRoute)
        val packages = mutableListOf("example.route")
        val preference = android.media.RouteDiscoveryPreference.Builder(listOf("video", "", "video"), true)
            .setAllowedPackages(packages)
            .setDeduplicationPackageOrder(listOf("example.route"))
            .setExtras(Bundle().apply { putString("source", "smoke") })
            .build()
        packages.clear()
        val restored = roundTrip(preference, android.media.RouteDiscoveryPreference.CREATOR)
        check(restored.preferredFeatures == listOf("video", "video"))
        check(restored.allowedPackages == listOf("example.route"))
        check(restored.deduplicationPackageOrder == listOf("example.route"))
        check(restored.shouldPerformActiveScan() && restored.shouldRemoveDuplicates())
        check(restored.extras.getString("source") == "smoke")
        val picture = android.media.quality.PictureProfileHandle(0x100000001L)
        check(roundTrip(picture, android.media.quality.PictureProfileHandle.CREATOR).id == picture.id)
        check(android.media.quality.PictureProfileHandle.NONE.id == 0L)
        val error = android.media.MediaCryptoException("crypto", 3, 5, 7)
        check(error.message == "crypto" && error.vendorError == 3 && error.oemError == 5 && error.errorContext == 7)
        val ordinary = android.media.MediaCryptoException("ordinary")
        check(ordinary.vendorError == 0 && ordinary.oemError == 0 && ordinary.errorContext == 0)
        check(GL10.GL_TRIANGLES == 4)
        check(GL10::class.java.getMethod(
            "glDrawArrays", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).returnType == Void.TYPE)
    }

    private fun testStockProtoLog() {
        val group = com.android.internal.protolog.ProtoLogGroup("STOCK_CLIENT_SMOKE", "StockProtoLog")
        group.setLogToProto(false)
        group.setLogToLogcat(true)
        com.android.internal.protolog.ProtoLog.init(group)
        val before = ArtzigTestDiagnostics.logSequence()
        com.android.internal.protolog.ProtoLog.i(group, "stock count=%d value=%s", 7, "ready")
        check(ArtzigTestDiagnostics.snapshot(before, ArtzigTestDiagnostics.issueSequence())
            .contains("stock count=7 value=ready"))
        group.setLogToLogcat(false)
        val disabled = ArtzigTestDiagnostics.logSequence()
        com.android.internal.protolog.ProtoLog.i(group, "stock disabled message")
        check(!ArtzigTestDiagnostics.snapshot(disabled, ArtzigTestDiagnostics.issueSequence())
            .contains("stock disabled message"))
    }

    private fun testStockActivityOptions() {
        val bounds = Rect(10, 20, 310, 220)
        val cookie = ActivityOptions.LaunchCookie()
        // Scene metadata is serialized only for the scene-transition animation type.
        val options = ActivityOptions(Bundle().apply {
            putInt(ActivityOptions.KEY_ANIM_TYPE, ActivityOptions.ANIM_SCENE_TRANSITION)
        }).setLaunchBounds(bounds).setLaunchDisplayId(7).setCallerDisplayId(3)
        options.setPendingIntentLaunchFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        options.setLaunchTaskId(31)
        options.setLaunchCookie(cookie)
        bounds.setEmpty()
        val scene = ActivityOptions.SceneTransitionInfo().apply {
            setResultCode(Activity.RESULT_OK)
            setResultData(Intent("dev.artzig.SCENE_RESULT"))
            setSharedElementNames(arrayListOf("poster"))
            setExitCoordinatorKey(19)
        }
        options.setSceneTransitionInfo(scene)
        val parcel = Parcel.obtain()
        val restored = try {
            parcel.writeBundle(options.toBundle())
            parcel.setDataPosition(0)
            checkNotNull(ActivityOptions.fromBundle(parcel.readBundle(ActivityOptions::class.java.classLoader)))
        } finally {
            parcel.recycle()
        }
        check(restored.launchBounds == Rect(10, 20, 310, 220))
        check(restored.launchDisplayId == 7 && restored.callerDisplayId == 3 && restored.launchTaskId == 31)
        check(restored.pendingIntentLaunchFlags == Intent.FLAG_ACTIVITY_NEW_TASK)
        check(restored.launchCookie == cookie.binder)
        val restoredScene = checkNotNull(restored.sceneTransitionInfo)
        check(restoredScene.resultCode == Activity.RESULT_OK && restoredScene.resultData?.action == "dev.artzig.SCENE_RESULT")
        check(restoredScene.sharedElementNames == arrayListOf("poster") && restoredScene.exitCoordinatorKey == 19)
        check(ActivityOptions.fromBundle(null) == null)
    }

    private fun testSplashScreenLifecycle() {
        val context = HostContexts.systemContext(System.getenv("ARTZIG_FRAMEWORK_RES"), 200, 200, 120, 0)
        val activity = Activity()
        activity.attach(context)
        val state = Bundle().apply {
            putStringArrayList("android:pendingExitSharedElements", arrayListOf("poster"))
            putStringArrayList("android:exitingMappedFrom", arrayListOf("from"))
            putStringArrayList("android:exitingMappedTo", arrayListOf("to"))
        }
        activity.performCreate(state)
        val saved = Bundle()
        activity.performSaveInstanceState(saved)
        for (key in state.keySet()) check(saved.getStringArrayList(key) == state.getStringArrayList(key))
        val thread = ActivityThread.currentActivityThread()
        val token = checkNotNull(activity.activityToken)
        val splash = activity.splashScreen
        check(splash === activity.splashScreen)
        splash.setOnExitAnimationListener { }
        check(thread.isHandleSplashScreenExit(token))
        splash.clearOnExitAnimationListener()
        check(!thread.isHandleSplashScreenExit(token))
        splash.setOnExitAnimationListener { }
        val record = ActivityThread.ActivityClientRecord().apply {
            this.fixtureActivity = activity
            this.token = token
            setState(android.app.servertransaction.ActivityLifecycleItem.ON_CREATE)
        }
        thread.handleDestroyActivity(record, false, false, "splash listener cleanup")
        check(!thread.isHandleSplashScreenExit(token))

        android.app.PropertyInvalidatedCache.disableForTestMode()
        val packages = HostedPackageManagerService().apply {
            installPlatform(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")))
        }
        val packageName = checkNotNull(packages.installPackage(checkNotNull(permissionsFixtureApk)))
        check(packages.getSplashScreenTheme(packageName, 0) == null)
        packages.setSplashScreenTheme(packageName, "android:style/Theme.Material", 0)
        check(packages.getSplashScreenTheme(packageName, 0) == "android:style/Theme.Material")
        packages.setSplashScreenTheme(packageName, null, 0)
        check(packages.getSplashScreenTheme(packageName, 0) == null)
        check(runCatching { packages.getSplashScreenTheme(packageName, -1) }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun testStockInputMethodService() {
        val context = HostContexts.systemContext(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 320, 240, 120, 0)
        val thread = ActivityThread.currentActivityThread()
        val service = android.inputmethodservice.InputMethodService()
        val packageContext = context.createPackageContext("org.example.permissions", 0)
        val loaded = thread.getPackageInfoNoCheck(packageContext.applicationInfo)
        val application = loaded.makeApplicationInner(false, thread.instrumentation)
        val base = service.createServiceBaseContext(thread, loaded)
        service.attach(base, thread, service.javaClass.name, Binder(), application, ActivityManager.getService())
        service.onCreate()
        try {
            check(service.windowType == WindowManager.LayoutParams.TYPE_INPUT_METHOD)
            check(service.window.window?.attributes?.type == WindowManager.LayoutParams.TYPE_INPUT_METHOD)
            check(service.onBind(Intent()) is com.android.internal.inputmethod.IInputMethod.Stub)
            check(service.onCreateInputMethodInterface() != null)
            check(service.onCreateInputMethodSessionInterface() != null)
            check(!service.isInputViewShown)
        } finally {
            service.onDestroy()
            service.detachAndCleanUp()
        }
    }

    private fun testQueuedRootInput() {
        val context = HostContexts.systemContext(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 200, 200, 120, 0)
        val root = android.view.ViewRootImpl(context)
        val delivered = ArrayList<Int>()
        val now = android.os.SystemClock.uptimeMillis()
        fun event(code: Int, time: Long) = KeyEvent(now, time, KeyEvent.ACTION_DOWN, code, 0)
        val view = object : View(context) {
            override fun onKeyDown(code: Int, keyEvent: KeyEvent): Boolean {
                delivered.add(code)
                if (code == KeyEvent.KEYCODE_A) root.enqueueInputEvent(event(KeyEvent.KEYCODE_C, now + 20))
                return true
            }
        }.apply { isFocusableInTouchMode = true }
        root.setView(view, 200, 200)
        view.requestFocus()
        try {
            check(root.inputToken == null)
            root.enqueueInputEvent(event(KeyEvent.KEYCODE_A, now + 30))
            root.enqueueInputEvent(event(KeyEvent.KEYCODE_B, now + 10))
            check(delivered.isEmpty()) { "queued input was dispatched synchronously" }
            drainMainQueue()
            check(delivered == listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_C))
            root.enqueueInputEvent(event(KeyEvent.KEYCODE_D, now))
        } finally { root.destroy() }
        drainMainQueue()
        check(delivered.size == 3) { "detached root received pending input" }
    }

    private fun testStockWindowContexts() {
        val base = HostContexts.systemContext(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 320, 240, 120, 0)
        val thread = android.app.ActivityThread.currentActivityThread()
        val systemUi = thread.getSystemUiContext(Display.DEFAULT_DISPLAY)
        check(systemUi !== thread.systemContext)
        check(systemUi === thread.systemUiContext && systemUi === thread.systemUiContextNoCreate)
        check(systemUi.displayId == Display.DEFAULT_DISPLAY)
        val isolatedUi = thread.createSystemUiContextForTesting(Display.DEFAULT_DISPLAY)
        check(isolatedUi !== systemUi && isolatedUi.displayId == Display.DEFAULT_DISPLAY)
        isolatedUi.destroy()
        drainMainQueue()
        check(thread.systemUiContextNoCreate === systemUi)
        val context = base.createWindowContext(
            android.view.ArtzigDisplay.get(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null,
        ) as android.window.WindowContext
        val token = context.windowContextToken as android.window.WindowTokenClient
        val controller = android.window.WindowTokenClientController.getInstance()
        val callbacks = ArrayList<Configuration>()
        context.registerComponentCallbacks(object : android.content.ComponentCallbacks {
            override fun onConfigurationChanged(config: Configuration) {
                check(context.resources.configuration.fontScale == config.fontScale)
                check(context.resources.configuration.screenWidthDp == config.screenWidthDp)
                callbacks.add(Configuration(config))
            }
            override fun onLowMemory() {}
        })
        try {
            check(controller.getWindowContext(token) === context)
            check(runCatching { token.attachContext(context) }.exceptionOrNull() is IllegalStateException)
            check(runCatching { context.attachToDisplayArea() }.exceptionOrNull() is IllegalStateException)
            val wm = context.getSystemService(WindowManager::class.java)
            check(wm === context.getSystemService(WindowManager::class.java))
            val originalMetrics = wm.currentWindowMetrics
            check(originalMetrics.bounds == android.graphics.Rect(0, 0, 320, 240))
            check(originalMetrics.density == 1f)
            check(runCatching {
                wm.addView(View(context), WindowManager.LayoutParams().apply { type = WindowManager.LayoutParams.TYPE_APPLICATION })
            }.exceptionOrNull() is IllegalArgumentException)

            val config = Configuration(context.resources.configuration).apply { fontScale = 1.3f }
            val item = android.app.servertransaction.WindowContextInfoChangeItem(token, config, Display.DEFAULT_DISPLAY)
            config.fontScale = 2f
            val parcel = Parcel.obtain()
            val restored = try {
                item.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                android.app.servertransaction.WindowContextInfoChangeItem.CREATOR.createFromParcel(parcel)
            } finally { parcel.recycle() }
            check(item == restored && item.hashCode() == restored.hashCode())
            restored.execute(ActivityThread.currentActivityThread(), android.app.servertransaction.PendingTransactionActions())
            check(callbacks.single().fontScale == 1.3f)

            HostContexts.configureDisplay(480, 240, 120)
            drainMainQueue()
            check(callbacks.size == 2 && callbacks.last().screenWidthDp == 480)
            check(wm.currentWindowMetrics.bounds == android.graphics.Rect(0, 0, 480, 240))
            check(wm.maximumWindowMetrics.bounds == wm.currentWindowMetrics.bounds)
            check(wm.currentWindowMetrics.windowInsets.getInsets(android.view.WindowInsets.Type.systemBars()) == android.graphics.Insets.NONE)
            check(originalMetrics.bounds == android.graphics.Rect(0, 0, 320, 240)) {
                "a window metrics snapshot changed after a resource configuration update"
            }
            HostContexts.configureDisplay(480, 240, 120)
            drainMainQueue()
            check(callbacks.size == 2) { "unchanged window configuration was delivered twice" }

            HostContexts.configureDisplay(411, 731, 180)
            drainMainQueue()
            check(wm.currentWindowMetrics.bounds == Rect(0, 0, 411, 731))
            check(wm.currentWindowMetrics.density == 1.5f)
            check(wm.maximumWindowMetrics.bounds == Rect(0, 0, 411, 731))
            check(callbacks.size == 3)

            val removal = android.app.servertransaction.WindowContextWindowRemovalItem(token)
            val removalParcel = Parcel.obtain()
            val restoredRemoval = try {
                removal.writeToParcel(removalParcel, 0)
                removalParcel.setDataPosition(0)
                android.app.servertransaction.WindowContextWindowRemovalItem.CREATOR.createFromParcel(removalParcel)
            } finally { removalParcel.recycle() }
            check(removal == restoredRemoval)
            restoredRemoval.execute(ActivityThread.currentActivityThread(), android.app.servertransaction.PendingTransactionActions())
            check(token.context == null && controller.getWindowContext(token) == null)
            context.dispatchConfigurationChanged(Configuration())
            check(callbacks.size == 3) { "destroyed window context retained its callbacks" }
        } finally {
            context.release()
            HostContexts.configureDisplay(320, 240, 120)
            drainMainQueue()
        }
    }

    private fun testHealthPermissionContracts() {
        check(HealthPermissions.isWritePermission(HealthPermissions.WRITE_HEART_RATE))
        check(!HealthPermissions.isWritePermission(HealthPermissions.READ_HEART_RATE))
        check(HealthPermissions.getHealthDataCategoryForWritePermission(HealthPermissions.WRITE_HEART_RATE) == HealthDataCategory.VITALS)
        check(HealthPermissions.getHealthDataCategoryForWritePermission(HealthPermissions.READ_HEART_RATE) == -1)
        check(HealthPermissions.getHealthReadPermission(HealthPermissionCategory.HEART_RATE) == HealthPermissions.READ_HEART_RATE)
        check(HealthPermissions.getHealthWritePermission(HealthPermissionCategory.HEART_RATE) == HealthPermissions.WRITE_HEART_RATE)
        check(HealthPermissions.getHealthReadPermission(-1) == null)
        check(runCatching { HealthPermissions.getHealthWritePermission(-1) }.exceptionOrNull() is NullPointerException)
        check(HealthPermissions.getWriteHealthPermissionsFor(-1).isEmpty())
        val permission = android.content.pm.PermissionInfo().apply {
            name = HealthPermissions.READ_HEART_RATE
            group = HealthPermissions.HEALTH_PERMISSION_GROUP
        }
        check(HealthPermissions.isValidHealthPermission(permission))
        permission.group = "android.permission-group.LOCATION"
        check(!HealthPermissions.isValidHealthPermission(permission))
    }

    private fun testAconfigManifestFlags() {
        check(android.content.res.Flags.manifestFlagging()) {
            "the pinned BP2A manifest requires its released manifest_flagging value"
        }
        check(android.aconfig.DeviceProtos.PATHS.contentEquals(arrayOf(
            "/system/etc/aconfig_flags.pb", "/system_ext/etc/aconfig_flags.pb",
            "/product/etc/aconfig_flags.pb", "/vendor/etc/aconfig_flags.pb",
        ))) { "device flag paths must come from the pinned AOSP template inputs" }
        val flags = AconfigFlags()
        flags.addFlagValuesForTesting(mapOf("example.enabled" to true, "example.disabled" to false))
        check(flags.getFlagValue("example.enabled") == true)
        check(flags.getFlagValue("example.disabled") == false)
        flags.addFlagValuesForTesting(mapOf("example.enabled" to false))
        check(flags.getFlagValue("example.enabled") == false) { "later flag values replace earlier values" }

        fun shouldSkip(flag: String): Boolean {
            val parser = android.util.Xml.newPullParser()
            parser.setInput(java.io.StringReader(
                "<component xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
                    "android:featureFlag=\"$flag\"/>",
            ))
            parser.nextTag()
            return flags.skipCurrentElement(null, parser)
        }
        check(shouldSkip("example.disabled"))
        check(!shouldSkip("!example.disabled"))
    }

    private fun testBinderCallStatistics() {
        val handler = Handler(checkNotNull(Looper.getMainLooper()))
        val latency = BinderLatencyObserver(object : BinderLatencyObserver.Injector() {
            override fun getHandler(): Handler = handler
        }, com.android.internal.os.BinderLatencyProto.Dims.SYSTEM_SERVER)
        val stats = BinderCallsStats(object : BinderCallsStats.Injector() {
            override fun getHandler(): Handler = handler
            override fun getLatencyObserver(processSource: Int): BinderLatencyObserver = latency
        })
        val device = CachedDeviceState(true, false)
        stats.setDeviceState(device.readonlyClient)
        stats.setCollectLatencyData(false)
        stats.setSamplingInterval(1)
        stats.setAddDebugEntries(false)
        val binder = Binder()
        val workSourceUid = 10000
        try {
            check(stats.callStarted(binder, 42, workSourceUid) == null) {
                "CPU telemetry must be disabled while charging"
            }
            device.setCharging(false)
            val first = checkNotNull(stats.callStarted(binder, 42, workSourceUid))
            stats.callEnded(first, 10, 20, workSourceUid)
            val second = checkNotNull(stats.callStarted(binder, 42, workSourceUid))
            stats.callThrewException(second, IllegalStateException("telemetry probe"))
            stats.callEnded(second, 30, 15, workSourceUid)
            val exported = stats.exportedCallStats.single()
            check(exported.workSourceUid == workSourceUid && exported.methodName == "42")
            check(exported.callCount == 2L && exported.recordedCallCount == 2L)
            check(exported.maxRequestSizeBytes == 30L && exported.maxReplySizeBytes == 20L)
            check(exported.exceptionCount == 1L)
            check(stats.exportedExceptionStats[IllegalStateException::class.java.name] == 1)

            val interrupted = checkNotNull(stats.callStarted(binder, 42, workSourceUid))
            device.setCharging(true)
            stats.callEnded(interrupted, 100, 100, workSourceUid)
            check(stats.exportedCallStats.single().callCount == 2L) {
                "calls ending after charging begins must not enter CPU telemetry"
            }
            stats.reset()
            check(stats.exportedCallStats.isEmpty() && stats.exportedExceptionStats.isEmpty())
        } finally {
            handler.removeCallbacks(latency.statsdPushRunnable)
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

            dev.artzig.HostedWindowStack.clear()
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
            dev.artzig.HostedWindowStack.clear()
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
    ): dev.artzig.HostedWindowStack.Entry {
        val view = RecordingInputView(context, name, observed).apply { isClickable = true }
        val params = WindowManager.LayoutParams().apply {
            width = right - left
            height = bottom - top
            this.flags = flags
            gravity = Gravity.TOP or Gravity.LEFT
        }
        manager.addView(view, params)
        val entry = dev.artzig.HostedWindowStack.entries().last()
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

    private fun testLocalActivityManagerLifecycleAndRecreation(fixture: StockActivityFixture) {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            320,
            240,
            120,
            0,
        )
        val parentName = LocalParentActivity::class.java.name
        val firstName = LocalFirstActivity::class.java.name
        val secondName = LocalSecondActivity::class.java.name
        val parentInfo = activityInfo(parentName).apply {
            applicationInfo.targetSdkVersion = android.os.Build.VERSION.SDK_INT
        }
        val firstInfo = activityInfo(firstName).apply {
            applicationInfo.targetSdkVersion = android.os.Build.VERSION_CODES.GINGERBREAD_MR1
        }
        val secondInfo = activityInfo(secondName).apply {
            applicationInfo.targetSdkVersion = android.os.Build.VERSION.SDK_INT
        }
        val adapter = LocalActivityHostAdapter()
        val firstIntent = Intent().setComponent(ComponentName("org.example", firstName))
        val secondIntent = Intent().setComponent(ComponentName("org.example", secondName))
        var activeManager: LocalActivityManager? = null

        fixture.configure(
            adapter,
            mapOf(parentName to parentInfo, firstName to firstInfo, secondName to secondInfo),
        )
        try {
            val parent = fixture.launch(parentName) as LocalParentActivity
            expectedLocalActivityParent = parent
            checkNotNull(parent.activityToken)
            check(adapter.presentedActivities == listOf(parent))

            val manager = LocalActivityManager(parent, true)
            activeManager = manager
            val prematureStart = runCatching {
                manager.startActivity("first", Intent(firstIntent))
            }.exceptionOrNull()
            check(prematureStart is IllegalStateException) {
                "LocalActivityManager accepted a child before dispatchCreate: $prematureStart"
            }

            manager.dispatchCreate(null)
            manager.dispatchResume()
            localActivityEvents.clear()
            val firstWindow = checkNotNull(manager.startActivity("first", Intent(firstIntent)))
            val first = manager.getActivity("first") as LocalFirstActivity
            check(firstWindow === first.window)
            check(first.isChild && first.parent === parent)
            check(first.activityToken === parent.activityToken)
            check(first.window.container === parent.window)
            check(parent.window.hasChildren())
            check(manager.currentActivity === first && manager.currentId == "first")
            check(localActivityEvents == listOf(
                "first:create",
                "first:start",
                "first:post-create",
                "first:resume",
            )) { "first embedded launch ordering: $localActivityEvents" }

            localActivityEvents.clear()
            check(manager.startActivity("first", Intent(firstIntent)) === firstWindow)
            check(manager.getActivity("first") === first)
            check(localActivityEvents.isEmpty()) {
                "same embedded intent recreated or redispatched the child: $localActivityEvents"
            }

            val singleTopIntent = Intent(firstIntent)
                .setAction("dev.artzig.LOCAL_ACTIVITY_SINGLE_TOP")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("new-intent-marker", "delivered")
            check(manager.startActivity("first", singleTopIntent) === firstWindow)
            check(manager.getActivity("first") === first)
            check(first.receivedNewIntents.size == 1)
            check(first.receivedNewIntents.single().action == singleTopIntent.action)
            check(first.receivedNewIntents.single().getStringExtra("new-intent-marker") == "delivered")
            check(localActivityEvents == listOf("first:new-intent")) {
                "SINGLE_TOP dispatch changed lifecycle state: $localActivityEvents"
            }

            localActivityEvents.clear()
            val secondWindow = checkNotNull(manager.startActivity("second", Intent(secondIntent)))
            val second = manager.getActivity("second") as LocalSecondActivity
            check(secondWindow === second.window)
            check(second.isChild && second.parent === parent)
            check(second.activityToken === parent.activityToken)
            check(second.window.container === parent.window)
            check(manager.currentActivity === second && manager.currentId == "second")
            check(localActivityEvents == listOf(
                "first:save",
                "first:pause",
                "second:create",
                "second:start",
                "second:post-create",
                "second:resume",
            )) { "single-mode child switch ordering: $localActivityEvents" }

            localActivityEvents.clear()
            val firstReentryIntent = Intent(singleTopIntent).setFlags(0)
            check(manager.startActivity("first", firstReentryIntent) === firstWindow)
            check(manager.currentActivity === first && manager.currentId == "first")
            check(localActivityEvents == listOf("second:pause", "first:resume")) {
                "modern child pause or existing-child resume ordering: $localActivityEvents"
            }

            localActivityEvents.clear()
            manager.dispatchPause(false)
            manager.dispatchStop()
            check(localActivityEvents == listOf(
                "first:save",
                "first:pause",
                "first:stop",
                "second:stop",
            )) { "embedded pause/stop ordering: $localActivityEvents" }

            localActivityEvents.clear()
            manager.dispatchResume()
            check(localActivityEvents == listOf(
                "first:restart",
                "first:start",
                "first:resume",
            )) { "embedded restart ordering: $localActivityEvents" }
            check(adapter.presentedActivities == listOf(parent)) {
                "embedded children escaped into the desktop presentation stack"
            }

            localActivityEvents.clear()
            manager.dispatchDestroy(true)
            activeManager = null
            check(localActivityEvents == listOf(
                "first:pause",
                "first:stop",
                "first:destroy",
                "second:destroy",
            )) { "embedded destroy ordering: $localActivityEvents" }
            check(manager.getActivity("first") == null && manager.getActivity("second") == null)

            val saveManager = LocalActivityManager(parent, true)
            activeManager = saveManager
            saveManager.dispatchCreate(null)
            saveManager.dispatchResume()
            localActivityEvents.clear()
            val originalWindow = checkNotNull(
                saveManager.startActivity("saved", Intent(secondIntent)),
            )
            val original = saveManager.getActivity("saved") as LocalSecondActivity
            localActivityEvents.clear()
            val savedState = checkNotNull(saveManager.saveInstanceState())
            check(savedState.getBundle("saved")?.getString("local-state") == "second")
            check(localActivityEvents == listOf("second:save")) {
                "embedded state save ordering: $localActivityEvents"
            }

            localActivityEvents.clear()
            saveManager.dispatchDestroy(true)
            activeManager = null
            check(localActivityEvents == listOf(
                "second:pause",
                "second:stop",
                "second:destroy",
            )) { "saved child teardown ordering: $localActivityEvents" }
            parent.finish()

            val restoredParent =
                fixture.launch(parentName) as LocalParentActivity
            expectedLocalActivityParent = restoredParent
            checkNotNull(restoredParent.activityToken)
            check(restoredParent.activityToken !== parent.activityToken)
            val restoredManager = LocalActivityManager(restoredParent, true)
            activeManager = restoredManager
            restoredManager.dispatchCreate(savedState)
            restoredManager.dispatchResume()
            localActivityEvents.clear()
            val restoredWindow = checkNotNull(
                restoredManager.startActivity("saved", Intent(secondIntent)),
            )
            val restored = restoredManager.getActivity("saved") as LocalSecondActivity
            check(restored !== original && restoredWindow !== originalWindow)
            check(restored.isChild && restored.parent === restoredParent)
            check(restored.activityToken === restoredParent.activityToken)
            check(restored.window.container === restoredParent.window)
            check(localActivityEvents == listOf(
                "second:create:saved",
                "second:start",
                "second:restore",
                "second:post-create:saved",
                "second:resume",
            )) { "embedded saved-state restore ordering: $localActivityEvents" }
            check(adapter.presentedActivities == listOf(parent, restoredParent)) {
                "restored embedded child escaped into the desktop presentation stack"
            }

            localActivityEvents.clear()
            check(restoredManager.destroyActivity("saved", true) === restoredWindow)
            check(restoredManager.getActivity("saved") == null)
            check(restoredManager.destroyActivity("saved", true) == null)
            activeManager = null
            check(localActivityEvents == listOf(
                "second:pause",
                "second:stop",
                "second:destroy",
            )) { "destroyActivity ordering: $localActivityEvents" }
        } finally {
            activeManager?.dispatchDestroy(true)
            expectedLocalActivityParent = null
            HostedActivityProcess.shutdownActivitiesInProcess()
        }
    }

    private fun testInstrumentationResultTransactions() {
        HostContexts.systemContext(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")),
            320, 240, 120, 0)
        val thread = ActivityThread.currentActivityThread()
        val name = ResultDeliveryActivity::class.java.name
        val info = activityInfo(name).apply {
            exported = false
            applicationInfo.targetSdkVersion = android.os.Build.VERSION.SDK_INT
        }
        val token = Binder()
        val activity = checkNotNull(thread.startActivityNow(null, null,
            Intent().setComponent(ComponentName("org.example", name)), info,
            token, null, null, null, null))
        val record = checkNotNull(thread.getActivityClient(token))
        val instrumentation = thread.instrumentation
        val target = "dev.artzig.fixture.MonitoredActivity"
        val result = RecordingProcessIntent("monitor").apply { putExtra("result-payload", "queued") }
        val monitor = instrumentation.addMonitor(target,
            Instrumentation.ActivityResult(Activity.RESULT_OK, result), true)
        withRecordingActivityClientController {
            try {
                thread.handleStartActivity(record, null, null)
                thread.handleResumeActivity(record, true, false, false, "monitor-fixture")
                check(runCatching { thread.handleStartActivity(record, null, null) }.exceptionOrNull()
                    is IllegalStateException) { "a resumed Activity accepted another start" }
                activityResultEvents.clear()
                check(activity.canStartActivityForResult())
                activity.startActivityForResult(Intent().setComponent(ComponentName("org.example", target)), 31)
                check(activityResultEvents.isEmpty()) { "monitor result was delivered inside startActivityForResult" }
                runHostedFrame()
                check(activityResultEvents == listOf("pause",
                    "result:monitor:31:${Activity.RESULT_OK}:queued", "resume")) {
                    "monitor result transaction ordering: $activityResultEvents"
                }
                check(record.lifecycleState == android.app.servertransaction.ActivityLifecycleItem.ON_RESUME)
                check(result.preparedForProcess && result.fromProtectedComponent == android.os.StrictMode.vmUnsafeIntentLaunchEnabled())
                check(result.extras?.classLoader === activity.classLoader)
                activityResultEvents.clear()
                activity.startActivityForResult(Intent().setComponent(ComponentName("org.example", target)), -1)
                runHostedFrame()
                check(activityResultEvents.isEmpty()) { "negative request code received a monitor result" }
                check(monitor.hits == 2)
            } finally {
                instrumentation.removeMonitor(monitor)
                thread.handleDestroyActivity(record, true, false, "monitor-fixture-cleanup")
            }
        }
    }

    private fun testActivityThreadResultDelivery() {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            320,
            240,
            120,
            0,
        )
        val activityName = ResultDeliveryActivity::class.java.name
        val info = activityInfo(activityName).apply {
            exported = false
            applicationInfo.targetSdkVersion = android.os.Build.VERSION.SDK_INT
        }
        // AOSP avoids protected-component inspection unless unsafe-intent detection is
        // enabled. Exercise both policy states, then verify delivery uses the active policy.
        val originalVmPolicy = android.os.StrictMode.getVmPolicy()
        try {
            android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.LAX)
            check(!ActivityThread.isProtectedComponent(info))
            android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder(originalVmPolicy)
                .detectUnsafeIntentLaunch().penaltyLog().build())
            check(ActivityThread.isProtectedComponent(info))
        } finally {
            android.os.StrictMode.setVmPolicy(originalVmPolicy)
        }
        val activity = ResultDeliveryActivity()
        activity.attach(context, info)
        activity.performCreate(null)
        activity.performStart()
        activity.performPostCreate(null)
        activity.performResume()

        val record = ActivityThread.ActivityClientRecord().apply {
            token = Binder()
            fixtureIntent = Intent().setComponent(ComponentName("org.example", activityName))
            fixtureActivityInfo = info
            this.fixtureActivity = activity
            setState(android.app.servertransaction.ActivityLifecycleItem.ON_RESUME)
        }
        val first = RecordingProcessIntent("first").apply {
            putExtra("result-payload", "one")
        }
        val second = RecordingProcessIntent("second").apply {
            putExtra("result-payload", "two")
        }

        activityResultEvents.clear()
        ActivityThread.currentActivityThread().handleSendResult(
            record,
            listOf(
                ResultInfo(null, 11, Activity.RESULT_OK, first, Binder()),
                ResultInfo(null, 12, Activity.RESULT_CANCELED, second),
            ),
            "FrameworkModuleSmoke-resumed",
        )
        check(activityResultEvents == listOf(
            "pause",
            "result:first:11:${Activity.RESULT_OK}:one",
            "result:second:12:${Activity.RESULT_CANCELED}:two",
            "resume",
        )) { "resumed Activity result ordering: $activityResultEvents" }
        check(record.lifecycleState == android.app.servertransaction.ActivityLifecycleItem.ON_RESUME)
        check(first.preparedForProcess && first.fromProtectedComponent == android.os.StrictMode.vmUnsafeIntentLaunchEnabled())
        check(second.preparedForProcess && second.fromProtectedComponent == android.os.StrictMode.vmUnsafeIntentLaunchEnabled())
        check(first.extras?.classLoader === activity.classLoader)
        check(second.extras?.classLoader === activity.classLoader)

        activity.performPause()
        record.setState(android.app.servertransaction.ActivityLifecycleItem.ON_PAUSE)
        val paused = RecordingProcessIntent("paused").apply {
            putExtra("result-payload", "three")
        }
        activityResultEvents.clear()
        ActivityThread.currentActivityThread().handleSendResult(
            record,
            listOf(ResultInfo(null, 13, Activity.RESULT_OK, paused)),
            "FrameworkModuleSmoke-paused",
        )
        check(activityResultEvents == listOf(
            "result:paused:13:${Activity.RESULT_OK}:three",
        )) { "paused Activity result ordering: $activityResultEvents" }
        check(record.lifecycleState == android.app.servertransaction.ActivityLifecycleItem.ON_PAUSE)
        check(paused.preparedForProcess && paused.fromProtectedComponent == android.os.StrictMode.vmUnsafeIntentLaunchEnabled())
        check(paused.extras?.classLoader === activity.classLoader)

        activity.performStop()
        activity.performDestroy()

        val callerAware = CallerAwareResultActivity()
        callerAware.attach(context, info)
        callerAware.performCreate(null)
        callerAware.performStart()
        callerAware.performPostCreate(null)
        callerAware.performResume()
        callerAware.performPause()
        val callerRecord = ActivityThread.ActivityClientRecord().apply {
            token = Binder()
            fixtureIntent = Intent().setComponent(ComponentName("org.example", activityName))
            fixtureActivityInfo = info
            this.fixtureActivity = callerAware
            setState(android.app.servertransaction.ActivityLifecycleItem.ON_PAUSE)
        }

        withContentUriPermissionApis(false) {
            ActivityThread.currentActivityThread().handleSendResult(
                callerRecord,
                listOf(ResultInfo(null, 21, Activity.RESULT_OK, Intent(), Binder())),
                "FrameworkModuleSmoke-caller-flag-off",
            )
        }
        check(callerAware.callbackKinds == listOf("legacy"))
        check(runCatching { callerAware.currentCaller }.exceptionOrNull()
            is IllegalStateException)

        callerAware.callbackKinds.clear()
        val callerToken = Binder()
        withContentUriPermissionApis(true) {
            ActivityThread.currentActivityThread().handleSendResult(
                callerRecord,
                listOf(ResultInfo(null, 22, Activity.RESULT_OK, Intent(), callerToken)),
                "FrameworkModuleSmoke-caller-flag-on",
            )
        }
        check(callerAware.callbackKinds == listOf("caller"))
        check(callerAware.observedCaller == ComponentCaller(callerRecord.token, callerToken))
        check(callerAware.currentCallerMatchedCallback)
        check(runCatching { callerAware.currentCaller }.exceptionOrNull()
            is IllegalStateException)
        check(callerRecord.lifecycleState ==
            android.app.servertransaction.ActivityLifecycleItem.ON_PAUSE)

        callerAware.callbackKinds.clear()
        val compatDispatch = Activity::class.java.getDeclaredMethod(
            "dispatchActivityResult",
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Intent::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        withContentUriPermissionApis(true) {
            compatDispatch.invoke(
                callerAware,
                null,
                24,
                Activity.RESULT_OK,
                Intent(),
                "FrameworkModuleSmoke-compat-result",
            )
        }
        check(callerAware.callbackKinds == listOf("caller"))
        check(callerAware.observedCaller == ComponentCaller(callerAware.activityToken, null))
        check(callerAware.currentCallerMatchedCallback)
        check(runCatching { callerAware.currentCaller }.exceptionOrNull()
            is IllegalStateException)
        callerAware.performStop()
        callerAware.performDestroy()

        val missingSuper = MissingSuperPauseActivity()
        missingSuper.attach(context, info)
        missingSuper.performCreate(null)
        missingSuper.performStart()
        missingSuper.performPostCreate(null)
        missingSuper.performResume()
        val missingSuperRecord = ActivityThread.ActivityClientRecord().apply {
            token = Binder()
            fixtureIntent = Intent().setComponent(ComponentName("org.example", activityName))
            fixtureActivityInfo = info
            this.fixtureActivity = missingSuper
            setState(android.app.servertransaction.ActivityLifecycleItem.ON_RESUME)
        }
        val missingSuperFailure = runCatching {
            ActivityThread.currentActivityThread().handleSendResult(
                missingSuperRecord,
                listOf(ResultInfo(null, 23, Activity.RESULT_OK, Intent())),
                "FrameworkModuleSmoke-missing-super",
            )
        }.exceptionOrNull()
        check(missingSuperFailure is SuperNotCalledException) {
            "missing super.onPause should fail directly: $missingSuperFailure"
        }
        check(missingSuper.pauseCalls == 1)
        check(missingSuperRecord.lifecycleState ==
            android.app.servertransaction.ActivityLifecycleItem.ON_RESUME)
        missingSuper.performStop()
        missingSuper.performDestroy()
    }

    private fun <T> withContentUriPermissionApis(enabled: Boolean, block: () -> T): T {
        val flags = android.security.Flags::class.java.getDeclaredField("FEATURE_FLAGS")
        flags.isAccessible = true
        val original = flags.get(null)
        val replacement = java.lang.reflect.Proxy.newProxyInstance(
            flags.type.classLoader,
            arrayOf(flags.type),
        ) { _, method, args ->
            if (method.name == "contentUriPermissionApis") {
                enabled
            } else {
                method.invoke(original, *(args ?: emptyArray()))
            }
        }
        flags.set(null, replacement)
        return try {
            block()
        } finally {
            flags.set(null, original)
        }
    }

    private fun testActivityThreadTransactionCompletion(persistable: Boolean = false) {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            320,
            240,
            120,
            0,
        )
        val activityName = TransactionCompletionActivity::class.java.name
        val info = activityInfo(activityName).apply {
            exported = false
            persistableMode = if (persistable) ActivityInfo.PERSIST_ACROSS_REBOOTS else ActivityInfo.PERSIST_ROOT_ONLY
            applicationInfo.targetSdkVersion = android.os.Build.VERSION.SDK_INT
        }
        val presented = mutableListOf<Activity>()
        val adapter = object : HostedActivityProcess.HostAdapter {
            override fun showActivity(activity: Activity) {
                transactionCompletionEvents += "present"
                presented += activity
            }
            override fun clearActivityWindows() {
                transactionCompletionEvents += "clear"
            }
            override fun resizeActivityRoot(activity: Activity, width: Int, height: Int) = Unit
        }
        HostedActivityProcess.configureActivityHost(adapter)

        val thread = ActivityThread.currentActivityThread()
        val token = Binder()
        val stoppedState = PersistableBundle().apply { putString("persistent", "kept") }
        transactionCompletionEvents.clear()
        try {
            val reports = withRecordingActivityClientController(
                onReport = { report, args ->
                    transactionCompletionEvents += "report:$report"
                    if (report == "stopped") {
                        val state = args?.get(1) as Bundle?
                        val persistentState = args?.get(2) as PersistableBundle?
                        check(state?.getString("saved") == "state")
                        check(persistentState?.getString("persistent") == if (persistable) "saved-persistent" else null) {
                            "persistable=$persistable stopped persistent state=$persistentState"
                        }
                        check(args?.get(3) == "transaction-description")
                    }
                },
            ) {
                if (persistable) {
                    // Pinned LaunchActivityItem copies ActivityInfo, whose copy constructor
                    // omits persistableMode. Supply incoming metadata directly to exercise
                    // ActivityThread's persistable lifecycle without altering that AOSP class.
                    val record = ActivityThread.ActivityClientRecord().also {
                        it.token = token
                        it.fixtureIntent = Intent().setComponent(ComponentName("org.example", activityName))
                        it.fixtureActivityInfo = info
                        it.fixturePersistentState = stoppedState
                        it.fixtureOverrideConfig = Configuration()
                    }
                    val pending = PendingTransactionActions()
                    thread.handleLaunchActivity(record, pending, Context.DEVICE_ID_DEFAULT, null)
                    thread.handleStartActivity(record, pending, null)
                }
                thread.executeTransaction(ClientTransaction().apply {
                    if (!persistable) addTransactionItem(
                        LaunchActivityItem(
                            token,
                            Intent().setComponent(ComponentName("org.example", activityName)),
                            0,
                            info,
                            Configuration(context.resources.configuration),
                            Configuration(),
                            Context.DEVICE_ID_DEFAULT,
                            null,
                            null,
                            ActivityManager.PROCESS_STATE_UNKNOWN,
                            null,
                            stoppedState,
                            null,
                            null,
                            null,
                            false,
                            null,
                            Binder(),
                            null,
                            Binder(),
                            false,
                            null,
                            Binder(),
                            ActivityWindowInfo(),
                        ),
                    )
                    addTransactionItem(ResumeActivityItem(token, false, false))
                })
                runHostedFrame()
                check(transactionCompletionEvents == listOf(
                    "create",
                    "start",
                    "post-create",
                    "resume",
                    "report:resumed",
                    "present",
                )) {
                    "transaction launch was presented before resume: $transactionCompletionEvents"
                }
                check(presented.single() === thread.getActivityClient(token)?.fixtureActivity)
                check(thread.getActivityClient(token)?.fixtureState == null)
                check(thread.getActivityClient(token)?.fixturePersistentState == null)

                thread.executeTransaction(ClientTransaction().apply {
                    addTransactionItem(StopActivityItem(token))
                })
                runHostedFrame()
                check("clear" in transactionCompletionEvents) {
                    "final stop did not clear after state save: $transactionCompletionEvents"
                }
                check(transactionCompletionEvents.takeLast(6) == listOf(
                    "pause",
                    "description",
                    "stop",
                    "save",
                    "clear",
                    "report:stopped",
                )) {
                    "stop state/report ordering mismatch: $transactionCompletionEvents"
                }

                thread.executeTransaction(ClientTransaction().apply {
                    addTransactionItem(DestroyActivityItem(token, true))
                })

                val missingToken = Binder()
                val missingDestroy = DestroyActivityItem(missingToken, true)
                val missingTransaction = ClientTransaction().apply {
                    addTransactionItem(ResumeActivityItem(missingToken, false, false))
                    addTransactionItem(missingDestroy)
                }
                missingTransaction.preExecute(thread)
                check(thread.getActivitiesToBeDestroyed()[missingToken] === missingDestroy)
                thread.executeTransaction(missingTransaction)
                check(!thread.getActivitiesToBeDestroyed().containsKey(missingToken))
            }
            check(reports == listOf("resumed", "stopped", "destroyed")) {
                "transaction completion reports mismatch: $reports"
            }
            check(transactionCompletionEvents.takeLast(2) == listOf(
                "destroy",
                "report:destroyed",
            )) {
                "final destroy completion mismatch: " +
                    transactionCompletionEvents
            }
            check(transactionCompletionEvents.count { it == "clear" } == 1) {
                "destroy cleared an activity already hidden by stop: $transactionCompletionEvents"
            }
            check(thread.getActivityClient(token) == null)
        } finally {
            thread.getActivityClient(token)?.let { record ->
                withRecordingActivityClientController {
                    thread.handleDestroyActivity(
                        record,
                        true,
                        false,
                        "transaction-completion-cleanup",
                    )
                }
            }
            HostedActivityProcess.shutdownActivitiesInProcess()
        }
    }

    private fun testActivityThreadConfigurationChange(withContextOverride: Boolean = false) {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            320,
            240,
            120,
            0,
        )
        val activityName = ConfigurationDispatchActivity::class.java.name
        val missingSuperName = MissingSuperConfigurationActivity::class.java.name
        val info = activityInfo(activityName).apply {
            // ActivityConfigurationChangeItem means the server has already selected the handled
            // callback path; ActivityThread must not second-guess it from this local mask.
            configChanges = 0
        }
        val missingSuperInfo = activityInfo(missingSuperName)
        HostedActivityProcess.configureActivityHost(LocalActivityHostAdapter())

        val thread = ActivityThread.currentActivityThread()
        val windowInfoEvents = mutableListOf<Pair<android.os.IBinder, ActivityWindowInfo>>()
        val windowInfoListener = java.util.function.BiConsumer<android.os.IBinder, ActivityWindowInfo> {
                token, windowInfo ->
            windowInfoEvents += token to ActivityWindowInfo(windowInfo)
        }
        ClientTransactionListenerController.getInstance()
            .registerActivityWindowInfoChangedListener(windowInfoListener)
        var record: ActivityThread.ActivityClientRecord? = null
        var missingSuperRecord: ActivityThread.ActivityClientRecord? = null
        var configurationActivity: ConfigurationDispatchActivity? = null
        var application: android.app.Application? = null
        val applicationCallback = configurationLifecycleCallback("application-callback")
        val activityCallback = configurationLifecycleCallback("activity-callback")
        configurationContextOverride = if (withContextOverride) Configuration().apply {
            fontScale = 1.75f
        } else null
        try {
            val token = Binder()
            val activity = thread.startActivityNow(
                null,
                "configuration",
                Intent().setComponent(ComponentName("org.example", activityName)),
                info,
                token,
                null,
                null,
                Binder(),
                null,
            ) as ConfigurationDispatchActivity
            configurationActivity = activity
            // LoadedApk owns one Application per package; these synthetic activities belong
            // to org.example, which is distinct from the initially bound permissions fixture.
            application = activity.application
            activity.application.registerActivityLifecycleCallbacks(applicationCallback)
            activity.registerActivityLifecycleCallbacks(activityCallback)
            record = checkNotNull(thread.getActivityClient(token))
            thread.handleStartActivity(
                record,
                PendingTransactionActions().apply { setCallOnPostCreate(true) },
                null,
            )
            thread.handleResumeActivity(record, true, false, false, "configuration-smoke")
            check(activity.isResumed)
            if (withContextOverride) {
                record.fixtureActivityInfo.configChanges = ActivityInfo.CONFIG_FONT_SCALE
                val hostConfig = Configuration(thread.configuration).apply {
                    fontScale = if (fontScale == 2f) 2.25f else 2f
                    // Desktop display updates are unsequenced, like HostContexts.configureDisplay.
                    seq = 0
                }
                val hostFontScale = hostConfig.fontScale
                check(!HostedActivityProcess.applyConfigurationInProcess(hostConfig,
                    ActivityInfo.CONFIG_FONT_SCALE, 320, 240))
                check(activity.callbackConfiguration?.fontScale == 1.75f &&
                    activity.resourcesMatchedDuringCallback) {
                    "host resize lost the app context override or delivered mismatched resources: " +
                        "callback=${activity.callbackConfiguration}, resources=${activity.resources.configuration}"
                }
                check(hostConfig.fontScale == hostFontScale)
            }
            val lifecycleState = record.lifecycleState
            val initialOrientation = activity.resources.configuration.orientation
            val targetOrientation = if (initialOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                Configuration.ORIENTATION_PORTRAIT
            } else {
                Configuration.ORIENTATION_LANDSCAPE
            }
            val older = Configuration().apply {
                orientation = targetOrientation
                fontScale = 1.25f
                seq = 100
            }
            val newer = Configuration(older).apply {
                fontScale = 1.5f
                seq = 101
            }
            val olderWindowInfo = ActivityWindowInfo().apply {
                set(false, Rect(0, 0, 100, 100), Rect(0, 0, 100, 100))
            }
            val newerWindowInfo = ActivityWindowInfo().apply {
                set(true, Rect(0, 0, 300, 200), Rect(20, 10, 280, 190))
            }

            activityConfigurationEvents.clear()
            val transaction = ClientTransaction().apply {
                addTransactionItem(
                    ActivityConfigurationChangeItem(token, older, olderWindowInfo),
                )
                addTransactionItem(
                    ActivityConfigurationChangeItem(token, newer, newerWindowInfo),
                )
            }
            thread.executeTransaction(transaction)
            check(activityConfigurationEvents == listOf(
                "config",
                "application-callback",
                "activity-callback",
            )) {
                "configuration transaction did not coalesce to its newest item: " +
                    activityConfigurationEvents
            }
            check(activity.callbackConfiguration?.orientation == targetOrientation)
            check(activity.callbackConfiguration?.fontScale == if (withContextOverride) 1.75f else 1.5f)
            check(record.fixtureOverrideConfig.fontScale == 1.5f) {
                "app context override mutated the server configuration"
            }
            check(newer.fontScale == 1.5f && older.fontScale == 1.25f)
            check(activity.resourcesMatchedDuringCallback) {
                "Activity Resources were not updated before onConfigurationChanged"
            }
            check(record.lifecycleState == lifecycleState)
            check(record.activityWindowInfo == newerWindowInfo)
            check(record.activityWindowInfo != olderWindowInfo)
            check(windowInfoEvents == listOf(token to newerWindowInfo))
            newerWindowInfo.set(false, Rect(), Rect())
            check(record.activityWindowInfo?.isEmbedded == true) {
                "ActivityClientRecord retained the transaction's mutable window-info instance"
            }
            check(windowInfoEvents.single().second.isEmbedded) {
                "window-info listener retained the transaction's mutable instance"
            }

            val sameWindowConfig = Configuration(newer).apply { seq = 102 }
            val sameWindowInfo = ActivityWindowInfo(checkNotNull(record.activityWindowInfo))
            thread.handleActivityConfigurationChanged(
                record,
                sameWindowConfig,
                Display.INVALID_DISPLAY,
                sameWindowInfo,
            )
            check(activityConfigurationEvents == listOf(
                "config",
                "application-callback",
                "activity-callback",
            )) {
                "equivalent override spuriously dispatched a configuration callback"
            }
            check(windowInfoEvents.size == 1) {
                "unchanged ActivityWindowInfo was dispatched again"
            }
            check(record.lifecycleState == lifecycleState)

            val changedWindowConfig = Configuration(newer).apply { seq = 103 }
            val changedWindowInfo = ActivityWindowInfo().apply {
                set(false, Rect(0, 0, 640, 480), Rect(0, 0, 640, 480))
            }
            thread.handleActivityConfigurationChanged(
                record,
                changedWindowConfig,
                Display.INVALID_DISPLAY,
                changedWindowInfo,
            )
            check(activityConfigurationEvents == listOf(
                "config",
                "application-callback",
                "activity-callback",
            ))
            check(record.lifecycleState == lifecycleState)
            check(record.activityWindowInfo == changedWindowInfo)
            check(windowInfoEvents == listOf(
                token to sameWindowInfo,
                token to changedWindowInfo,
            ))

            // Once seq=103 is installed, an older non-move update is dropped with all its state.
            thread.handleActivityConfigurationChanged(
                record,
                older,
                Display.INVALID_DISPLAY,
                olderWindowInfo,
            )
            check(activityConfigurationEvents == listOf(
                "config",
                "application-callback",
                "activity-callback",
            ))
            check(record.activityWindowInfo == changedWindowInfo)
            check(windowInfoEvents.size == 2)

            // Stock ContextImpl falls back to the default display when a reported display
            // disappears before the client processes the move. The move still updates metadata.
            thread.handleActivityConfigurationChanged(
                record,
                Configuration(newer).apply { seq = 104 },
                7,
                olderWindowInfo,
            )
            check(activity.movedDisplayId == 7)
            check(activity.displayId == Display.DEFAULT_DISPLAY)
            check(record.activityWindowInfo == olderWindowInfo)
            check(windowInfoEvents.last() == token to olderWindowInfo)
            check(record.lifecycleState == lifecycleState)

            val missingToken = Binder()
            val missingSuper = thread.startActivityNow(
                null,
                "configuration-missing-super",
                Intent().setComponent(ComponentName("org.example", missingSuperName)),
                missingSuperInfo,
                missingToken,
                null,
                null,
                Binder(),
                null,
            ) as MissingSuperConfigurationActivity
            missingSuperRecord = checkNotNull(thread.getActivityClient(missingToken))
            thread.handleStartActivity(
                missingSuperRecord,
                PendingTransactionActions().apply { setCallOnPostCreate(true) },
                null,
            )
            check(thread.performResumeActivity(
                missingSuperRecord,
                false,
                "configuration-missing-super",
            ))
            val missingLifecycleState = missingSuperRecord.lifecycleState
            val missingTarget = if (
                missingSuper.resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE
            ) {
                Configuration.ORIENTATION_PORTRAIT
            } else {
                Configuration.ORIENTATION_LANDSCAPE
            }
            val missingConfig = Configuration().apply {
                orientation = missingTarget
                seq = 200
            }
            val failure = runCatching {
                ClientTransaction().apply {
                    addTransactionItem(
                        ActivityConfigurationChangeItem(
                            missingToken,
                            missingConfig,
                            ActivityWindowInfo(),
                        ),
                    )
                }.also(thread::executeTransaction)
            }.exceptionOrNull()
            check(failure is SuperNotCalledException) {
                "missing super.onConfigurationChanged should fail directly: $failure"
            }
            check(missingSuper.resourcesOrientationDuringCallback == missingTarget) {
                "Resources update did not precede the failing configuration callback"
            }
            check(missingSuperRecord.lifecycleState == missingLifecycleState)
        } finally {
            configurationContextOverride = null
            configurationActivity?.unregisterActivityLifecycleCallbacks(activityCallback)
            application?.unregisterActivityLifecycleCallbacks(applicationCallback)
            ClientTransactionListenerController.getInstance()
                .unregisterActivityWindowInfoChangedListener(windowInfoListener)
            withRecordingActivityClientController {
                missingSuperRecord?.let {
                    thread.handleDestroyActivity(it, true, false, "configuration-smoke-cleanup")
                }
                record?.let {
                    thread.handleDestroyActivity(it, true, false, "configuration-smoke-cleanup")
                }
            }
            HostedActivityProcess.shutdownActivitiesInProcess()
        }
    }

    private fun testActivityThreadRelaunch() {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            320,
            240,
            120,
            0,
        )
        val activityName = RelaunchActivity::class.java.name
        val info = activityInfo(activityName).apply {
            exported = false
            applicationInfo.targetSdkVersion = android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM
        }
        HostedActivityProcess.configureActivityHost(LocalActivityHostAdapter())

        val thread = ActivityThread.currentActivityThread()
        val token = Binder()
        val application = checkNotNull(ActivityThread.currentApplication())
        val processConfigSeqs = mutableListOf<Int>()
        val processConfigCallback = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                processConfigSeqs += newConfig.seq
            }

            override fun onLowMemory() = Unit
        }
        application.registerComponentCallbacks(processConfigCallback)
        val windowInfoEvents = mutableListOf<Pair<android.os.IBinder, ActivityWindowInfo>>()
        val windowInfoListener = java.util.function.BiConsumer<android.os.IBinder, ActivityWindowInfo> {
                changedToken, windowInfo ->
            windowInfoEvents += changedToken to ActivityWindowInfo(windowInfo)
        }
        ClientTransactionListenerController.getInstance()
            .registerActivityWindowInfoChangedListener(windowInfoListener)
        var record: ActivityThread.ActivityClientRecord? = null
        activityRelaunchGeneration = 0
        activityRelaunchEvents.clear()
        try {
            val baseOrientation = context.resources.configuration.orientation
            val coalescedOrientation = if (
                baseOrientation == Configuration.ORIENTATION_LANDSCAPE
            ) {
                Configuration.ORIENTATION_PORTRAIT
            } else {
                Configuration.ORIENTATION_LANDSCAPE
            }
            val olderProcessConfig = Configuration(context.resources.configuration).apply {
                orientation = coalescedOrientation
                fontScale = 1.1f
                seq = 1000
            }
            val newerProcessConfig = Configuration(olderProcessConfig).apply {
                fontScale = 1.2f
                seq = 1001
            }
            thread.executeTransaction(ClientTransaction().apply {
                addTransactionItem(
                    ConfigurationChangeItem(olderProcessConfig, Context.DEVICE_ID_DEFAULT),
                )
                addTransactionItem(
                    ConfigurationChangeItem(newerProcessConfig, Context.DEVICE_ID_DEFAULT),
                )
            })
            check(processConfigSeqs == listOf(1001)) {
                "process configuration items did not coalesce to newest: $processConfigSeqs"
            }

            val launchConfig = Configuration(newerProcessConfig).apply {
                orientation = baseOrientation
                fontScale = 1.3f
                seq = 1002
            }
            val launchAssistToken = Binder()
            val launchShareableToken = Binder()
            val launchIntent = RecordingReferrerIntent(
                Intent().putExtra("relaunch-intent", "launch"),
                "launch-intent-referrer",
            )
            val launchResult = Intent().putExtra("relaunch-result", "launch")
            withContentUriPermissionApis(false) {
                thread.executeTransaction(ClientTransaction().apply {
                    addTransactionItem(
                        LaunchActivityItem(
                            token,
                            Intent().setComponent(ComponentName("org.example", activityName)),
                            0,
                            info,
                            launchConfig,
                            Configuration(),
                            Context.DEVICE_ID_DEFAULT,
                            "launch-referrer",
                            null,
                            ActivityManager.PROCESS_STATE_UNKNOWN,
                            null,
                            null,
                            listOf(ResultInfo(null, 40, Activity.RESULT_OK, launchResult)),
                            listOf(launchIntent),
                            null,
                            false,
                            null,
                            launchAssistToken,
                            null,
                            launchShareableToken,
                            false,
                            null,
                            Binder(),
                            ActivityWindowInfo(),
                        ),
                    )
                })
            }
            record = checkNotNull(thread.getActivityClient(token))
            val oldActivity = record.fixtureActivity as RelaunchActivity
            check(oldActivity.resources.configuration.orientation == baseOrientation)
            check(processConfigSeqs == listOf(1001, 1002)) {
                "LaunchActivityItem preExecute config was not consumed before launch: " +
                    processConfigSeqs
            }
            check(oldActivity.assistToken === launchAssistToken)
            check(oldActivity.shareableActivityToken === launchShareableToken)
            check(oldActivity.referrer.toString() == "android-app://launch-referrer")
            thread.handleStartActivity(
                record,
                PendingTransactionActions().apply { setCallOnPostCreate(true) },
                null,
            )
            check(withContentUriPermissionApis(false) {
                thread.performResumeActivity(record, false, "relaunch-smoke-launch")
            })
            check(launchIntent.preparedForProcess && launchIntent.fromProtectedComponent == android.os.StrictMode.vmUnsafeIntentLaunchEnabled())
            check(activityRelaunchEvents == listOf(
                "1:create:fresh:no-retained:launch",
                "1:start",
                "1:post-create:null",
                "1:state-not-saved",
                "1:new-intent-legacy:launch:no-caller",
                "1:result:40:${Activity.RESULT_OK}:launch",
                "1:resume",
            )) { "LaunchActivityItem payload/token/config mismatch: $activityRelaunchEvents" }
            val oldWindow = oldActivity.window
            oldActivity.intent = Intent(oldActivity.intent).putExtra("relaunch-custom", "custom")

            val currentOrientation = oldActivity.resources.configuration.orientation
            val targetOrientation = if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                Configuration.ORIENTATION_PORTRAIT
            } else {
                Configuration.ORIENTATION_LANDSCAPE
            }
            val globalConfig = Configuration(oldActivity.resources.configuration).apply {
                orientation = targetOrientation
                seq = 1100
            }
            val olderOverride = Configuration().apply {
                fontScale = 1.25f
                seq = 1101
            }
            val newerOverride = Configuration().apply {
                fontScale = 1.5f
                seq = 1102
            }
            val olderWindowInfo = ActivityWindowInfo().apply {
                set(false, Rect(0, 0, 100, 100), Rect(0, 0, 100, 100))
            }
            val newerWindowInfo = ActivityWindowInfo().apply {
                set(true, Rect(0, 0, 300, 200), Rect(10, 10, 290, 190))
            }
            val firstResult = Intent().putExtra("relaunch-result", "first")
            val secondResult = Intent().putExtra("relaunch-result", "second")
            val firstIntent = RecordingReferrerIntent(
                Intent().putExtra("relaunch-intent", "first"),
                "first-referrer",
            )
            val secondIntent = RecordingReferrerIntent(
                Intent().putExtra("relaunch-intent", "second"),
                "second-referrer",
            )

            activityRelaunchEvents.clear()
            val transaction = ClientTransaction().apply {
                addTransactionItem(
                    ConfigurationChangeItem(globalConfig, Context.DEVICE_ID_DEFAULT),
                )
                addTransactionItem(
                    ActivityRelaunchItem(
                        token,
                        listOf(ResultInfo(null, 41, Activity.RESULT_OK, firstResult)),
                        listOf(firstIntent),
                        ActivityInfo.CONFIG_ORIENTATION,
                        MergedConfiguration(globalConfig, olderOverride),
                        true,
                        olderWindowInfo,
                    ),
                )
                addTransactionItem(
                    ActivityRelaunchItem(
                        token,
                        listOf(ResultInfo(null, 42, Activity.RESULT_CANCELED, secondResult)),
                        listOf(secondIntent),
                        ActivityInfo.CONFIG_FONT_SCALE,
                        MergedConfiguration(globalConfig, newerOverride),
                        true,
                        newerWindowInfo,
                    ),
                )
                addTransactionItem(ResumeActivityItem(token, false, false))
            }
            val controllerEvents = withContentUriPermissionApis(false) {
                withRecordingActivityClientController {
                    thread.executeTransaction(transaction)
                }
            }

            val replacement = record.fixtureActivity as RelaunchActivity
            check(replacement !== oldActivity)
            check(thread.getActivityClient(token) === record)
            check(record.lifecycleState ==
                android.app.servertransaction.ActivityLifecycleItem.ON_RESUME)
            check(replacement.window !== oldWindow) {
                "relaunch did not create a new Activity window object"
            }
            check(record.fixtureOverrideConfig.fontScale == 1.5f)
            check(record.fixtureLastNonConfigurationInstances == null)
            check(record.activityWindowInfo == newerWindowInfo)
            check(record.activityWindowInfo !== newerWindowInfo)
            check(windowInfoEvents == listOf(token to newerWindowInfo)) {
                "coalesced relaunch did not notify only the newest window info: $windowInfoEvents"
            }
            newerWindowInfo.set(false, Rect(), Rect())
            check(record.activityWindowInfo?.isEmbedded == true)
            check(windowInfoEvents.single().second.isEmbedded)
            check(firstIntent.preparedForProcess && firstIntent.fromProtectedComponent == android.os.StrictMode.vmUnsafeIntentLaunchEnabled())
            check(secondIntent.preparedForProcess && secondIntent.fromProtectedComponent == android.os.StrictMode.vmUnsafeIntentLaunchEnabled())
            check(runCatching { replacement.currentCaller }.exceptionOrNull()
                is IllegalStateException)
            check(replacement.observedReferrer == "android-app://second-referrer")
            check(replacement.referrer.toString() == "android-app://launch-referrer")
            check(controllerEvents == listOf("relaunched", "relaunched", "resumed")) {
                "ActivityClient reports were not emitted by transaction postExecute: " +
                    controllerEvents
            }
            check(activityRelaunchGeneration == 2) {
                "coalesced relaunch created $activityRelaunchGeneration Activity instances"
            }
            check(activityRelaunchEvents == listOf(
                "1:pause:$targetOrientation",
                "1:stop:true:${ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_FONT_SCALE}",
                "1:save",
                "1:retain",
                "1:destroy:true",
                "2:create:saved:retained:custom",
                "2:start",
                "2:restore:saved",
                "2:post-create:saved",
                "2:state-not-saved",
                "2:new-intent-legacy:first:no-caller",
                "2:new-intent-legacy:second:no-caller",
                "2:result:41:${Activity.RESULT_OK}:first",
                "2:result:42:${Activity.RESULT_CANCELED}:second",
                "2:resume",
            )) { "Activity relaunch ordering/coalescing mismatch: $activityRelaunchEvents" }

            val standaloneGlobal = Configuration(replacement.resources.configuration).apply {
                orientation = currentOrientation
                seq = 1200
            }
            val standaloneOverride = Configuration().apply {
                fontScale = 1.75f
                seq = 1201
            }
            val standaloneWindowInfo = ActivityWindowInfo().apply {
                set(false, Rect(0, 0, 640, 480), Rect(0, 0, 640, 480))
            }
            val standaloneCallerToken = Binder()
            val standaloneIntent = RecordingReferrerIntent(
                Intent().putExtra("relaunch-intent", "third"),
                "third-referrer",
                standaloneCallerToken,
            )
            val replacementWindow = replacement.window
            // The stock process transport publishes pending global configuration before
            // executing the activity relaunch; a createdConfig is not that process update.
            thread.updatePendingConfiguration(standaloneGlobal)
            activityRelaunchEvents.clear()
            val standaloneReports = withContentUriPermissionApis(true) {
                withRecordingActivityClientController {
                    thread.executeTransaction(ClientTransaction().apply {
                        addTransactionItem(
                            ActivityRelaunchItem(
                                token,
                                null,
                                listOf(standaloneIntent),
                                ActivityInfo.CONFIG_ORIENTATION,
                                MergedConfiguration(standaloneGlobal, standaloneOverride),
                                true,
                                standaloneWindowInfo,
                            ),
                        )
                        addTransactionItem(ResumeActivityItem(token, false, false))
                    })
                }
            }
            val standaloneReplacement = record.fixtureActivity as RelaunchActivity
            check(standaloneReplacement !== replacement)
            check(standaloneReplacement.window !== replacementWindow)
            check(standaloneReplacement.resources.configuration.orientation == currentOrientation) {
                "relaunch did not consume pending process configuration before launch"
            }
            check(ActivityThread.currentApplication()?.resources?.configuration?.orientation ==
                currentOrientation) {
                "relaunch did not install pending process configuration for the Application"
            }
            check(record.fixtureOverrideConfig.fontScale == 1.75f)
            check(record.fixtureLastNonConfigurationInstances == null)
            check(record.activityWindowInfo == standaloneWindowInfo)
            check(windowInfoEvents == listOf(
                token to ActivityWindowInfo(windowInfoEvents.first().second),
                token to standaloneWindowInfo,
            ))
            check(record.lifecycleState ==
                android.app.servertransaction.ActivityLifecycleItem.ON_RESUME)
            check(standaloneReports == listOf("relaunched", "resumed"))
            check(standaloneIntent.preparedForProcess &&
                standaloneIntent.fromProtectedComponent == android.os.StrictMode.vmUnsafeIntentLaunchEnabled())
            check(standaloneReplacement.observedNewIntentCaller ==
                ComponentCaller(token, standaloneCallerToken))
            check(standaloneReplacement.currentCallerMatchedCallback)
            check(standaloneReplacement.observedReferrer == "android-app://third-referrer")
            check(standaloneReplacement.referrer.toString() ==
                "android-app://launch-referrer")
            check(runCatching { standaloneReplacement.currentCaller }.exceptionOrNull()
                is IllegalStateException)
            check(activityRelaunchEvents == listOf(
                "2:pause:$currentOrientation",
                "2:stop:true:${ActivityInfo.CONFIG_ORIENTATION}",
                "2:save",
                "2:retain",
                "2:destroy:true",
                "3:create:saved:retained:custom",
                "3:start",
                "3:restore:saved",
                "3:post-create:saved",
                "3:state-not-saved",
                "3:new-intent-caller:third:true",
                "3:resume",
            )) { "standalone Activity relaunch ordering mismatch: $activityRelaunchEvents" }

            val staleGlobal = Configuration(standaloneGlobal).apply {
                orientation = targetOrientation
                seq = 1150
            }
            activityRelaunchEvents.clear()
            val staleReports = withRecordingActivityClientController {
                thread.executeTransaction(ClientTransaction().apply {
                    addTransactionItem(
                        ActivityRelaunchItem(
                            token,
                            null,
                            null,
                            ActivityInfo.CONFIG_ORIENTATION,
                            MergedConfiguration(staleGlobal, standaloneOverride),
                            true,
                            standaloneWindowInfo,
                        ),
                    )
                    addTransactionItem(ResumeActivityItem(token, false, false))
                })
            }
            val staleReplacement = record.fixtureActivity as RelaunchActivity
            check(staleReplacement.resources.configuration.orientation == currentOrientation) {
                "older createdConfig regressed Activity resources"
            }
            check(ActivityThread.currentApplication()?.resources?.configuration?.orientation ==
                currentOrientation) {
                "older createdConfig regressed Application resources"
            }
            check(record.fixtureLastNonConfigurationInstances == null)
            check(windowInfoEvents.size == 2) {
                "unchanged relaunch window info was notified again: $windowInfoEvents"
            }
            check(staleReports == listOf("relaunched", "resumed"))
            check(activityRelaunchEvents == listOf(
                "3:pause:$currentOrientation",
                "3:stop:true:${ActivityInfo.CONFIG_ORIENTATION}",
                "3:save",
                "3:retain",
                "3:destroy:true",
                "4:create:saved:retained:custom",
                "4:start",
                "4:restore:saved",
                "4:post-create:saved",
                "4:state-not-saved",
                "4:resume",
            )) { "stale createdConfig relaunch ordering mismatch: $activityRelaunchEvents" }

            val instrumentation = Instrumentation()
            activityRelaunchEvents.clear()
            withContentUriPermissionApis(false) {
                instrumentation.callActivityOnNewIntent(
                    staleReplacement,
                    Intent().putExtra("relaunch-intent", "direct-legacy"),
                )
                instrumentation.callActivityOnNewIntent(
                    staleReplacement,
                    ReferrerIntent(
                        Intent().putExtra("relaunch-intent", "direct-referrer-legacy"),
                        "direct-legacy-referrer",
                    ),
                )
            }
            withContentUriPermissionApis(true) {
                instrumentation.callActivityOnNewIntent(
                    staleReplacement,
                    Intent().putExtra("relaunch-intent", "direct-caller"),
                )
                instrumentation.callActivityOnNewIntent(
                    staleReplacement,
                    ReferrerIntent(
                        Intent().putExtra("relaunch-intent", "direct-referrer-caller"),
                        "direct-caller-referrer",
                    ),
                )
            }
            check(activityRelaunchEvents == listOf(
                "4:new-intent-legacy:direct-legacy:no-caller",
                "4:new-intent-legacy:direct-referrer-legacy:no-caller",
                "4:new-intent-caller:direct-caller:true",
                "4:new-intent-caller:direct-referrer-caller:true",
            )) { "direct Instrumentation new-intent flag routing mismatch: $activityRelaunchEvents" }
            check(staleReplacement.observedNewIntentCaller == ComponentCaller(token, null))
            check(staleReplacement.observedReferrer == "android-app://direct-caller-referrer")
            check(staleReplacement.referrer.toString() == "android-app://launch-referrer")
            check(runCatching { staleReplacement.currentCaller }.exceptionOrNull()
                is IllegalStateException)

            staleReplacement.intent = MalformedReferrerIntent()
            val retainedCustomIntent = staleReplacement.intent
            activityRelaunchEvents.clear()
            withContentUriPermissionApis(false) {
                instrumentation.callActivityOnNewIntent(
                    staleReplacement,
                    ReferrerIntent(
                        Intent().putExtra("relaunch-intent", "malformed-referrer"),
                        "safe-fallback",
                    ),
                )
            }
            check(activityRelaunchEvents == listOf(
                "4:new-intent-legacy:malformed-referrer:no-caller",
            ))
            check(staleReplacement.observedReferrer == "android-app://safe-fallback")
            check(staleReplacement.referrer.toString() == "android-app://launch-referrer")

            val sameContentNewSequence = Configuration(
                application.resources.configuration,
            ).apply { seq = 1300 }
            activityRelaunchEvents.clear()
            val sequenceOnlyReports = withRecordingActivityClientController {
                thread.executeTransaction(ClientTransaction().apply {
                    // All preExecute calls run first: the later process item therefore supplies
                    // the sequence-only pending config consumed by the earlier relaunch item.
                    addTransactionItem(
                        ActivityRelaunchItem(
                            token,
                            null,
                            null,
                            0,
                            MergedConfiguration(standaloneGlobal, standaloneOverride),
                            true,
                            standaloneWindowInfo,
                        ),
                    )
                    addTransactionItem(
                        ConfigurationChangeItem(
                            sameContentNewSequence,
                            Context.DEVICE_ID_DEFAULT,
                        ),
                    )
                    addTransactionItem(ResumeActivityItem(token, false, false))
                })
            }
            check(sequenceOnlyReports == listOf("relaunched", "resumed"))
            check(checkNotNull(record.fixtureActivity).intent === retainedCustomIntent)
            check(checkNotNull(record.fixtureActivity).componentName == record.fixtureIntent.component)
            check(activityRelaunchEvents == listOf(
                "4:pause:$currentOrientation",
                "4:stop:true:0",
                "4:save",
                "4:retain",
                "4:destroy:true",
                "5:create:saved:retained:launch",
                "5:start",
                "5:restore:saved",
                "5:post-create:saved",
                "5:state-not-saved",
                "5:resume",
            )) { "sequence-only pending relaunch mismatch: $activityRelaunchEvents" }
            check(record.fixtureLastNonConfigurationInstances == null)

            record.fixtureActivityInfo.applicationInfo.targetSdkVersion =
                android.os.Build.VERSION_CODES.O
            activityRelaunchEvents.clear()
            val oreoReports = withRecordingActivityClientController {
                thread.executeTransaction(ClientTransaction().apply {
                    addTransactionItem(
                        ActivityRelaunchItem(
                            token,
                            null,
                            null,
                            0,
                            MergedConfiguration(sameContentNewSequence, standaloneOverride),
                            true,
                            standaloneWindowInfo,
                        ),
                    )
                    addTransactionItem(ResumeActivityItem(token, false, false))
                })
            }
            check(oreoReports == listOf("relaunched", "resumed"))
            check(activityRelaunchEvents == listOf(
                "5:pause:$currentOrientation",
                "5:save",
                "5:stop:true:0",
                "5:retain",
                "5:destroy:true",
                "6:create:saved:retained:launch",
                "6:start",
                "6:restore:saved",
                "6:post-create:saved",
                "6:state-not-saved",
                "6:resume",
            )) { "target-O relaunch save ordering mismatch: $activityRelaunchEvents" }
            check(record.fixtureLastNonConfigurationInstances == null)
            val staleChangedSequence = Configuration(sameContentNewSequence).apply {
                orientation = targetOrientation
                seq = 1250
            }
            thread.handleConfigurationChanged(
                staleChangedSequence,
                Context.DEVICE_ID_DEFAULT,
            )
            check(application.resources.configuration.seq == 1300)
            check(application.resources.configuration.orientation ==
                currentOrientation) {
                "sequence-only config did not block a later stale changed delta"
            }
            check(processConfigSeqs == listOf(1001, 1002, 1100, 1200)) {
                "zero-mask/stale configurations dispatched callbacks: $processConfigSeqs"
            }
        } finally {
            application.unregisterComponentCallbacks(processConfigCallback)
            ClientTransactionListenerController.getInstance()
                .unregisterActivityWindowInfoChangedListener(windowInfoListener)
            withRecordingActivityClientController {
                thread.getActivityClient(token)?.let {
                    thread.handleDestroyActivity(it, true, false, "relaunch-smoke-cleanup")
                }
            }
            HostedActivityProcess.shutdownActivitiesInProcess()
        }
    }

    private fun <T> withRecordingActivityClientController(
        onReport: ((String, Array<out Any?>?) -> Unit)? = null,
        block: () -> T,
    ): List<String> {
        val singletonField = ActivityClient::class.java.getDeclaredField("INTERFACE_SINGLETON")
            .apply { isAccessible = true }
        val singleton = singletonField.get(null)
        val knownInstanceField = singleton.javaClass.getDeclaredField("mKnownInstance")
            .apply { isAccessible = true }
        val original = knownInstanceField.get(singleton)
        val reports = mutableListOf<String>()
        val controller = Proxy.newProxyInstance(
            IActivityClientController::class.java.classLoader,
            arrayOf(IActivityClientController::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "activityRelaunched" -> {
                    reports += "relaunched"
                    onReport?.invoke("relaunched", args)
                    null
                }
                "activityResumed" -> {
                    reports += "resumed"
                    onReport?.invoke("resumed", args)
                    null
                }
                "activityStopped" -> {
                    reports += "stopped"
                    onReport?.invoke("stopped", args)
                    null
                }
                "activityDestroyed" -> {
                    reports += "destroyed"
                    onReport?.invoke("destroyed", args)
                    null
                }
                "setTaskDescription" -> {
                    // Stock Activity sends its themed task metadata during creation.
                    check(args?.get(0) is IBinder && args.get(1) is ActivityManager.TaskDescription)
                    null
                }
                "getDisplayId" -> Display.DEFAULT_DISPLAY
                "willActivityBeVisible" -> true // The fixture has one foreground task.
                "reportSizeConfigurations" -> null // Qualifier policy has a separate service test.
                "asBinder" -> Binder()
                "toString" -> "FrameworkModuleSmokeActivityClientController"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("unexpected IActivityClientController call: ${method.name}")
            }
        } as IActivityClientController
        ActivityClient.setActivityClientController(controller)
        return try {
            block()
            reports
        } finally {
            knownInstanceField.set(singleton, original)
        }
    }

    private fun configurationLifecycleCallback(label: String) =
        object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit

            override fun onActivityConfigurationChanged(activity: Activity) {
                activityConfigurationEvents += label
            }
        }

    private fun testActivityGroupLifecycleStateAndResults(fixture: StockActivityFixture) {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            320,
            240,
            120,
            0,
        )
        val groupName = RecordingActivityGroup::class.java.name
        val childName = ActivityGroupChildActivity::class.java.name
        val resultName = ActivityGroupResultActivity::class.java.name
        val groupInfo = activityInfo(groupName).apply {
            applicationInfo.targetSdkVersion = android.os.Build.VERSION.SDK_INT
        }
        val childInfo = activityInfo(childName).apply {
            applicationInfo.targetSdkVersion = android.os.Build.VERSION.SDK_INT
        }
        val resultInfo = activityInfo(resultName).apply {
            applicationInfo.targetSdkVersion = android.os.Build.VERSION.SDK_INT
        }
        val adapter = LocalActivityHostAdapter()
        val childIntent = Intent().setComponent(ComponentName("org.example", childName))
        val resultIntent = Intent().setComponent(ComponentName("org.example", resultName))

        fixture.configure(
            adapter,
            mapOf(groupName to groupInfo, childName to childInfo, resultName to resultInfo),
        )
        localActivityEvents.clear()
        activityGroupEvents.clear()
        try {
            val group = fixture.launch(groupName) as RecordingActivityGroup
            expectedLocalActivityParent = group
            check(activityGroupEvents == listOf(
                "group:create",
                "group:start",
                "group:post-create",
                "group:resume",
            )) { "ActivityGroup launch ordering: $activityGroupEvents" }

            activityGroupEvents.clear()
            val manager = group.localActivityManager
            val childWindow = checkNotNull(manager.startActivity("group-child", childIntent))
            val child = group.currentActivity as ActivityGroupChildActivity
            check(manager.currentActivity === child && manager.currentId == "group-child")
            check(childWindow === child.window && child.window.container === group.window)
            check(localActivityEvents == listOf(
                "group-child:create",
                "group-child:start",
                "group-child:post-create",
                "group-child:resume",
            )) { "ActivityGroup child launch ordering: $localActivityEvents" }

            localActivityEvents.clear()
            child.startActivityForResult(resultIntent, 73)
            settleActivityFrames()
            check(HostedActivityProcess.currentActivity() is ActivityGroupResultActivity)
            check(activityGroupEvents == listOf("group:pause", "group:stop", "group:save")) {
                "ActivityGroup background callbacks: $activityGroupEvents"
            }
            check(localActivityEvents == listOf("group-child:pause", "group-child:stop")) {
                "modern ActivityGroup background order: $localActivityEvents"
            }

            val result = HostedActivityProcess.currentActivity() as ActivityGroupResultActivity
            result.setResult(
                Activity.RESULT_OK,
                Intent().putExtra("group-result", "delivered"),
            )
            result.finish()
            settleActivityFrames()
            check(HostedActivityProcess.currentActivity() === group)
            check(child.resultRequestCode == 73)
            check(child.resultCode == Activity.RESULT_OK)
            check(child.resultMarker == "delivered")
            check(activityGroupEvents == listOf(
                "group:pause",
                "group:stop",
                "group:save",
                "group:start",
                "group:resume",
            )) { "ActivityGroup forwarded lifecycle ordering: $activityGroupEvents" }
            check(localActivityEvents == listOf(
                "group-child:pause",
                "group-child:stop",
                "group-child:result",
                "group-child:restart",
                "group-child:start",
                "group-child:resume",
            )) { "ActivityGroup child result/lifecycle ordering: $localActivityEvents" }

            val sender = fixture.activitySender(resultIntent)
            child.startIntentSenderForResult(sender, 74, null, 0, 0, 0)
            settleActivityFrames()
            val senderResult = HostedActivityProcess.currentActivity() as ActivityGroupResultActivity
            senderResult.setResult(Activity.RESULT_OK, Intent().putExtra("group-result", "sender"))
            senderResult.finish()
            settleActivityFrames()
            check(HostedActivityProcess.currentActivity() === group)
            check(child.resultRequestCode == 74 && child.resultMarker == "sender")

            // LocalActivityManager.saveInstanceState only saves a stopped child
            // if it already has a snapshot. Modern parent save ordering is after
            // stop, so explicitly snapshot the resumed child before this restore test.
            val childSnapshot = checkNotNull(manager.saveInstanceState())
            check(childSnapshot.getBundle("group-child")?.getString("local-state") == "group-child")
            localActivityEvents.clear()
            activityGroupEvents.clear()
            group.recreate()
            settleActivityFrames()
            check(HostedActivityProcess.currentActivity() === group) {
                "public recreate must ignore an in-process Binder activity token"
            }
            check(activityGroupEvents.isEmpty())
            check(runCatching { child.recreate() }.exceptionOrNull() is IllegalStateException)
            fixture.relaunch(group)
            val restoredGroup = HostedActivityProcess.currentActivity() as RecordingActivityGroup
            expectedLocalActivityParent = restoredGroup
            check(restoredGroup !== group)
            check(restoredGroup.currentActivity == null)
            check(activityGroupEvents == listOf(
                "group:pause",
                "group:stop",
                "group:save",
                "group:destroy",
                "group:create:saved",
                "group:start",
                "group:restore",
                "group:post-create:saved",
                "group:resume",
            )) { "ActivityGroup recreation ordering: $activityGroupEvents" }
            check(localActivityEvents == listOf(
                "group-child:pause",
                "group-child:stop",
                "group-child:save",
                "group-child:destroy",
            )) { "ActivityGroup child save/teardown ordering: $localActivityEvents" }

            localActivityEvents.clear()
            val restoredWindow = checkNotNull(
                restoredGroup.localActivityManager.startActivity("group-child", childIntent),
            )
            val restoredChild = restoredGroup.currentActivity as ActivityGroupChildActivity
            check(restoredChild !== child && restoredWindow !== childWindow)
            check(restoredGroup.localActivityManager.currentId == "group-child")
            check(localActivityEvents == listOf(
                "group-child:create:saved",
                "group-child:start",
                "group-child:restore",
                "group-child:post-create:saved",
                "group-child:resume",
            )) { "ActivityGroup child restore ordering: $localActivityEvents" }

            localActivityEvents.clear()
            activityGroupEvents.clear()
            val groupResult = RecordingProcessIntent("group-handler").apply {
                putExtra("group-result", "transaction-delivered")
            }
            val groupRecord = ActivityThread.ActivityClientRecord().apply {
                token = Binder()
                fixtureIntent = Intent().setComponent(ComponentName("org.example", groupName))
                fixtureActivityInfo = groupInfo
                fixtureActivity = restoredGroup
                setState(android.app.servertransaction.ActivityLifecycleItem.ON_RESUME)
            }
            withContentUriPermissionApis(true) {
                ActivityThread.currentActivityThread().handleSendResult(
                    groupRecord,
                    listOf(ResultInfo("group-child", 74, Activity.RESULT_OK, groupResult)),
                    "FrameworkModuleSmoke-ActivityGroup",
                )
            }
            check(groupRecord.lifecycleState ==
                android.app.servertransaction.ActivityLifecycleItem.ON_RESUME)
            check(restoredChild.resultRequestCode == 74)
            check(restoredChild.resultCode == Activity.RESULT_OK)
            check(restoredChild.resultMarker == "transaction-delivered")
            check(activityGroupEvents == listOf("group:pause", "group:resume"))
            check(localActivityEvents == listOf(
                "group-child:pause",
                "group-child:result",
                "group-child:resume",
            )) { "ActivityGroup transaction result ordering: $localActivityEvents" }
            check(groupResult.preparedForProcess && groupResult.fromProtectedComponent == android.os.StrictMode.vmUnsafeIntentLaunchEnabled())
            check(groupResult.extras?.classLoader === restoredGroup.classLoader)
            check(adapter.presentedActivities.none { it is ActivityGroupChildActivity }) {
                "ActivityGroup child escaped into the desktop presentation stack"
            }
        } finally {
            expectedLocalActivityParent = null
            HostedActivityProcess.shutdownActivitiesInProcess()
        }
    }

    private fun testActivityLifecycle(fixture: StockActivityFixture) {
        events.clear()
        createdThemeResIds.clear()
        val firstName = FirstActivity::class.java.name
        val secondName = SecondActivity::class.java.name
        val missingConfigurationSuperName = HostedMissingSuperConfigurationActivity::class.java.name
        val firstInfo = activityInfo(firstName).apply {
            configChanges = 0
            applicationInfo.targetSdkVersion = android.os.Build.VERSION_CODES.HONEYCOMB
            theme = 0x7f012345
        }
        val secondInfo = activityInfo(secondName).apply {
            applicationInfo.theme = 0x7f012346
        }
        val missingConfigurationSuperInfo = activityInfo(missingConfigurationSuperName).apply {
            configChanges = ActivityInfo.CONFIG_SCREEN_SIZE
        }
        val adapter = RecordingHostAdapter()

        fixture.configure(
            adapter,
            mapOf(
                firstName to firstInfo,
                secondName to secondInfo,
                missingConfigurationSuperName to missingConfigurationSuperInfo,
            ),
            resolver = adapter::resolveActivity,
        )
        val application = checkNotNull(ActivityThread.currentApplication())
        val applicationConfigurationCallback = object : android.content.ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                events += "application:config"
            }

            override fun onLowMemory() = Unit
        }
        application.registerComponentCallbacks(applicationConfigurationCallback)
        try {
            val first = fixture.launch(firstName)
            check(events == listOf("first:create", "first:start", "first:resume", "show:first")) {
                "main launch ordering: $events"
            }
            check(createdThemeResIds["first"] == firstInfo.getThemeResource()) {
                "activity theme must be applied before onCreate"
            }

            val senderIntent = Intent().setComponent(ComponentName("org.example", secondName))
            val canceledSender = fixture.activitySender(senderIntent, canceled = true)
            val canceled = runCatching {
                first.startIntentSenderForResult(canceledSender, 51, null, 0, 0, 0)
            }.exceptionOrNull()
            check(canceled is android.content.IntentSender.SendIntentException)
            check(HostedActivityProcess.currentActivity() === first && events.size == 4)
            first.startIntentSenderForResult(fixture.activitySender(senderIntent), -1, null, 0, 0, 0)
            settleActivityFrames()
            check(events.takeLast(7) == listOf(
                "first:pause",
                "second:create",
                "second:start",
                "second:resume",
                "show:second",
                "first:save",
                "first:stop",
            )) { "second launch ordering: $events" }
            check(createdThemeResIds["second"] == secondInfo.getThemeResource()) {
                "application theme fallback must be applied before onCreate"
            }

            checkNotNull(HostedActivityProcess.currentActivity()).finish()
            settleActivityFrames()
            check(HostedActivityProcess.currentActivity() === first)
            check(events.takeLast(6) == listOf(
                "second:pause",
                "first:start",
                "first:resume",
                "second:stop",
                "second:destroy",
                "show:first",
            )) { "finish ordering: $events" }

            val shareTarget = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "https://example.com/shared")
            val chooser = Intent.createChooser(shareTarget, "Share")
            adapter.implicitActivity = ComponentName("org.example", secondName)
            first.startActivity(chooser)
            settleActivityFrames()
            val resolvedShare = checkNotNull(HostedActivityProcess.currentActivity())
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
            settleActivityFrames()
            check(HostedActivityProcess.currentActivity() === first)
            adapter.implicitActivity = null

            // Stock process callbacks require an actual public configuration change;
            // an arbitrary change mask with an unchanged Configuration is insufficient.
            val handledConfiguration = Configuration(application.resources.configuration).apply {
                seq = 0
                screenWidthDp += 10
                screenHeightDp += 10
            }
            check(firstInfo.configChanges and ActivityInfo.CONFIG_SCREEN_SIZE == 0)
            check(firstInfo.getRealConfigChanged() and ActivityInfo.CONFIG_SCREEN_SIZE != 0) {
                "pre-HONEYCOMB_MR2 ActivityInfo omitted the implicit screen-size change"
            }
            HostedActivityProcess.applyConfigurationInProcess(
                handledConfiguration,
                ActivityInfo.CONFIG_SCREEN_SIZE,
                600,
                900,
            )
            check(events.takeLast(3) == listOf(
                "application:config",
                "resize:first:600x900",
                "first:config",
            )) {
                "handled configuration ordering: $events"
            }
            check(first.changingConfigurations == 0)

            first.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            changingConfigurationEvents.clear()
            val relaunchEventStart = events.size
            handledConfiguration.orientation = if (handledConfiguration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE) Configuration.ORIENTATION_PORTRAIT
                else Configuration.ORIENTATION_LANDSCAPE
            HostedActivityProcess.applyConfigurationInProcess(
                handledConfiguration,
                ActivityInfo.CONFIG_ORIENTATION,
                900,
                600,
            )
            val relaunchEvents = events.drop(relaunchEventStart)
            check(relaunchEvents.firstOrNull() == "application:config") {
                "Application configuration callback did not precede relaunch: $relaunchEvents"
            }
            check(relaunchEvents.indexOf("application:config") < relaunchEvents.indexOf("first:save")) {
                "Application configuration callback did not precede Activity teardown: " +
                    relaunchEvents
            }
            check(HostedActivityProcess.currentActivity() !== first)
            check(events.contains("first:save"))
            check(events.takeLast(5) == listOf(
                "first:create:saved",
                "first:start",
                "first:restore",
                "first:resume",
                "show:first",
            )) { "relaunch restore ordering: $events" }
            check(HostedActivityProcess.currentActivity()?.requestedOrientation ==
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                "configuration relaunch lost the activity's requested orientation"
            }
            check(changingConfigurationEvents == listOf(
                "first:stop:${ActivityInfo.CONFIG_ORIENTATION}",
                "first:destroy:${ActivityInfo.CONFIG_ORIENTATION}",
            )) { "configuration relaunch did not expose its change mask during teardown" }

            changingConfigurationEvents.clear()
            fixture.relaunch(checkNotNull(HostedActivityProcess.currentActivity()))
            check(changingConfigurationEvents == listOf(
                "first:stop:0",
                "first:destroy:0",
            )) { "explicit Activity.recreate inherited a configuration-change mask" }

            events.clear()
            checkNotNull(HostedActivityProcess.currentActivity()).finish()
            settleActivityFrames()
            check(HostedActivityProcess.currentActivity() == null)
            check(events == listOf(
                "first:pause",
                "first:stop",
                "first:destroy",
                "clear",
            )) { "last Activity finish ordering: $events" }

            events.clear()
            handledConfiguration.orientation = if (handledConfiguration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE) Configuration.ORIENTATION_PORTRAIT
                else Configuration.ORIENTATION_LANDSCAPE
            check(!HostedActivityProcess.applyConfigurationInProcess(
                handledConfiguration,
                ActivityInfo.CONFIG_ORIENTATION,
                640,
                480,
            ))
            check(events == listOf("application:config")) {
                "process configuration was not dispatched without a current Activity: $events"
            }

            fixture.launch(missingConfigurationSuperName)
            handledConfiguration.screenWidthDp += 10
            val hostedMissingSuperFailure = runCatching {
                HostedActivityProcess.applyConfigurationInProcess(
                    handledConfiguration,
                    ActivityInfo.CONFIG_SCREEN_SIZE,
                    600,
                    900,
                )
            }.exceptionOrNull()
            check(hostedMissingSuperFailure is SuperNotCalledException) {
                "hosted handled configuration accepted a missing super call: " +
                    hostedMissingSuperFailure
            }
        } finally {
            HostedActivityProcess.shutdownActivitiesInProcess()
            application.unregisterComponentCallbacks(applicationConfigurationCallback)
        }
    }

    private fun testLegacyFragmentTransactionsAndRecreation() {
        legacyFragmentEvents.clear()
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            320,
            240,
            120,
            0,
        )
        val info = activityInfo(LegacyFragmentActivity::class.java.name)
        val first = LegacyFragmentActivity()
        first.attach(context, info)
        first.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) {
                legacyFragmentEvents += "callback:start"
            }

            override fun onActivityResumed(activity: Activity) {
                legacyFragmentEvents += "callback:resume"
            }

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        first.performCreate(null)
        val original = checkNotNull(first.fragment)
        first.performStart()
        first.performPostCreate(null)
        first.performResume()
        check(legacyFragmentEvents == listOf(
            "activity:create",
            "fragment:create",
            "fragment:activity-created",
            "activity:start",
            "callback:start",
            "fragment:start",
            "activity:resume",
            "callback:resume",
            "fragment:resume",
        )) { "legacy fragment launch ordering: $legacyFragmentEvents" }

        val savedState = Bundle()
        first.performSaveInstanceState(savedState)
        legacyFragmentEvents.clear()
        first.performPause()
        first.performStop()
        first.performDestroy()
        check(legacyFragmentEvents == listOf(
            "fragment:pause",
            "activity:pause",
            "fragment:stop",
            "activity:stop",
            "fragment:destroy",
            "activity:destroy",
        )) { "legacy fragment teardown ordering: $legacyFragmentEvents" }

        legacyFragmentEvents.clear()
        val restoredActivity = LegacyFragmentActivity()
        restoredActivity.attach(context, info)
        restoredActivity.performCreate(savedState)
        val restored = checkNotNull(restoredActivity.fragment)
        check(restored !== original) { "saved fragment state reused the destroyed instance" }
        check(restored.arguments?.getString("argument") == "preserved")
        check(restored.restoredMarker == "saved")
        restoredActivity.performStart()
        restoredActivity.performRestoreInstanceState(savedState)
        restoredActivity.performPostCreate(savedState)
        restoredActivity.performResume()
        check(legacyFragmentEvents == listOf(
            "activity:create:saved",
            "fragment:create:saved",
            "fragment:activity-created",
            "activity:start",
            "fragment:start",
            "activity:resume",
            "fragment:resume",
        )) { "legacy fragment restore ordering: $legacyFragmentEvents" }
        restoredActivity.performPause()
        restoredActivity.performStop()
        restoredActivity.performDestroy()
    }

    private fun testLegacyFragmentHostedResultsAndRetention(fixture: StockActivityFixture) {
        val hostName = HostedLegacyFragmentActivity::class.java.name
        val resultName = LegacyResultActivity::class.java.name
        fixture.configure(
            LegacyFragmentHostAdapter(),
            mapOf(hostName to activityInfo(hostName), resultName to activityInfo(resultName)),
        )
        try {
            val first = fixture.launch(hostName) as HostedLegacyFragmentActivity
            val retained = checkNotNull(first.fragment)
            check(retained.createCount == 1)

            retained.requestPermissions(arrayOf(android.Manifest.permission.INTERNET), 73)
            settleActivityFrames()
            check(retained.permissionRequestCode == 73)
            check(retained.permissionNames.contentEquals(arrayOf(android.Manifest.permission.INTERNET)))
            // org.example does not declare INTERNET; the controller must preserve denial.
            check(retained.permissionResults.contentEquals(intArrayOf(PackageManager.PERMISSION_DENIED)))

            HostedActivityProcess.applyConfigurationInProcess(
                Configuration().apply { setToDefaults() },
                ActivityInfo.CONFIG_ORIENTATION,
                900,
                600,
            )
            val replacement = HostedActivityProcess.currentActivity() as HostedLegacyFragmentActivity
            check(replacement !== first)
            check(replacement.fragment === retained) {
                "configuration recreation did not retain the legacy Fragment instance"
            }
            check(retained.activity === replacement) {
                "retained legacy Fragment was not rebound to the replacement Activity"
            }
            check(retained.createCount == 1) {
                "retained legacy Fragment was created more than once"
            }

            retained.startActivityForResult(
                Intent().setComponent(ComponentName("org.example", resultName)),
                91,
            )
            settleActivityFrames()
            val resultActivity = HostedActivityProcess.currentActivity() as LegacyResultActivity
            resultActivity.setResult(
                Activity.RESULT_OK,
                Intent().putExtra("legacy-result", "delivered"),
            )
            HostedActivityProcess.applyConfigurationInProcess(
                Configuration().apply { setToDefaults() },
                ActivityInfo.CONFIG_ORIENTATION,
                640,
                480,
            )
            val recreatedResultActivity = HostedActivityProcess.currentActivity() as LegacyResultActivity
            check(recreatedResultActivity !== resultActivity)
            // Result values belong to the Activity instance; the application
            // supplies them again after recreation. ATMS retains the recipient.
            recreatedResultActivity.setResult(Activity.RESULT_OK,
                Intent().putExtra("legacy-result", "delivered"))
            recreatedResultActivity.finish()
            settleActivityFrames()
            check(HostedActivityProcess.currentActivity() === replacement)
            check(retained.activityResultRequestCode == 91)
            check(retained.activityResultCode == Activity.RESULT_OK)
            check(retained.activityResultData == "delivered")
        } finally {
            HostedActivityProcess.shutdownActivitiesInProcess()
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

    private class RecordingHostAdapter : HostedActivityProcess.HostAdapter {
        var implicitActivity: ComponentName? = null
        var lastImplicitIntent: Intent? = null



        override fun showActivity(activity: Activity) {
            events += "show:${(activity as RecordingActivity).label}"
        }

        override fun clearActivityWindows() {
            events += "clear"
        }

        override fun resizeActivityRoot(activity: Activity, width: Int, height: Int) {
            events += "resize:${(activity as RecordingActivity).label}:${width}x$height"
        }

        fun resolveActivity(intent: Intent): ComponentName? {
            lastImplicitIntent = Intent(intent)
            return implicitActivity
        }
    }

    private class LocalActivityHostAdapter : HostedActivityProcess.HostAdapter {
        val presentedActivities = mutableListOf<Activity>()



        override fun showActivity(activity: Activity) {
            presentedActivities += activity
        }

        override fun clearActivityWindows() = Unit

        override fun resizeActivityRoot(activity: Activity, width: Int, height: Int) = Unit

    }

    private class LegacyFragmentHostAdapter : HostedActivityProcess.HostAdapter {
        override fun showActivity(activity: Activity) {}
        override fun clearActivityWindows() {}
        override fun resizeActivityRoot(activity: Activity, width: Int, height: Int) {}
    }

    class HostedLegacyFragmentActivity : Activity() {
        var fragment: HostedLegacyFragment? = null
            private set

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val manager = fragmentManager
            val restored = manager.findFragmentByTag(HOSTED_FRAGMENT_TAG)
            if (restored != null) {
                fragment = restored as HostedLegacyFragment
                return
            }
            val added = HostedLegacyFragment().apply { retainInstance = true }
            manager.beginTransaction().add(added, HOSTED_FRAGMENT_TAG).commit()
            check(manager.executePendingTransactions())
            fragment = added
        }

        companion object {
            private const val HOSTED_FRAGMENT_TAG = "hosted-legacy-fragment"
        }
    }

    class HostedLegacyFragment : android.app.Fragment() {
        var createCount = 0
            private set
        var permissionRequestCode = -1
            private set
        var permissionNames = emptyArray<String>()
            private set
        var permissionResults = IntArray(0)
            private set
        var activityResultRequestCode = -1
            private set
        var activityResultCode = Activity.RESULT_CANCELED
            private set
        var activityResultData: String? = null
            private set

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            createCount++
        }

        override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray,
        ) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            permissionRequestCode = requestCode
            permissionNames = Array(permissions.size) { permissions[it] }
            permissionResults = grantResults.copyOf()
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)
            activityResultRequestCode = requestCode
            activityResultCode = resultCode
            activityResultData = data?.getStringExtra("legacy-result")
        }
    }

    class LegacyResultActivity : Activity()

    class RecordingDialog(context: Context, themeResId: Int) : android.app.Dialog(context, themeResId) {
        val lifecycleEvents = mutableListOf<String>()
        lateinit var input: android.widget.EditText
            private set

        override fun onCreate(savedInstanceState: Bundle?) {
            lifecycleEvents += if (savedInstanceState == null) "create" else "create:saved"
            super.onCreate(savedInstanceState)
            input = android.widget.EditText(context).apply {
                id = 0x1020f00
                isSaveEnabled = true
            }
            setContentView(input)
        }

        override fun onStart() {
            lifecycleEvents += "start"
            super.onStart()
        }

        override fun onStop() {
            lifecycleEvents += "stop"
            super.onStop()
        }
    }

    class LegacyFragmentActivity : Activity() {
        var fragment: RecordingLegacyFragment? = null
            private set

        override fun onCreate(savedInstanceState: Bundle?) {
            legacyFragmentEvents += if (savedInstanceState == null) {
                "activity:create"
            } else {
                "activity:create:saved"
            }
            super.onCreate(savedInstanceState)
            val manager = fragmentManager
            if (savedInstanceState == null) {
                val added = RecordingLegacyFragment().apply {
                    arguments = Bundle().apply { putString("argument", "preserved") }
                }
                manager.beginTransaction().add(added, LEGACY_FRAGMENT_TAG).commit()
                check(manager.findFragmentByTag(LEGACY_FRAGMENT_TAG) == null) {
                    "legacy FragmentTransaction committed synchronously"
                }
                check(manager.executePendingTransactions()) {
                    "legacy FragmentManager did not execute its pending transaction"
                }
                check(manager.findFragmentByTag(LEGACY_FRAGMENT_TAG) === added)
                fragment = added
            } else {
                fragment = manager.findFragmentByTag(LEGACY_FRAGMENT_TAG)
                    as? RecordingLegacyFragment
            }
        }

        override fun onStart() {
            legacyFragmentEvents += "activity:start"
            super.onStart()
        }

        override fun onResume() {
            legacyFragmentEvents += "activity:resume"
            super.onResume()
        }

        override fun onPause() {
            legacyFragmentEvents += "activity:pause"
            super.onPause()
        }

        override fun onStop() {
            legacyFragmentEvents += "activity:stop"
            super.onStop()
        }

        override fun onDestroy() {
            legacyFragmentEvents += "activity:destroy"
            super.onDestroy()
        }

        companion object {
            private const val LEGACY_FRAGMENT_TAG = "legacy-fragment"
        }
    }

    class RecordingLegacyFragment : android.app.Fragment() {
        var restoredMarker: String? = null
            private set

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            restoredMarker = savedInstanceState?.getString("fragment-marker")
            legacyFragmentEvents += if (restoredMarker == "saved") {
                "fragment:create:saved"
            } else {
                "fragment:create"
            }
        }

        override fun onActivityCreated(savedInstanceState: Bundle?) {
            super.onActivityCreated(savedInstanceState)
            legacyFragmentEvents += "fragment:activity-created"
        }

        override fun onStart() {
            super.onStart()
            legacyFragmentEvents += "fragment:start"
        }

        override fun onResume() {
            super.onResume()
            legacyFragmentEvents += "fragment:resume"
        }

        override fun onPause() {
            super.onPause()
            legacyFragmentEvents += "fragment:pause"
        }

        override fun onStop() {
            super.onStop()
            legacyFragmentEvents += "fragment:stop"
        }

        override fun onDestroy() {
            super.onDestroy()
            legacyFragmentEvents += "fragment:destroy"
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)
            outState.putString("fragment-marker", "saved")
        }
    }

    class RecordingProcessIntent(val resultLabel: String) : Intent() {
        var preparedForProcess = false
            private set
        var fromProtectedComponent = false
            private set

        override fun prepareToEnterProcess(
            fromProtectedComponent: Boolean,
            source: AttributionSource?,
        ) {
            preparedForProcess = true
            this.fromProtectedComponent = fromProtectedComponent
            super.prepareToEnterProcess(fromProtectedComponent, source)
        }
    }

    class RecordingReferrerIntent : ReferrerIntent {
        var preparedForProcess = false
            private set
        var fromProtectedComponent = false
            private set

        constructor(intent: Intent, referrer: String) : super(intent, referrer)

        constructor(intent: Intent, referrer: String, callerToken: android.os.IBinder) :
            super(intent, referrer, callerToken)

        override fun prepareToEnterProcess(
            fromProtectedComponent: Boolean,
            source: AttributionSource?,
        ) {
            preparedForProcess = true
            this.fromProtectedComponent = fromProtectedComponent
            super.prepareToEnterProcess(fromProtectedComponent, source)
        }
    }

    class MalformedReferrerIntent : Intent() {
        override fun <T : Any?> getParcelableExtra(name: String?, clazz: Class<T>): T? {
            throw android.os.BadParcelableException("malformed referrer")
        }
    }

    class TransactionCompletionActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            transactionCompletionEvents += "create"
            super.onCreate(savedInstanceState)
        }

        override fun onCreate(savedInstanceState: Bundle?, persistentState: PersistableBundle?) {
            check(persistentState?.getString("persistent") == "kept")
            super.onCreate(savedInstanceState, persistentState)
        }

        override fun onSaveInstanceState(outState: Bundle, outPersistentState: PersistableBundle) {
            super.onSaveInstanceState(outState, outPersistentState)
            outPersistentState.putString("persistent", "saved-persistent")
        }

        override fun onStart() {
            transactionCompletionEvents += "start"
            super.onStart()
        }

        override fun onPostCreate(savedInstanceState: Bundle?) {
            transactionCompletionEvents += "post-create"
            super.onPostCreate(savedInstanceState)
        }

        override fun onResume() {
            transactionCompletionEvents += "resume"
            super.onResume()
        }

        override fun onPause() {
            transactionCompletionEvents += "pause"
            super.onPause()
        }

        override fun onCreateDescription(): CharSequence {
            transactionCompletionEvents += "description"
            return "transaction-description"
        }

        override fun onStop() {
            transactionCompletionEvents += "stop"
            super.onStop()
        }

        override fun onSaveInstanceState(outState: Bundle) {
            transactionCompletionEvents += "save"
            super.onSaveInstanceState(outState)
            outState.putString("saved", "state")
        }

        override fun onDestroy() {
            transactionCompletionEvents += "destroy"
            super.onDestroy()
        }
    }

    class ResultDeliveryActivity : Activity() {
        override fun onPause() {
            activityResultEvents += "pause"
            super.onPause()
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            val result = data as RecordingProcessIntent
            activityResultEvents +=
                "result:${result.resultLabel}:$requestCode:$resultCode:" +
                result.getStringExtra("result-payload")
            super.onActivityResult(requestCode, resultCode, data)
        }

        override fun onResume() {
            activityResultEvents += "resume"
            super.onResume()
        }
    }

    class CallerAwareResultActivity : Activity() {
        val callbackKinds = mutableListOf<String>()
        var observedCaller: ComponentCaller? = null
            private set
        var currentCallerMatchedCallback = false
            private set

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            callbackKinds += "legacy"
            check(runCatching { currentCaller }.exceptionOrNull() is IllegalStateException)
        }

        override fun onActivityResult(
            requestCode: Int,
            resultCode: Int,
            data: Intent?,
            caller: ComponentCaller,
        ) {
            callbackKinds += "caller"
            observedCaller = caller
            currentCallerMatchedCallback = currentCaller === caller
        }
    }

    class MissingSuperPauseActivity : Activity() {
        var pauseCalls = 0
            private set

        override fun onPause() {
            pauseCalls++
        }
    }

    class RelaunchActivity : Activity() {
        // Synthetic activities share one LoadedApk; expose the per-case application
        // metadata used by stock ActivityClientRecord.isPreP during the relaunch.
        override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo(super.getApplicationInfo()).apply {
            targetSdkVersion = activityInfo?.applicationInfo?.targetSdkVersion ?: targetSdkVersion
        }

        private var generation = 0
        var observedNewIntentCaller: ComponentCaller? = null
            private set
        var currentCallerMatchedCallback = false
            private set
        var observedReferrer: String? = null
            private set

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            generation = ++activityRelaunchGeneration
            activityRelaunchEvents += "$generation:create:" +
                (savedInstanceState?.getString("relaunch-state") ?: "fresh") + ":" +
                (lastNonConfigurationInstance ?: "no-retained") + ":" +
                (intent.getStringExtra("relaunch-custom") ?: "launch")
        }

        override fun onStart() {
            activityRelaunchEvents += "$generation:start"
            super.onStart()
        }

        override fun onRestoreInstanceState(savedInstanceState: Bundle) {
            activityRelaunchEvents +=
                "$generation:restore:${savedInstanceState.getString("relaunch-state")}"
            super.onRestoreInstanceState(savedInstanceState)
        }

        override fun onPostCreate(savedInstanceState: Bundle?) {
            activityRelaunchEvents +=
                "$generation:post-create:${savedInstanceState?.getString("relaunch-state")}"
            super.onPostCreate(savedInstanceState)
        }

        override fun onResume() {
            activityRelaunchEvents += "$generation:resume"
            super.onResume()
        }

        override fun onStateNotSaved() {
            activityRelaunchEvents += "$generation:state-not-saved"
            super.onStateNotSaved()
        }

        override fun onPause() {
            activityRelaunchEvents +=
                "$generation:pause:${resources.configuration.orientation}"
            super.onPause()
        }

        override fun onStop() {
            activityRelaunchEvents +=
                "$generation:stop:${isChangingConfigurations}:${changingConfigurations}"
            super.onStop()
        }

        override fun onSaveInstanceState(outState: Bundle) {
            activityRelaunchEvents += "$generation:save"
            super.onSaveInstanceState(outState)
            outState.putString("relaunch-state", "saved")
        }

        override fun onRetainNonConfigurationInstance(): Any {
            activityRelaunchEvents += "$generation:retain"
            return "retained"
        }

        override fun onDestroy() {
            activityRelaunchEvents += "$generation:destroy:$isChangingConfigurations"
            super.onDestroy()
        }

        override fun onNewIntent(intent: Intent) {
            val callerState = if (runCatching { currentCaller }.isSuccess) {
                "caller"
            } else {
                "no-caller"
            }
            observedReferrer = referrer?.toString()
            activityRelaunchEvents +=
                "$generation:new-intent-legacy:" +
                "${intent.getStringExtra("relaunch-intent")}:$callerState"
            super.onNewIntent(intent)
        }

        override fun onNewIntent(intent: Intent, caller: ComponentCaller) {
            observedNewIntentCaller = caller
            currentCallerMatchedCallback = currentCaller === caller
            observedReferrer = referrer?.toString()
            activityRelaunchEvents +=
                "$generation:new-intent-caller:${intent.getStringExtra("relaunch-intent")}:" +
                currentCallerMatchedCallback
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            activityRelaunchEvents +=
                "$generation:result:$requestCode:$resultCode:" +
                data?.getStringExtra("relaunch-result")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private var configurationContextOverride: Configuration? = null
    class ConfigurationDispatchActivity : Activity() {
        var movedDisplayId = Display.INVALID_DISPLAY
            private set

        override fun onMovedToDisplay(displayId: Int, configuration: Configuration) {
            movedDisplayId = displayId
            super.onMovedToDisplay(displayId, configuration)
        }

        override fun attachBaseContext(base: Context) {
            configurationContextOverride?.let(::applyOverrideConfiguration)
            super.attachBaseContext(base)
        }
        var callbackConfiguration: Configuration? = null
            private set
        var resourcesMatchedDuringCallback = false
            private set
        override fun onConfigurationChanged(newConfig: Configuration) {
            activityConfigurationEvents += "config"
            callbackConfiguration = Configuration(newConfig)
            resourcesMatchedDuringCallback =
                resources.configuration.orientation == newConfig.orientation &&
                    resources.configuration.fontScale == newConfig.fontScale
            super.onConfigurationChanged(newConfig)
        }
    }

    class MissingSuperConfigurationActivity : Activity() {
        var resourcesOrientationDuringCallback = Configuration.ORIENTATION_UNDEFINED
            private set

        override fun onConfigurationChanged(newConfig: Configuration) {
            resourcesOrientationDuringCallback = resources.configuration.orientation
        }
    }

    class RecordingActivityGroup : ActivityGroup() {
        override fun onCreate(savedInstanceState: Bundle?) {
            activityGroupEvents += if (savedInstanceState?.getString("group-state") == "saved") {
                "group:create:saved"
            } else {
                "group:create"
            }
            super.onCreate(savedInstanceState)
        }

        override fun onStart() {
            activityGroupEvents += "group:start"
            super.onStart()
        }

        override fun onRestoreInstanceState(savedInstanceState: Bundle) {
            check(savedInstanceState.getString("group-state") == "saved")
            activityGroupEvents += "group:restore"
            super.onRestoreInstanceState(savedInstanceState)
        }

        override fun onPostCreate(savedInstanceState: Bundle?) {
            activityGroupEvents += if (savedInstanceState?.getString("group-state") == "saved") {
                "group:post-create:saved"
            } else {
                "group:post-create"
            }
            super.onPostCreate(savedInstanceState)
        }

        override fun onResume() {
            activityGroupEvents += "group:resume"
            super.onResume()
        }

        override fun onSaveInstanceState(outState: Bundle) {
            activityGroupEvents += "group:save"
            super.onSaveInstanceState(outState)
            outState.putString("group-state", "saved")
        }

        override fun onPause() {
            activityGroupEvents += "group:pause"
            super.onPause()
        }

        override fun onStop() {
            activityGroupEvents += "group:stop"
            super.onStop()
        }

        override fun onDestroy() {
            activityGroupEvents += "group:destroy"
            super.onDestroy()
        }
    }

    class ActivityGroupChildActivity : LocalRecordingActivity("group-child") {
        var resultRequestCode = -1
            private set
        var resultCode = Activity.RESULT_CANCELED
            private set
        var resultMarker: String? = null
            private set

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            localActivityEvents += "group-child:result"
            resultRequestCode = requestCode
            this.resultCode = resultCode
            resultMarker = data?.getStringExtra("group-result")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    class ActivityGroupResultActivity : Activity()

    class LocalParentActivity : Activity()

    private fun testTaskRemovalServices(context: Context, packages: HostedPackageManagerService, pkg: String) {
        val reports = HostedActivityManagerService()
        for (system in listOf(false, true)) {
            val completed = java.util.concurrent.CountDownLatch(1)
            var failure: Throwable? = null
            val worker = Thread {
                try {
                    check(!reports.handleApplicationWtf(null, "lock-order-fixture", system,
                        android.app.ApplicationErrorReport.ParcelableCrashInfo(IllegalStateException("fixture")),
                        if (system) -1 else android.os.Process.myPid()))
                } catch (error: Throwable) { failure = error }
                finally { completed.countDown() }
            }
            val returned = synchronized(reports.windowServiceLock()) {
                worker.start()
                completed.await(2, java.util.concurrent.TimeUnit.SECONDS)
            }
            worker.join(5000)
            check(!worker.isAlive && returned) { "System WTF took the AMS lock before posting" }
            failure?.let { throw it }
        }
        drainMainQueue()
        val am = HostedActivityManagerService(context).apply { packageService = packages }
        val thread = CapturingApplicationThread()
        val uid = Binder.getCallingUid()
        packages.bindPackageUid(pkg, uid)
        am.noteProcessPackage(uid, pkg, pkg)
        am.attachApplication(thread, 0)
        fun intent(name: String) = Intent().setClassName(pkg, "$pkg.$name")
        val base = intent("TaskActivity").setAction("task-removed")
        fun start(name: String) = checkNotNull(am.startService(thread, intent(name), null, false, pkg, null, 0))
        // Removal queued between starts must keep the last start ID, including
        // when the first start is waiting for Application.onCreate to finish.
        start("TaskService")
        am.cleanUpServices(0, base.component!!, base)
        start("TaskService")
        check(thread.serviceEvents.isEmpty())
        am.finishAttachApplication(0, 0)
        check(thread.serviceEvents == listOf("create:TaskService", "args:TaskService:false:1:null",
            "args:TaskService:true:1:task-removed", "args:TaskService:false:2:null")) { thread.serviceEvents }
        thread.serviceEvents.clear()
        am.cleanUpServices(10, base.component!!, base)
        am.cleanUpServices(0, ComponentName("unrelated", "Activity"), base)
        check(thread.serviceEvents.isEmpty())
        val connection = object : android.app.IServiceConnection.Stub() {
            override fun connected(name: ComponentName?, service: android.os.IBinder?, dead: Boolean) { }
        }
        fun bind(name: String, callback: android.app.IServiceConnection) {
            check(am.bindServiceInstance(thread, null, intent(name), null, callback,
                Context.BIND_AUTO_CREATE.toLong(), null, pkg, 0) == 1)
        }
        bind("BoundTaskService", connection)
        start("StopTaskService")
        thread.serviceEvents.clear()
        am.cleanUpServices(0, base.component!!, base)
        check(thread.serviceEvents == listOf("args:TaskService:true:2:task-removed", "stop:StopTaskService")) {
            thread.serviceEvents
        }
        // stopWithTask clears started ownership, while a live binding keeps the
        // instance alive until the final unbind. Bound-only services get no args.
        start("StopTaskService")
        val stopConnection = object : android.app.IServiceConnection.Stub() {
            override fun connected(name: ComponentName?, service: android.os.IBinder?, dead: Boolean) { }
        }
        bind("StopTaskService", stopConnection)
        thread.serviceEvents.clear()
        am.cleanUpServices(0, base.component!!, base)
        check(thread.serviceEvents == listOf("args:TaskService:true:2:task-removed")) { thread.serviceEvents }
        check(am.unbindService(stopConnection))
        check(thread.serviceEvents.last() == "stop:StopTaskService")
        check(am.unbindService(connection))
        check(am.stopService(thread, intent("TaskService"), null, 0) == 1)
        thread.serviceEvents.clear()
        am.cleanUpServices(0, base.component!!, base)
        check(thread.serviceEvents.isEmpty())
    }

    private class CapturingApplicationThread : UnsupportedApplicationThread() {
        val serviceEvents = mutableListOf<String>()
        private val serviceNames = mutableMapOf<android.os.IBinder, String>()
        override fun scheduleCreateService(token: android.os.IBinder?, info: android.content.pm.ServiceInfo?,
            compatInfo: android.content.res.CompatibilityInfo?, processState: Int) {
            val name = checkNotNull(info).name.substringAfterLast('.')
            serviceNames[checkNotNull(token)] = name
            serviceEvents.add("create:$name")
        }
        override fun scheduleServiceArgs(token: android.os.IBinder?, args: android.content.pm.ParceledListSlice<*>?) {
            for (value in checkNotNull(args).list) {
                val arg = value as android.app.ServiceStartArgs
                serviceEvents.add("args:${serviceNames[token]}:${arg.taskRemoved}:${arg.startId}:${arg.args?.action}")
            }
        }
        override fun scheduleStopService(token: android.os.IBinder?) {
            serviceEvents.add("stop:${serviceNames.remove(token)}")
        }
        override fun scheduleBindService(token: android.os.IBinder?, intent: Intent?, rebind: Boolean,
            processState: Int, bindSeq: Long) { }
        override fun scheduleUnbindService(token: android.os.IBinder?, intent: Intent?) { }
        val transactions = mutableListOf<ClientTransaction>()
        var boundProcessName: String? = null
        var boundPackageName: String? = null

        override fun bindApplication(
            packageName: String?, info: android.content.pm.ApplicationInfo?,
            sdkSandboxClientAppVolumeUuid: String?, sdkSandboxClientAppPackage: String?,
            isSdkInSandbox: Boolean, providerList: android.content.pm.ProviderInfoList?,
            testName: android.content.ComponentName?, profilerInfo: android.app.ProfilerInfo?,
            testArguments: android.os.Bundle?, testWatcher: android.app.IInstrumentationWatcher?,
            uiAutomationConnection: android.app.IUiAutomationConnection?, debugMode: Int,
            enableBinderTracking: Boolean, trackAllocation: Boolean,
            restrictedBackupMode: Boolean, persistent: Boolean,
            config: android.content.res.Configuration?, compatInfo: android.content.res.CompatibilityInfo?,
            services: MutableMap<*, *>?, coreSettings: android.os.Bundle?, buildSerial: String?,
            autofillOptions: android.content.AutofillOptions?, contentCaptureOptions: android.content.ContentCaptureOptions?,
            disabledCompatChanges: LongArray?, loggableCompatChanges: LongArray?,
            serializedSystemFontMap: android.os.SharedMemory?, applicationSharedMemoryFd: java.io.FileDescriptor?,
            startRequestedElapsedTime: Long, startRequestedUptime: Long,
        ) {
            check(transactions.isEmpty())
            checkNotNull(info)
            checkNotNull(providerList)
            checkNotNull(config)
            checkNotNull(compatInfo)
            check(!isSdkInSandbox && testName == null && profilerInfo == null)
            boundProcessName = packageName
            boundPackageName = info.packageName
        }

        override fun scheduleTransaction(transaction: ClientTransaction?) {
            check(boundProcessName != null) { "activity transaction arrived before application binding" }
            transactions += checkNotNull(transaction)
        }

        fun removeFirstTransaction(): ClientTransaction = transactions.removeAt(0)
    }

    open class LocalRecordingActivity(private val activityLabel: String) : Activity() {
        val receivedNewIntents = mutableListOf<Intent>()

        override fun onCreate(savedInstanceState: Bundle?) {
            check(isChild)
            check(parent === expectedLocalActivityParent)
            check(activityToken === checkNotNull(parent).activityToken)
            check(window.container === parent.window)
            localActivityEvents += if (
                savedInstanceState?.getString("local-state") == activityLabel
            ) {
                "$activityLabel:create:saved"
            } else {
                "$activityLabel:create"
            }
            super.onCreate(savedInstanceState)
        }

        override fun onStart() {
            localActivityEvents += "$activityLabel:start"
            super.onStart()
        }

        override fun onRestoreInstanceState(savedInstanceState: Bundle) {
            check(savedInstanceState.getString("local-state") == activityLabel)
            localActivityEvents += "$activityLabel:restore"
            super.onRestoreInstanceState(savedInstanceState)
        }

        override fun onPostCreate(savedInstanceState: Bundle?) {
            localActivityEvents += if (
                savedInstanceState?.getString("local-state") == activityLabel
            ) {
                "$activityLabel:post-create:saved"
            } else {
                "$activityLabel:post-create"
            }
            super.onPostCreate(savedInstanceState)
        }

        override fun onNewIntent(intent: Intent) {
            receivedNewIntents += Intent(intent)
            localActivityEvents += "$activityLabel:new-intent"
            super.onNewIntent(intent)
        }

        override fun onResume() {
            localActivityEvents += "$activityLabel:resume"
            super.onResume()
        }

        override fun onSaveInstanceState(outState: Bundle) {
            localActivityEvents += "$activityLabel:save"
            super.onSaveInstanceState(outState)
            outState.putString("local-state", activityLabel)
        }

        override fun onPause() {
            localActivityEvents += "$activityLabel:pause"
            super.onPause()
        }

        override fun onStop() {
            localActivityEvents += "$activityLabel:stop"
            super.onStop()
        }

        override fun onRestart() {
            localActivityEvents += "$activityLabel:restart"
            super.onRestart()
        }

        override fun onDestroy() {
            localActivityEvents += "$activityLabel:destroy"
            super.onDestroy()
        }
    }

    class LocalFirstActivity : LocalRecordingActivity("first") {
        // These synthetic children share the fixture's LoadedApk. Give the legacy child
        // its own application metadata so stock ActivityThread tests the pre-Honeycomb
        // branch through Activity.getApplicationInfo(), as a separately installed APK would.
        override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo(super.getApplicationInfo()).apply {
            targetSdkVersion = android.os.Build.VERSION_CODES.GINGERBREAD_MR1
        }
    }
    class LocalSecondActivity : LocalRecordingActivity("second")

    open class RecordingActivity(private val activityLabel: String) : Activity() {
        val label: String get() = activityLabel

        override fun onCreate(savedInstanceState: Bundle?) {
            createdThemeResIds[activityLabel] = themeResId
            events += if (savedInstanceState?.getString("state") == "saved") {
                "$activityLabel:create:saved"
            } else {
                "$activityLabel:create"
            }
            super.onCreate(savedInstanceState)
        }

        override fun onStart() {
            events += "$activityLabel:start"
            super.onStart()
        }

        override fun onResume() {
            events += "$activityLabel:resume"
            super.onResume()
        }

        override fun onPause() {
            events += "$activityLabel:pause"
            super.onPause()
        }

        override fun onStop() {
            events += "$activityLabel:stop"
            changingConfigurationEvents += "$activityLabel:stop:$changingConfigurations"
            super.onStop()
        }

        override fun onDestroy() {
            events += "$activityLabel:destroy"
            changingConfigurationEvents += "$activityLabel:destroy:$changingConfigurations"
            super.onDestroy()
        }

        override fun onConfigurationChanged(newConfig: Configuration) {
            events += "$activityLabel:config"
            super.onConfigurationChanged(newConfig)
        }

        override fun onSaveInstanceState(outState: Bundle) {
            events += "$activityLabel:save"
            super.onSaveInstanceState(outState)
            outState.putString("state", "saved")
        }

        override fun onRestoreInstanceState(savedInstanceState: Bundle) {
            check(savedInstanceState.getString("state") == "saved")
            events += "$activityLabel:restore"
            super.onRestoreInstanceState(savedInstanceState)
        }
    }

    class FirstActivity : RecordingActivity("first") {
        // Synthetic activities share one LoadedApk, while this fixture exercises older target
        // SDK lifecycle rules. Expose its declared metadata through the stock Context API.
        override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo(super.getApplicationInfo()).apply {
            targetSdkVersion = activityInfo?.applicationInfo?.targetSdkVersion ?: targetSdkVersion
        }
    }
    class SecondActivity : RecordingActivity("second")
    class HostedMissingSuperConfigurationActivity : RecordingActivity("missing-config") {
        override fun onConfigurationChanged(newConfig: Configuration) {
            events += "missing-config:config"
        }
    }
}
