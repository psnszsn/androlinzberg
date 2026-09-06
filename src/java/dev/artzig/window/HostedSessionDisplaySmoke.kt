package dev.artzig.window

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.graphics.ColorSpace
import android.hardware.OverlayProperties
import android.hardware.display.DisplayManagerGlobal
import android.hardware.display.DisplayManagerInternal
import android.hardware.display.DisplayTopology
import android.hardware.display.IDisplayManagerCallback
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.RemoteException
import android.util.IntArray
import android.util.SparseArray
import android.view.Display
import android.view.DisplayInfo
import android.view.SurfaceControl
import com.android.server.display.DisplayControl
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object HostedSessionDisplaySmoke {
    fun run(base: Context) {
        var invalidations = 0
        var permitConnectionEvents = false
        val context = object : ContextWrapper(base) {
            override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
                check(permission == Manifest.permission.MANAGE_DISPLAYS)
                if (!permitConnectionEvents) throw SecurityException(message)
            }
        }
        val thread = HandlerThread("display-service-fixture").apply { start() }
        val handler = Handler(thread.looper)
        fun drain() {
            val done = CountDownLatch(1)
            check(handler.post { done.countDown() })
            check(done.await(5, TimeUnit.SECONDS))
        }
        try {
            val info = DisplayInfo().apply {
                displayId = 0; logicalWidth = 1280; logicalHeight = 720; logicalDensityDpi = 160
                appWidth = 1280; appHeight = 720; modeId = 1; defaultModeId = 1
                supportedModes = arrayOf(Display.Mode(1, 1280, 720, 60f))
            }
            var configuredTransaction: SurfaceControl.Transaction? = null
            val output = object : HostedLogicalDisplay.Output {
                override fun configure(transaction: SurfaceControl.Transaction,
                    projection: HostedLogicalDisplay.Projection) { configuredTransaction = transaction }
            }
            val service = HostedSessionDisplayService(context, handler, info, output,
                ColorSpace.Named.SRGB.ordinal, OverlayProperties.getDefault(),
                invalidateDisplayCache = { invalidations++ })
            val local = localService(service)
            check(service.getDisplayIds(false).contentEquals(intArrayOf(0)))
            check(service.getDisplayInfo(900) == null && local.getPossibleDisplayInfo(900).isEmpty())
            check(runCatching { service.isMinimalPostProcessingRequested(900) }
                .exceptionOrNull() is NullPointerException)
            check(local.getDisplayIdsForGroup(0).contentEquals(intArrayOf(0)))
            check(local.getDisplayIdsForGroup(3).isEmpty())
            val access = IntArray.wrap(intArrayOf(123, 456))
            local.setDisplayAccessUIDs(SparseArray<IntArray>().apply { put(0, access); put(99, access) })
            access.clear()
            check(service.isUidPresentOnDisplay(123, 0) && !service.isUidPresentOnDisplay(123, 99))
            check(!service.isUidPresentOnDisplay(789, 0))
            check(runCatching { service.registerCallback(null) }.exceptionOrNull() is IllegalArgumentException)
            val callback = Callback()
            check(runCatching { service.registerCallbackWithEventMask(callback,
                DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_CONNECTION_CHANGED) }.exceptionOrNull() is SecurityException)
            check(callback.death == null)
            service.registerCallbackWithEventMask(callback, 0L)
            val override = DisplayInfo(info).apply { logicalWidth = 1000; appWidth = 1000 }
            local.setDisplayInfoOverrideFromWindowManager(0, override)
            drain()
            check(invalidations == 1 && callback.events.isEmpty())
            // A second registration from the same PID updates the mask of the
            // original callback, as stock DisplayManagerService does.
            val ignored = Callback()
            service.registerCallback(ignored)
            override.logicalWidth = 1100
            local.setDisplayInfoOverrideFromWindowManager(0, override)
            drain()
            check(callback.events == listOf(DisplayManagerGlobal.EVENT_DISPLAY_BASIC_CHANGED))
            check(ignored.death == null && ignored.events.isEmpty())
            check(local.getPossibleDisplayInfo(0).single().logicalWidth == 1280)
            check(service.getDisplayInfo(0)!!.logicalWidth == 1100)
            SurfaceControl.Transaction().use { global -> SurfaceControl.Transaction().use { perDisplay ->
                var listenerTransaction: SurfaceControl.Transaction? = null
                val listener = DisplayManagerInternal.DisplayTransactionListener { listenerTransaction = it }
                local.registerDisplayTransactionListener(listener)
                local.performTraversal(global, SparseArray<SurfaceControl.Transaction>().apply { put(0, perDisplay) })
                check(configuredTransaction === perDisplay && listenerTransaction === global)
                local.unregisterDisplayTransactionListener(listener)
            } }
            checkNotNull(callback.death).binderDied()
            local.setDisplayInfoOverrideFromWindowManager(0, null)
            drain()
            check(callback.events.size == 1)
            check(runCatching { service.registerCallback(Callback(failLink = true)) }.exceptionOrNull()?.cause is RemoteException)
            permitConnectionEvents = true
            val replacement = Callback()
            service.registerCallbackWithEventMask(replacement,
                DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_CONNECTION_CHANGED)
            check(replacement.death != null)
            checkNotNull(replacement.death).binderDied()
        } finally {
            thread.quitSafely()
            thread.join(5000)
            check(!thread.isAlive)
        }
    }

    fun compositor(context: Context, handler: Handler) {
        val service = SurfaceFlingerDisplayOutput.createService(context, handler)
        val local = localService(service)
        val before = checkNotNull(service.getDisplayInfo(0))
        check(before.logicalWidth > 0 && before.logicalHeight > 0 && before.supportedModes.size == 1)
        val privateOutput = checkNotNull(DisplayControl.createVirtualDisplay("private-task-discovery-fixture", false))
        try {
            check(service.getDisplayIds(false).contentEquals(intArrayOf(0)))
            check(local.getPossibleDisplayInfo(0).single().logicalWidth == before.logicalWidth)
            check(DisplayControl.getPhysicalDisplayIds().size == 1)
            SurfaceControl.Transaction().use { transaction ->
                local.performTraversal(transaction, SparseArray())
                transaction.apply(true)
            }
        } finally { DisplayControl.destroyVirtualDisplay(privateOutput) }
    }

    private fun localService(service: HostedSessionDisplayService): DisplayManagerInternal =
        Class.forName("android.hardware.display.HostedDisplayManagerInternal")
            .getConstructor(HostedSessionDisplayService::class.java).newInstance(service) as DisplayManagerInternal

    private class Callback(private val failLink: Boolean = false) : IDisplayManagerCallback.Stub() {
        var death: IBinder.DeathRecipient? = null
        val events = mutableListOf<Int>()
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            if (failLink) throw RemoteException("display fixture already dead")
            death = recipient
        }
        override fun onDisplayEvent(displayId: Int, event: Int) { check(displayId == 0); events.add(event) }
        override fun onTopologyChanged(topology: DisplayTopology) { error("Unexpected topology") }
    }
}
