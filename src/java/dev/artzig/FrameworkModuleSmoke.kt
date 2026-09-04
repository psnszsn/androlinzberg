@file:Suppress("DEPRECATION")

package dev.artzig

import android.app.Activity
import android.app.ActivityClient
import android.app.ActivityGroup
import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.ActivityThread
import android.app.ComponentCaller
import android.app.DownloadManager
import android.app.GrammaticalInflectionManager
import android.app.IActivityClientController
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
import android.app.servertransaction.ActivityRelaunchItem
import android.app.servertransaction.ClientTransaction
import android.app.servertransaction.ClientTransactionListenerController
import android.app.servertransaction.ConfigurationChangeItem
import android.app.servertransaction.DestroyActivityItem
import android.app.servertransaction.LaunchActivityItem
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
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.os.Bundle
import android.os.Binder
import android.os.Handler
import android.os.Looper
import com.android.internal.pm.pkg.parsing.ParsingUtils
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.os.PersistableBundle
import android.os.StatFs
import android.provider.Downloads
import android.util.SuperNotCalledException
import android.security.net.config.NetworkSecurityConfig
import android.security.net.config.NetworkSecurityConfigProvider
import android.security.net.config.Pin
import android.security.net.config.PinSet
import android.service.contentcapture.ContentCaptureService
import android.service.textclassifier.TextClassifierService
import android.service.voice.VisualQueryDetectedResult
import android.net.Uri
import android.net.http.X509TrustManagerExtensions
import android.net.nsd.HostedNsdBackends
import android.net.nsd.InMemoryHostedNsdBackend
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

    @JvmStatic
    fun main(args: Array<String>) {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        permissionsFixtureApk = args.firstOrNull()
        testPackageNames()
        testStockModuleClientContracts()
        testTaskInfoParcelableContracts()
        testAccessibilityHtmlFiltering()
        testFrameworkServiceValueContracts()
        testContentCaptureContracts()
        testTraceBufferContracts()
        testProviderContracts()
        testStatFsContracts()
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
        testLocalActivityManagerLifecycleAndRecreation()
        testActivityThreadResultDelivery()
        testActivityThreadTransactionCompletion()
        testActivityThreadConfigurationChange()
        testActivityThreadRelaunch()
        testActivityGroupLifecycleStateAndResults()
        testLegacyFragmentTransactionsAndRecreation()
        testLegacyFragmentHostedResultsAndRetention()
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

        // The hosted TaskSnapshot deliberately has no SurfaceFlinger HardwareBuffer API.
        object : TaskStackListener() {}.onTaskSnapshotChanged(601, TaskSnapshot())
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
        task.taskDescription = ActivityManager.TaskDescription("task-$marker", marker + 6)
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
            val inputMethods = checkNotNull(
                context.getSystemService(android.view.inputmethod.InputMethodManager::class.java),
            )
            check(inputMethods.inputMethodList.isEmpty()) { "host must expose no software IMEs" }
            check(inputMethods.enabledInputMethodList.isEmpty()) { "host must enable no software IMEs" }
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
                    checkNotNull(android.view.ArtzigWindowStack.topFocusable()).root,
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
                        android.view.ArtzigWindowStack.topFocusable(),
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
                val lowerRoot = checkNotNull(android.view.ArtzigWindowStack.topFocusable()).root
                val upperDialog = showPredictiveDialog()
                upperDialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                    sharedBackCallback,
                )
                val upperRoot = checkNotNull(android.view.ArtzigWindowStack.topFocusable()).root
                check(upperRoot !== lowerRoot)
                ApkHost.physicalKey(0, 158, 0, 0)
                upperDialog.dismiss()
                runHostedFrame()
                check(checkNotNull(android.view.ArtzigWindowStack.topFocusable()).root === lowerRoot)
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
            android.view.ArtzigWindowStack.clear()
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

    private fun testLocalActivityManagerLifecycleAndRecreation() {
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
        val adapter = LocalActivityHostAdapter(context)
        val firstIntent = Intent().setComponent(ComponentName("org.example", firstName))
        val secondIntent = Intent().setComponent(ComponentName("org.example", secondName))
        var activeManager: LocalActivityManager? = null

        ActivityThread.configureActivityHost(
            adapter,
            mapOf(parentName to parentInfo, firstName to firstInfo, secondName to secondInfo),
        )
        try {
            val parent = ActivityThread.launchMainActivity(parentName) as LocalParentActivity
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
                ActivityThread.launchMainActivity(parentName) as LocalParentActivity
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
            ActivityThread.shutdownActivitiesInProcess()
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
        val activity = ResultDeliveryActivity()
        activity.attach(context, info)
        activity.performCreate(null)
        activity.performStart()
        activity.performPostCreate(null)
        activity.performResume()

        val record = ActivityThread.ActivityClientRecord().apply {
            token = Binder()
            intent = Intent().setComponent(ComponentName("org.example", activityName))
            activityInfo = info
            this.activity = activity
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
        check(first.preparedForProcess && first.fromProtectedComponent)
        check(second.preparedForProcess && second.fromProtectedComponent)
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
        check(paused.preparedForProcess && paused.fromProtectedComponent)
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
            intent = Intent().setComponent(ComponentName("org.example", activityName))
            activityInfo = info
            this.activity = callerAware
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
        val hostedDispatch = Activity::class.java.getDeclaredMethod(
            "dispatchActivityResult",
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Intent::class.java,
        ).apply { isAccessible = true }
        withContentUriPermissionApis(true) {
            hostedDispatch.invoke(
                callerAware,
                null,
                24,
                Activity.RESULT_OK,
                Intent(),
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
            intent = Intent().setComponent(ComponentName("org.example", activityName))
            activityInfo = info
            this.activity = missingSuper
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

    private fun testActivityThreadTransactionCompletion() {
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
            applicationInfo.targetSdkVersion = android.os.Build.VERSION.SDK_INT
        }
        val presented = mutableListOf<Activity>()
        val adapter = object : ActivityThread.HostAdapter {
            override fun getActivityContext(): Context = context
            override fun getActivityClassLoader(): ClassLoader = checkNotNull(javaClass.classLoader)
            override fun showActivity(activity: Activity) {
                transactionCompletionEvents += "present"
                presented += activity
            }
            override fun clearActivityWindows() {
                transactionCompletionEvents += "clear"
            }
            override fun resizeActivityRoot(activity: Activity, width: Int, height: Int) = Unit
            override fun resolveActivity(intent: Intent): ComponentName? = intent.component
        }
        ActivityThread.configureActivityHost(adapter, mapOf(activityName to info))

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
                        check(persistentState?.getString("persistent") == "kept")
                        check(args?.get(3) == "transaction-description")
                    }
                },
            ) {
                thread.executeTransaction(ClientTransaction().apply {
                    addTransactionItem(
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
                check(transactionCompletionEvents == listOf(
                    "create",
                    "start",
                    "post-create",
                    "resume",
                    "present",
                    "report:resumed",
                )) {
                    "transaction launch was presented before resume: $transactionCompletionEvents"
                }
                check(presented.single() === thread.getActivityClient(token)?.activity)

                thread.executeTransaction(ClientTransaction().apply {
                    addTransactionItem(StopActivityItem(token))
                })
                check(transactionCompletionEvents.last() == "clear") {
                    "final stop did not clear after state save: $transactionCompletionEvents"
                }
                drainMainQueue()
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
            ActivityThread.shutdownActivitiesInProcess()
        }
    }

    private fun testActivityThreadConfigurationChange() {
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
        ActivityThread.configureActivityHost(
            LocalActivityHostAdapter(context),
            mapOf(activityName to info, missingSuperName to missingSuperInfo),
        )

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
        val application = checkNotNull(ActivityThread.currentApplication())
        val applicationCallback = configurationLifecycleCallback("application-callback")
        val activityCallback = configurationLifecycleCallback("activity-callback")
        application.registerActivityLifecycleCallbacks(applicationCallback)
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
            activity.registerActivityLifecycleCallbacks(activityCallback)
            record = checkNotNull(thread.getActivityClient(token))
            thread.handleStartActivity(
                record,
                PendingTransactionActions().apply { setCallOnPostCreate(true) },
                null,
            )
            check(thread.performResumeActivity(record, false, "configuration-smoke"))
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
            check(activity.callbackConfiguration?.fontScale == 1.5f)
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

            val unavailableDisplayFailure = runCatching {
                thread.handleActivityConfigurationChanged(
                    record,
                    Configuration(newer).apply { seq = 104 },
                    7,
                    olderWindowInfo,
                )
            }.exceptionOrNull()
            check(unavailableDisplayFailure is UnsupportedOperationException) {
                "non-default display update should fail at the hosted display boundary: " +
                    unavailableDisplayFailure
            }
            check(record.activityWindowInfo == changedWindowInfo)
            check(record.lifecycleState == lifecycleState)

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
            configurationActivity?.unregisterActivityLifecycleCallbacks(activityCallback)
            application.unregisterActivityLifecycleCallbacks(applicationCallback)
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
            ActivityThread.shutdownActivitiesInProcess()
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
        ActivityThread.configureActivityHost(
            LocalActivityHostAdapter(context),
            mapOf(activityName to info),
        )

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
            val oldActivity = record.activity as RelaunchActivity
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
            check(launchIntent.preparedForProcess && launchIntent.fromProtectedComponent)
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

            val replacement = record.activity as RelaunchActivity
            check(replacement !== oldActivity)
            check(thread.getActivityClient(token) === record)
            check(record.lifecycleState ==
                android.app.servertransaction.ActivityLifecycleItem.ON_RESUME)
            check(replacement.window !== oldWindow) {
                "preserveWindow crossed the hosted WindowManager fence"
            }
            check(record.overrideConfig.fontScale == 1.5f)
            check(record.lastNonConfigurationInstances == null)
            check(record.activityWindowInfo == newerWindowInfo)
            check(record.activityWindowInfo !== newerWindowInfo)
            check(windowInfoEvents == listOf(token to newerWindowInfo)) {
                "coalesced relaunch did not notify only the newest window info: $windowInfoEvents"
            }
            newerWindowInfo.set(false, Rect(), Rect())
            check(record.activityWindowInfo?.isEmbedded == true)
            check(windowInfoEvents.single().second.isEmbedded)
            check(firstIntent.preparedForProcess && firstIntent.fromProtectedComponent)
            check(secondIntent.preparedForProcess && secondIntent.fromProtectedComponent)
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
            val standaloneReplacement = record.activity as RelaunchActivity
            check(standaloneReplacement !== replacement)
            check(standaloneReplacement.window !== replacementWindow)
            check(standaloneReplacement.resources.configuration.orientation == currentOrientation) {
                "standalone relaunch did not install its createdConfig before launch"
            }
            check(ActivityThread.currentApplication()?.resources?.configuration?.orientation ==
                currentOrientation) {
                "standalone relaunch did not install createdConfig for the Application"
            }
            check(record.overrideConfig.fontScale == 1.75f)
            check(record.lastNonConfigurationInstances == null)
            check(record.activityWindowInfo == standaloneWindowInfo)
            check(windowInfoEvents == listOf(
                token to ActivityWindowInfo(windowInfoEvents.first().second),
                token to standaloneWindowInfo,
            ))
            check(record.lifecycleState ==
                android.app.servertransaction.ActivityLifecycleItem.ON_RESUME)
            check(standaloneReports == listOf("relaunched", "resumed"))
            check(standaloneIntent.preparedForProcess &&
                standaloneIntent.fromProtectedComponent)
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
            val staleReplacement = record.activity as RelaunchActivity
            check(staleReplacement.resources.configuration.orientation == currentOrientation) {
                "older createdConfig regressed Activity resources"
            }
            check(ActivityThread.currentApplication()?.resources?.configuration?.orientation ==
                currentOrientation) {
                "older createdConfig regressed Application resources"
            }
            check(record.lastNonConfigurationInstances == null)
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
            check(record.lastNonConfigurationInstances == null)

            record.activityInfo.applicationInfo.targetSdkVersion =
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
            check(record.lastNonConfigurationInstances == null)
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
            ActivityThread.shutdownActivitiesInProcess()
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

    private fun testActivityGroupLifecycleStateAndResults() {
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
        val adapter = LocalActivityHostAdapter(context)
        val childIntent = Intent().setComponent(ComponentName("org.example", childName))
        val resultIntent = Intent().setComponent(ComponentName("org.example", resultName))

        ActivityThread.configureActivityHost(
            adapter,
            mapOf(groupName to groupInfo, childName to childInfo, resultName to resultInfo),
        )
        localActivityEvents.clear()
        activityGroupEvents.clear()
        try {
            val group = ActivityThread.launchMainActivity(groupName) as RecordingActivityGroup
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
            check(ActivityThread.currentActivity() is ActivityGroupResultActivity)
            check(activityGroupEvents == listOf("group:pause", "group:stop"))
            check(localActivityEvents == listOf("group-child:pause", "group-child:stop"))

            val result = ActivityThread.currentActivity() as ActivityGroupResultActivity
            result.setResult(
                Activity.RESULT_OK,
                Intent().putExtra("group-result", "delivered"),
            )
            result.finish()
            check(ActivityThread.currentActivity() === group)
            check(child.resultRequestCode == 73)
            check(child.resultCode == Activity.RESULT_OK)
            check(child.resultMarker == "delivered")
            check(activityGroupEvents == listOf(
                "group:pause",
                "group:stop",
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

            localActivityEvents.clear()
            activityGroupEvents.clear()
            group.recreate()
            val restoredGroup = ActivityThread.currentActivity() as RecordingActivityGroup
            expectedLocalActivityParent = restoredGroup
            check(restoredGroup !== group)
            check(restoredGroup.currentActivity == null)
            check(activityGroupEvents == listOf(
                "group:save",
                "group:pause",
                "group:stop",
                "group:destroy",
                "group:create:saved",
                "group:start",
                "group:restore",
                "group:post-create:saved",
                "group:resume",
            )) { "ActivityGroup recreation ordering: $activityGroupEvents" }
            check(localActivityEvents == listOf(
                "group-child:save",
                "group-child:pause",
                "group-child:stop",
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
                intent = Intent().setComponent(ComponentName("org.example", groupName))
                activityInfo = groupInfo
                activity = restoredGroup
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
            check(groupResult.preparedForProcess && groupResult.fromProtectedComponent)
            check(groupResult.extras?.classLoader === restoredGroup.classLoader)
            check(adapter.presentedActivities.none { it is ActivityGroupChildActivity }) {
                "ActivityGroup child escaped into the desktop presentation stack"
            }
        } finally {
            expectedLocalActivityParent = null
            ActivityThread.shutdownActivitiesInProcess()
        }
    }

    private fun testActivityLifecycle() {
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

        ActivityThread.configureActivityHost(
            adapter,
            mapOf(
                firstName to firstInfo,
                secondName to secondInfo,
                missingConfigurationSuperName to missingConfigurationSuperInfo,
            ),
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
            check(firstInfo.configChanges and ActivityInfo.CONFIG_SCREEN_SIZE == 0)
            check(firstInfo.getRealConfigChanged() and ActivityInfo.CONFIG_SCREEN_SIZE != 0) {
                "pre-HONEYCOMB_MR2 ActivityInfo omitted the implicit screen-size change"
            }
            ActivityThread.applyConfigurationInProcess(
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
            ActivityThread.applyConfigurationInProcess(
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
            check(changingConfigurationEvents == listOf(
                "first:stop:${ActivityInfo.CONFIG_ORIENTATION}",
                "first:destroy:${ActivityInfo.CONFIG_ORIENTATION}",
            )) { "configuration relaunch did not expose its change mask during teardown" }

            changingConfigurationEvents.clear()
            checkNotNull(ActivityThread.currentActivity()).recreate()
            check(changingConfigurationEvents == listOf(
                "first:stop:0",
                "first:destroy:0",
            )) { "explicit Activity.recreate inherited a configuration-change mask" }

            events.clear()
            checkNotNull(ActivityThread.currentActivity()).finish()
            check(ActivityThread.currentActivity() == null)
            check(events == listOf(
                "first:pause",
                "first:stop",
                "first:destroy",
                "clear",
            )) { "last Activity finish ordering: $events" }

            events.clear()
            check(!ActivityThread.applyConfigurationInProcess(
                handledConfiguration,
                ActivityInfo.CONFIG_ORIENTATION,
                640,
                480,
            ))
            check(events == listOf("application:config")) {
                "process configuration was not dispatched without a current Activity: $events"
            }

            ActivityThread.launchMainActivity(missingConfigurationSuperName)
            val hostedMissingSuperFailure = runCatching {
                ActivityThread.applyConfigurationInProcess(
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
            ActivityThread.shutdownActivitiesInProcess()
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

    private fun testLegacyFragmentHostedResultsAndRetention() {
        val hostName = HostedLegacyFragmentActivity::class.java.name
        val resultName = LegacyResultActivity::class.java.name
        ActivityThread.configureActivityHost(
            LegacyFragmentHostAdapter(),
            mapOf(hostName to activityInfo(hostName), resultName to activityInfo(resultName)),
        )
        try {
            val first = ActivityThread.launchMainActivity(hostName) as HostedLegacyFragmentActivity
            val retained = checkNotNull(first.fragment)
            check(retained.createCount == 1)

            retained.requestPermissions(arrayOf(android.Manifest.permission.INTERNET), 73)
            drainMainQueue()
            check(retained.permissionRequestCode == 73)
            check(retained.permissionNames.contentEquals(arrayOf(android.Manifest.permission.INTERNET)))
            check(retained.permissionResults.size == 1)

            ActivityThread.applyConfigurationInProcess(
                Configuration().apply { setToDefaults() },
                ActivityInfo.CONFIG_ORIENTATION,
                900,
                600,
            )
            val replacement = ActivityThread.currentActivity() as HostedLegacyFragmentActivity
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
            val resultActivity = ActivityThread.currentActivity() as LegacyResultActivity
            resultActivity.setResult(
                Activity.RESULT_OK,
                Intent().putExtra("legacy-result", "delivered"),
            )
            ActivityThread.applyConfigurationInProcess(
                Configuration().apply { setToDefaults() },
                ActivityInfo.CONFIG_ORIENTATION,
                640,
                480,
            )
            val recreatedResultActivity = ActivityThread.currentActivity() as LegacyResultActivity
            check(recreatedResultActivity !== resultActivity)
            recreatedResultActivity.finish()
            check(ActivityThread.currentActivity() === replacement)
            check(retained.activityResultRequestCode == 91)
            check(retained.activityResultCode == Activity.RESULT_OK)
            check(retained.activityResultData == "delivered")
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

    private class LocalActivityHostAdapter(
        private val context: Context,
    ) : ActivityThread.HostAdapter {
        val presentedActivities = mutableListOf<Activity>()

        override fun getActivityContext(): Context = context

        override fun getActivityClassLoader(): ClassLoader =
            checkNotNull(javaClass.classLoader)

        override fun showActivity(activity: Activity) {
            presentedActivities += activity
        }

        override fun clearActivityWindows() = Unit

        override fun resizeActivityRoot(activity: Activity, width: Int, height: Int) = Unit

        override fun resolveActivity(intent: Intent): ComponentName? = intent.component
    }

    private class LegacyFragmentHostAdapter : ActivityThread.HostAdapter {
        override fun getActivityContext(): Context? = null
        override fun getActivityClassLoader(): ClassLoader = checkNotNull(javaClass.classLoader)
        override fun showActivity(activity: Activity) {}
        override fun clearActivityWindows() {}
        override fun resizeActivityRoot(activity: Activity, width: Int, height: Int) {}
        override fun resolveActivity(intent: Intent): ComponentName? = null
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

    class ConfigurationDispatchActivity : Activity() {
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

    class LocalFirstActivity : LocalRecordingActivity("first")
    class LocalSecondActivity : LocalRecordingActivity("second")

    open class RecordingActivity(private val activityLabel: String) : Activity() {
        val label: String get() = activityLabel

        override fun attach(base: Context?, activityInfo: ActivityInfo?) {
            super.attach(checkNotNull(base), activityInfo)
        }

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

    class FirstActivity : RecordingActivity("first")
    class SecondActivity : RecordingActivity("second")
    class HostedMissingSuperConfigurationActivity : RecordingActivity("missing-config") {
        override fun onConfigurationChanged(newConfig: Configuration) {
            events += "missing-config:config"
        }
    }
}
