package dev.artzig.window

import android.Manifest
import android.content.Context
import android.graphics.Point
import android.hardware.OverlayProperties
import android.hardware.display.DisplayManagerGlobal
import android.hardware.display.DisplayManagerInternal
import android.hardware.display.IDisplayManager
import android.hardware.display.IDisplayManagerCallback
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.RemoteException
import android.util.IntArray
import android.util.SparseArray
import android.view.Display
import android.view.DisplayInfo
import android.view.SurfaceControl
import dev.artzig.system.unsupported

/**
 * DisplayManagerService's Binder and LocalService views of one session display.
 * Display discovery comes from the primary compositor output, never from the
 * private virtual displays used to compose individual task windows.
 */
class HostedSessionDisplayService(private val context: Context, private val handler: Handler,
    physicalInfo: DisplayInfo, output: HostedLogicalDisplay.Output,
    private val wideColorSpaceId: Int, private val overlays: OverlayProperties, installRotation: Int = 0,
    private val invalidateDisplayCache: () -> Unit = DisplayManagerGlobal::invalidateLocalDisplayInfoCaches) :
    // Display discovery precedes ActivityThread's first Context. Supply the
    // service's bootstrap Context explicitly; permission checks run on requests.
    IDisplayManager.Stub(android.os.PermissionEnforcer(context)), IDisplayManager by unsupported<IDisplayManager>() {
    override fun asBinder(): IBinder = this
    private val lock = Any()
    private val callbacks = SparseArray<Callback>()
    private val accessUids = SparseArray<IntArray>()
    private var windowManager: com.android.server.wm.WindowManagerInternal? = null
    @Volatile private var minimalPostProcessingAllowed = true
    private val display = HostedLogicalDisplay(physicalInfo, output, ::displayChanged, {
        handler.post { windowManager?.requestTraversalFromDisplayManager() }
    }, installRotation)

    fun attachWindowManager(manager: com.android.server.wm.WindowManagerInternal) {
        check(windowManager == null)
        windowManager = manager
        val uri = android.provider.Settings.Secure.getUriFor(
            android.provider.Settings.Secure.MINIMAL_POST_PROCESSING_ALLOWED)
        context.contentResolver.registerContentObserver(uri, false,
            object : android.database.ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) { updateMinimalPostProcessingAllowed() }
            })
        updateMinimalPostProcessingAllowed()
        manager.requestTraversalFromDisplayManager()
    }

    private fun updateMinimalPostProcessingAllowed() {
        minimalPostProcessingAllowed = android.provider.Settings.Secure.getIntForUser(
            context.contentResolver, android.provider.Settings.Secure.MINIMAL_POST_PROCESSING_ALLOWED,
            1, android.os.UserHandle.USER_SYSTEM) != 0
    }

    override fun getDisplayInfo(displayId: Int): DisplayInfo? =
        display.getDisplayInfo(displayId)?.takeIf { it.hasAccess(Binder.getCallingUid()) ||
            isUidPresentOnDisplay(Binder.getCallingUid(), displayId) }
    override fun getDisplayIds(includeDisabled: Boolean): kotlin.IntArray =
        if (getDisplayInfo(Display.DEFAULT_DISPLAY) != null) intArrayOf(Display.DEFAULT_DISPLAY)
        else intArrayOf()
    override fun isUidPresentOnDisplay(uid: Int, displayId: Int): Boolean = synchronized(lock) {
        accessUids[displayId]?.indexOf(uid)?.let { it >= 0 } ?: false
    }
    override fun getStableDisplaySize(): Point = display.getStableDisplaySize()
    override fun getPreferredWideGamutColorSpaceId(): Int = wideColorSpaceId
    override fun getRefreshRateSwitchingType(): Int = android.hardware.display.DisplayManager.SWITCHING_TYPE_NONE
    override fun getOverlaySupport(): OverlayProperties = overlays
    override fun isMinimalPostProcessingRequested(displayId: Int): Boolean {
        // The stock query dereferences the selected logical display directly.
        display.getDisplayInfo(displayId)!!
        return display.isMinimalPostProcessingRequested()
    }

    override fun registerCallback(callback: IDisplayManagerCallback?) = registerCallbackWithEventMask(
        callback, DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_ADDED or
            DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_BASIC_CHANGED or
            DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_REFRESH_RATE or
            DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_REMOVED)

    override fun registerCallbackWithEventMask(callback: IDisplayManagerCallback?, eventsMask: Long) {
        requireNotNull(callback) { "listener must not be null" }
        if (eventsMask and DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_CONNECTION_CHANGED != 0L) {
            context.enforceCallingOrSelfPermission(Manifest.permission.MANAGE_DISPLAYS,
                "Permission required to get signals about connection events.")
        }
        val pid = Binder.getCallingPid()
        synchronized(lock) {
            callbacks[pid]?.let { it.mask = eventsMask; return }
            val record = Callback(pid, callback, eventsMask)
            try { callback.asBinder().linkToDeath(record, 0) }
            catch (error: RemoteException) { throw RuntimeException(error) }
            callbacks.put(pid, record)
        }
    }

    private inner class Callback(val pid: Int, val callback: IDisplayManagerCallback, var mask: Long) :
        IBinder.DeathRecipient {
        override fun binderDied() { synchronized(lock) {
            if (callbacks[pid] === this) callbacks.remove(pid)
        } }
    }

    private fun displayChanged() {
        invalidateDisplayCache()
        handler.post {
            val listeners = synchronized(lock) {
                (0 until callbacks.size()).map { callbacks.valueAt(it) }.filter {
                    it.mask and DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_BASIC_CHANGED != 0L
                }
            }
            for (record in listeners) {
                try { record.callback.onDisplayEvent(Display.DEFAULT_DISPLAY,
                    DisplayManagerGlobal.EVENT_DISPLAY_BASIC_CHANGED) }
                catch (_: RemoteException) { record.binderDied() }
            }
        }
    }

    fun getPossibleDisplayInfo(displayId: Int): Set<DisplayInfo> =
        if (displayId == Display.DEFAULT_DISPLAY) setOf(DisplayInfo().also {
            display.getNonOverrideDisplayInfo(displayId, it)
        }) else emptySet()
    fun getDisplayPosition(displayId: Int): Point? = display.getDisplayPosition(displayId)
    fun getNonOverrideDisplayInfo(displayId: Int, outInfo: DisplayInfo) =
        display.getNonOverrideDisplayInfo(displayId, outInfo)
    fun setDisplayInfoOverrideFromWindowManager(displayId: Int, info: DisplayInfo?) =
        display.setDisplayInfoOverrideFromWindowManager(displayId, info)
    fun registerDisplayTransactionListener(listener: DisplayManagerInternal.DisplayTransactionListener) =
        display.registerDisplayTransactionListener(listener)
    fun unregisterDisplayTransactionListener(listener: DisplayManagerInternal.DisplayTransactionListener) =
        display.unregisterDisplayTransactionListener(listener)
    fun performTraversal(transaction: SurfaceControl.Transaction,
        displayTransactions: SparseArray<SurfaceControl.Transaction>) = display.performTraversal(transaction,
            displayTransactions.get(Display.DEFAULT_DISPLAY, transaction))
    fun setDisplayOffsets(displayId: Int, x: Int, y: Int) = display.setDisplayOffsets(displayId, x, y)
    fun setDisplayScalingDisabled(displayId: Int, disabled: Boolean) =
        display.setDisplayScalingDisabled(displayId, disabled)
    fun setDisplayProperties(displayId: Int, hasContent: Boolean, requestedRefreshRate: Float,
        requestedModeId: Int, requestedMinRefreshRate: Float, requestedMaxRefreshRate: Float,
        preferMinimalPostProcessing: Boolean, disableHdrConversion: Boolean, inTraversal: Boolean) {
        // DMS's app mode votes cannot select another mode on this fixed-mode
        // output. HDR conversion is absent (mHdrConversionMode == null branch).
        // The primary display always uses its own layer stack, even without
        // content; there is no other Android display to mirror.
        display.setDisplayProperties(displayId, hasContent,
            minimalPostProcessingAllowed && preferMinimalPostProcessing, inTraversal)
    }
    fun setScreenBrightnessOverrideFromWindowManager(
        overrides: SparseArray<DisplayManagerInternal.DisplayBrightnessOverrideRequest>) {
        val brightness = overrides[Display.DEFAULT_DISPLAY]?.brightness ?: return
        // DisplayBrightnessController only selects a valid override in [0, 1].
        // Missing, NaN and out-of-range values all clear the override.
        if (brightness >= android.os.PowerManager.BRIGHTNESS_MIN &&
            brightness <= android.os.PowerManager.BRIGHTNESS_MAX) {
            throw dev.artzig.ArtzigTestDiagnostics.unsupported("DisplayManagerInternal",
                "setScreenBrightnessOverrideFromWindowManager",
                "The desktop compositor does not expose per-window display brightness")
        }
    }
    fun getDisplayIdToMirror(displayId: Int): Int =
        if (displayId == Display.DEFAULT_DISPLAY) Display.DEFAULT_DISPLAY else Display.INVALID_DISPLAY
    fun onDisplayBelongToTopologyChanged(displayId: Int, inTopology: Boolean) {
        // DMS's null DisplayTopologyCoordinator branch: a single Android display.
    }
    fun setDisplayAccessUIDs(uids: SparseArray<IntArray>) { synchronized(lock) {
        accessUids.clear()
        uids[Display.DEFAULT_DISPLAY]?.let { accessUids.put(Display.DEFAULT_DISPLAY, it.clone()) }
    } }
    fun getDisplayWindowPolicyController(displayId: Int): android.window.DisplayWindowPolicyController? = null
    fun getHostUsiVersion(displayId: Int): android.hardware.input.HostUsiVersion? = null
    fun isProximitySensorAvailable(displayId: Int): Boolean = false
    fun getRefreshRateForDisplayAndSensor(displayId: Int, name: String?, type: String?):
        android.view.SurfaceControl.RefreshRateRange? = null
    fun getRefreshRateLimitations(displayId: Int): List<DisplayManagerInternal.RefreshRateLimitation> = emptyList()
    fun getDisplayGroupIds(): IntArray = IntArray.wrap(intArrayOf(Display.DEFAULT_DISPLAY_GROUP))
    fun getDisplayIds(): IntArray = IntArray.wrap(intArrayOf(Display.DEFAULT_DISPLAY))
    fun getDisplayIdsForGroup(groupId: Int): kotlin.IntArray =
        if (groupId == Display.DEFAULT_DISPLAY_GROUP) intArrayOf(Display.DEFAULT_DISPLAY) else intArrayOf()
    fun getDisplayIdsByGroupsIds(): SparseArray<kotlin.IntArray> = SparseArray<kotlin.IntArray>().apply {
        put(Display.DEFAULT_DISPLAY_GROUP, intArrayOf(Display.DEFAULT_DISPLAY))
    }
    fun getGroupIdForDisplay(displayId: Int): Int =
        if (displayId == Display.DEFAULT_DISPLAY) Display.DEFAULT_DISPLAY_GROUP else Display.INVALID_DISPLAY_GROUP
}
