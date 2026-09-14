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
import android.content.res.Configuration
import dev.artzig.testing.HostedConfigurationController
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
import android.util.Rational
import android.util.MergedConfiguration
import android.util.SparseArray
import android.util.proto.ProtoOutputStream
import android.view.Choreographer
import android.view.contentcapture.ContentCaptureEvent
import android.view.contentcapture.IContentCaptureManager
import android.view.contentprotection.ContentProtectionEventProcessor
import dev.artzig.window.HostedWindowPresentation
import android.view.KeyCharacterMap
import android.view.Display
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.window.ActivityWindowInfo
import android.window.IWindowContainerToken
import android.window.TaskSnapshot
import android.window.WindowContainerToken
import dev.artzig.system.HostedActivityManagerService
import dev.artzig.system.HostedPackageManagerService
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import android.widget.EditText
import com.android.internal.util.RingBuffer
import com.android.internal.util.TraceBuffer
import com.android.internal.content.ReferrerIntent
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.X509TrustManager
import javax.microedition.khronos.opengles.GL10

/** Behavioral smoke tests whose surface is the hosted framework modules. */
internal object FrameworkModuleSmoke {
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
        StockIcuSmoke.run(ActivityThread.currentActivityThread().systemContext)
        StockResourcePathSmoke.run()
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
        StockEglWindowSmoke.run()
        StockLegacyEglSmoke.run()
        testStockGraphicBuffers()
        testStockOverlayProperties()
        dev.artzig.window.HostedLogicalDisplaySmoke.run()
        dev.artzig.window.WaylandTaskInputSmoke.run()
        dev.artzig.testing.SessionJournalSmoke.run()
        dev.artzig.testing.SessionObservationSmoke.run()
        dev.artzig.window.WaylandPointerCaptureSmoke.run()
        dev.artzig.window.WaylandKeyboardMapSmoke.run()
        com.android.server.wm.StockSurfaceSyncSmoke.run()
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
        dev.artzig.system.HostedProcessInfoSmoke.run()
        dev.artzig.system.HostedVideoBatteryUsageSmoke.run()
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
        testSystemUiContextCaching()
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
        testChoreographerPhaseOrder()
        testSimpleRendererContracts()
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
        StockToneSmoke.run()
        StockAudioFoundationSmoke.run()
        StockVoiceTypesSmoke.run()
        StockMainlineTypesSmoke.run()
        StockCronetConfigSmoke.run()
        StockHttpEngineSmoke.run()
        StockMediaStoreSmoke.run()
        StockServiceRegistrySmoke.run()
        StockOverlaySmoke.run()
        StockClientRestorationSmoke.run()
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
        com.android.server.uri.StockUriPermissionSmoke.withService {
            testHostedTaskServices()
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
            testNetworkServiceDiscovery()
            testEditableTextSavedStateRoundTrips()
            testWindowPresentation()

        }
        testHostedPermissionGrants()
        println("hosted framework module smoke: PASS")
    }

    private fun testStockRuntimeInitialization() {
        val environment = com.android.internal.ravenwood.RavenwoodEnvironment.getInstance()
        check(!environment.isRunningOnRavenwood())
        check(runCatching { environment.fromAddress<Any>(1) }.exceptionOrNull() is UnsupportedOperationException)

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
                    check(peer.peerCredentials.gid == android.system.Os.getgid())
                    val credentials = android.system.Os.getsockoptUcred(peer.fileDescriptor,
                        android.system.OsConstants.SOL_SOCKET, android.system.OsConstants.SO_PEERCRED)
                    check(credentials.pid == android.os.Process.myPid())
                    check(credentials.uid == android.system.Os.getuid() && credentials.gid == android.system.Os.getgid())
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
            dev.artzig.system.HostedLegacyPermissionManagerInternal(HostedPackageManagerService(
            dev.artzig.system.HostedUserManagerInternal(dev.artzig.system.HostedUserLifecycle()))))
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
        val packages = HostedPackageManagerService(
            dev.artzig.system.HostedUserManagerInternal(dev.artzig.system.HostedUserLifecycle()))
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
        // Binding this process does not create a visible activity or service.
        check(process.processState == ActivityManager.PROCESS_STATE_CACHED_EMPTY)
        check(process.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED)
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
            // Pinned EGL14 JNI converts the entire remainder of the config array.
            // Match its output extent while still checking a nonzero offset.
            val configs = arrayOf(original, original)
            val attributes = intArrayOf(android.opengl.EGL14.EGL_RENDERABLE_TYPE,
                android.opengl.EGL14.EGL_OPENGL_ES2_BIT, android.opengl.EGL14.EGL_SURFACE_TYPE,
                android.opengl.EGL14.EGL_PBUFFER_BIT, android.opengl.EGL14.EGL_NONE)
            check(android.opengl.EGL14.eglChooseConfig(display, attributes, 0, configs, 1, 1, number, 1))
            check(configs[0] === original && number[1] == 1)
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

        // Parse the configured PEM bundle independently through stock Java APIs;
        // native startup supplies the directory through the pinned BoringSSL APIs.
        val certificateFactory = java.security.cert.CertificateFactory.getInstance("X.509")
        val reference = LinkedHashSet<java.security.cert.X509Certificate>()
        var block: StringBuilder? = null
        File(checkNotNull(System.getProperty("artzig.ca.bundle"))).forEachLine(Charsets.US_ASCII) { line ->
            if (line == "-----BEGIN CERTIFICATE-----") {
                check(block == null)
                block = StringBuilder()
            }
            block?.let { pem ->
                pem.append(line).append('\n')
                if (line == "-----END CERTIFICATE-----") {
                    reference += certificateFactory.generateCertificate(pem.toString().byteInputStream()) as
                        java.security.cert.X509Certificate
                    block = null
                }
            }
        }
        check(block == null && reference.isNotEmpty())
        val referenceFactory = javax.net.ssl.TrustManagerFactory.getInstance("PKIX", "HarmonyJSSE")
        referenceFactory.init(null as java.security.KeyStore?)
        check((referenceFactory.trustManagers.single() as X509TrustManager).acceptedIssuers.toSet() == reference)
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
            // Query the session-owned display. Per-task resize must not rewrite
            // this registry; actual resize/configuration is covered by APK gates.
            val info = checkNotNull(android.hardware.display.DisplayManagerGlobal.getInstance()
                .getDisplayInfo(android.view.Display.DEFAULT_DISPLAY))
            val size = android.graphics.Point()
            display.getRealSize(size)
            check(size.x == info.logicalWidth && size.y == info.logicalHeight) {
                "display size $size differs from session registry ${info.logicalWidth}x${info.logicalHeight}"
            }
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
        val configurationCopy = ActivityManager.getService().configuration
        val originalConfiguration = Configuration(configurationCopy)
        check(!configurationCopy.userSetLocale)
        configurationCopy.fontScale += 1f
        configurationCopy.userSetLocale = true
        check(ActivityManager.getService().configuration == originalConfiguration) {
            "ActivityManager.getConfiguration exposed mutable service state"
        }
        check(activityTaskService.activityClientController != null)
        check(activityTaskService.getTasks(4, false, true, Display.DEFAULT_DISPLAY).isEmpty())
        check(activityTaskService.getRecentTasks(4, 0, UserHandle.USER_SYSTEM).list.isEmpty())
        check(runCatching { activityTaskService.focusedRootTaskInfo }
            .exceptionOrNull() is SecurityException)
        check(runCatching { activityTaskService.getTaskBounds(Int.MAX_VALUE) }
            .exceptionOrNull() is SecurityException)
        // Stock ATMS validates the package/UID before resolving an activity.
        val fixture = checkNotNull(permissionsFixtureApk) { "permission fixture APK argument missing" }
        val fixtureInstaller = checkNotNull(dev.artzig.HostedServiceClient.installerOrNull())
        check(fixtureInstaller.installPackage(fixture, null) == "org.example.permissions")
        check(activityTaskService.startActivity(
            null,
            "org.example.permissions",
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

        // Bind this JVM to the installed fixture before exercising clients that
        // authenticate the context package against the Binder caller UID.
        val fixtureApplication = dev.artzig.HostedServiceClient.attachApplication("org.example.permissions")
        val appContext = packageIdentityContext(context, "org.example.permissions")

        checkNotNull(appContext.getSystemService(android.content.ClipboardManager::class.java))
        val clipboardManager = android.content.ClipboardManager(
            appContext,
            Handler(appContext.mainLooper),
        )
        clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("smoke", "hosted-clip"))
        // ClipboardService requires focus for an ordinary app's reads. This
        // bound, windowless fixture must remain unable to read even its own clip;
        // foreground round trips run through the actual APK clipboard gate.
        check(!clipboardManager.hasPrimaryClip())
        check(clipboardManager.text == null)
        check(clipboardManager.primaryClipDescription == null)


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
        // WindowProcessController.dispatchConfiguration defers delivery while
        // this windowless process is cached. Its caller-specific configuration
        // getter still exposes the override; real APK tests cover resource and
        // lifecycle delivery when an activity is visible.
        fun nightBitsBecome(expected: Int): Boolean {
            repeat(250) {
                drainMainQueue()
                if (ActivityManager.getService().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == expected) return true
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
                if (ActivityManager.getService().configuration.grammaticalGender == expected) return true
                Thread.sleep(20)
            }
            return false
        }
        try {
            grammaticalInflectionManager.setRequestedApplicationGrammaticalGender(
                Configuration.GRAMMATICAL_GENDER_FEMININE,
            )
            check(grammaticalGenderBecomes(Configuration.GRAMMATICAL_GENDER_FEMININE))
            check(ActivityManager.getService().configuration.grammaticalGender == Configuration.GRAMMATICAL_GENDER_FEMININE) {
                "ActivityManager.getConfiguration lost the calling process's override"
            }
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

    private fun testHostedTaskServices() {
        val context = HostContexts.systemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            320,
            240,
            120,
            0,
        )
        android.app.PropertyInvalidatedCache.disableForTestMode()
        StockPackageSnapshotsSmoke.run(checkNotNull(permissionsFixtureApk))
        StockPackageDiagnosticsSmoke.run()
        val packages = HostedPackageManagerService(
            dev.artzig.system.HostedUserManagerInternal(dev.artzig.system.HostedUserLifecycle())).apply {
            installPlatform(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")))
        }
        val callerPackage = "org.example.permissions"
        check(packages.installPackage(checkNotNull(permissionsFixtureApk)) == callerPackage)
        testTaskRemovalServices(context, packages, callerPackage)
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

    private fun testMessageQueueSyncBarrier() {
        drainMainQueue()
        val looper = checkNotNull(Looper.myLooper())
        val queue = looper.queue
        val observed = mutableListOf<String>()
        val synchronous = Handler(looper)
        val asynchronous = Handler.createAsync(looper)
        val barrier = queue.postSyncBarrier()
        try {
            synchronous.post { observed += "sync" }
            asynchronous.post { observed += "async" }
            BoundedLooper.runSlice()
            check(observed == listOf("async")) { "unexpected barrier delivery: $observed" }
        } finally { queue.removeSyncBarrier(barrier) }
        drainMainQueue()
        check(observed == listOf("async", "sync")) { "barrier did not release sync work: $observed" }
        check(runCatching { queue.removeSyncBarrier(barrier) }.exceptionOrNull()
            is IllegalStateException) { "removing a barrier twice must fail" }
    }

    private fun testMessageQueueHostPumpSemantics() {
        StockLooperSmoke.run()
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
        return BoundedLooper.drain()
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
        val packages = HostedPackageManagerService(
            dev.artzig.system.HostedUserManagerInternal(dev.artzig.system.HostedUserLifecycle())).apply {
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

    private fun testSystemUiContextCaching() {
        HostContexts.systemContext(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 320, 240, 120, 0)
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

    @Suppress("DEPRECATION")
    private fun testWindowPresentation() {
        val defaultVisible = WindowInsets.Type.defaultVisible()
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
        // This in-process AMS fixture bypasses SystemServer's shared-memory setup.
        val previousMemory = com.android.internal.os.ApplicationSharedMemory.sInstance
        com.android.internal.os.ApplicationSharedMemory.create().use { memory ->
            com.android.internal.os.ApplicationSharedMemory.sInstance = memory
            try { am.attachApplication(thread, 0) }
            finally { com.android.internal.os.ApplicationSharedMemory.sInstance = previousMemory }
        }
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
        // Stock AMS/ActiveServices reject invalid requests before retaining a
        // connection. In particular bit 62 must survive the Binder Long argument.
        fun rejectedBind(flags: Long, instance: String? = null, request: Intent = intent("BoundTaskService"),
            expected: Class<out Throwable> = SecurityException::class.java) {
            val failure = runCatching { am.bindServiceInstance(thread, null, request, null,
                connection, flags or Context.BIND_AUTO_CREATE.toLong(), instance, pkg, 0) }.exceptionOrNull()
            check(expected.isInstance(failure)) { "flags=$flags instance=$instance: $failure" }
            check(!am.unbindService(connection)) { "Rejected bind retained a connection" }
        }
        rejectedBind(Context.BIND_EXTERNAL_SERVICE_LONG)
        rejectedBind(Integer.toUnsignedLong(Context.BIND_EXTERNAL_SERVICE))
        rejectedBind(Context.BIND_SHARED_ISOLATED_PROCESS.toLong())
        rejectedBind(0L, "named", expected = IllegalArgumentException::class.java)
        rejectedBind(0L, "bad/name", expected = IllegalArgumentException::class.java)
        rejectedBind(0L, "named", Intent("implicit"), IllegalArgumentException::class.java)
        rejectedBind(Context.BIND_SCHEDULE_LIKE_TOP_APP.toLong())
        check(runCatching { am.bindServiceInstance(null, null, intent("BoundTaskService"), null,
            connection, 0L, null, pkg, 0) }.exceptionOrNull() is SecurityException)
        check(runCatching { am.bindServiceInstance(thread, null, intent("BoundTaskService"), null,
            connection, 0L, null, null, 0) }.exceptionOrNull() is IllegalArgumentException)
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

}
