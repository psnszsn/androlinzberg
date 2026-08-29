package dev.artzig

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityThread
import android.app.ContextImpl
import android.app.DownloadManager
import android.app.Notification
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.HostedPackageParser
import android.content.res.Configuration
import android.content.res.HostedConfigurationController
import android.graphics.Point
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.net.Uri
import android.view.Choreographer
import android.view.HostedSystemUiController
import android.view.HostedInputDispatcher
import android.view.HostedWindowPresentation
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowManagerImpl
import android.view.ViewRootImpl
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/** Behavioral smoke tests whose surface is the hosted framework modules. */
internal object FrameworkModuleSmoke {
    private val events = mutableListOf<String>()

    @JvmStatic
    fun main(args: Array<String>) {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        testPackageNames()
        testConfigurationCalculation()
        testMessageQueueSyncBarrier()
        testMessageQueueHostPumpSemantics()
        testChoreographerPhaseOrder()
        testPostedCallbackRunsAfterPendingTraversal()
        testTraversalRescheduleAndSharedQueue()
        testHostedSystemServices()
        testHostedInputEvents()
        testHostedInputDispatch()
        testHostedPhysicalInputLifecycle()
        testHostedInputHierarchyDispatch()
        testHostedInputCancelOnInterception()
        testHostedInputSiblingDispatch()
        testHostedInputWindowRouting()
        testWindowPresentation()
        testActivityLifecycle()
        println("hosted framework module smoke: PASS")
    }

    private fun testPackageNames() {
        check(HostedPackageParser.buildClassName("org.example", ".MainActivity") ==
            "org.example.MainActivity")
        check(HostedPackageParser.buildClassName("org.example", "MainActivity") ==
            "org.example.MainActivity")
        check(HostedPackageParser.buildClassName("org.example", "other.MainActivity") ==
            "other.MainActivity")
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
        val context = ContextImpl.createSystemContext(
            System.getenv("ARTZIG_FRAMEWORK_RES") ?: "zig-out/res/framework-res.apk",
            200,
            200,
            120,
            0,
        )
        val activityManager = context.getSystemService(ActivityManager::class.java)
        check(activityManager != null && !activityManager.isBackgroundRestricted)

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

        // The hosted provider table is deliberately kept under filesDir, where the APK can alter
        // it. Verify that restoring a forged successful record cannot turn provider APIs into a
        // read/delete primitive for paths outside application-owned storage.
        val stateDirectory = File(context.filesDir, "hosted-download-manager")
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

        val byName = context.getSystemService(Context.DOWNLOAD_SERVICE)
        val downloadManager = context.getSystemService(DownloadManager::class.java)
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
        val destination = File(context.filesDir, "hosted-download-manager-smoke.bin")
        destination.delete()
        val unsafeRequest = DownloadManager.Request(
            Uri.parse("http://127.0.0.1/unsafe"),
        ).setDestinationUri(Uri.fromFile(File("/tmp/hosted-download-manager-escape.bin")))
        check(runCatching { downloadManager.enqueue(unsafeRequest) }.exceptionOrNull() is SecurityException) {
            "hosted DownloadManager must not escape application-owned storage"
        }
        val traversalRequest = DownloadManager.Request(
            Uri.parse("http://127.0.0.1/traversal"),
        ).setDestinationInExternalFilesDir(context, null, "../../hosted-download-manager-escape.bin")
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

            val restoredManager = DownloadManager(context)
            restoredManager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                    DownloadManager.STATUS_SUCCESSFUL)
            }
            check(File(context.filesDir, "hosted-download-manager/state.bin").isFile) {
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

    private fun testPostedCallbackRunsAfterPendingTraversal() {
        val context = ContextImpl.createSystemContext(
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
            asyncMessage.recycle()
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
                message.recycle()
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
        val context = ContextImpl.createSystemContext(
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
        val queue = checkNotNull(Looper.getMainLooper()).queue
        while (true) {
            val message = queue.`pollDueMessage$artzig`() ?: return
            try {
                message.target.dispatchMessage(message)
            } finally {
                message.recycle()
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
        val context = ContextImpl.createSystemContext(frameworkResources, 200, 200, 120, 0)
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
        val context = ContextImpl.createSystemContext(
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
        val context = ContextImpl.createSystemContext(
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

    private fun testHostedInputCancelOnInterception() {
        val context = ContextImpl.createSystemContext(
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
        val context = ContextImpl.createSystemContext(
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
        val context = ContextImpl.createSystemContext(
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
        val firstName = FirstActivity::class.java.name
        val secondName = SecondActivity::class.java.name
        val firstInfo = activityInfo(firstName).apply {
            configChanges = ActivityInfo.CONFIG_SCREEN_SIZE
        }
        val secondInfo = activityInfo(secondName)
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
        screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private class RecordingHostAdapter : ActivityThread.HostAdapter {
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
    }

    open class RecordingActivity(private val activityLabel: String) : Activity() {
        val label: String get() = activityLabel

        override fun attach(base: Context?, activityInfo: ActivityInfo?) {
            setRequestedOrientation(
                activityInfo?.screenOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            )
        }

        override fun onCreate(savedInstanceState: Bundle?) {
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
