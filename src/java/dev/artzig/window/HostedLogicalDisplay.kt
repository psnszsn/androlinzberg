package dev.artzig.window

import android.graphics.Point
import android.graphics.Rect
import android.view.Display
import android.view.DisplayInfo
import android.view.Surface
import android.view.SurfaceControl
import com.android.server.wm.utils.DisplayInfoOverrides
import java.util.concurrent.CopyOnWriteArrayList
import android.hardware.display.DisplayManagerInternal.DisplayTransactionListener

/**
 * LogicalDisplay.getDisplayInfoLocked/setDisplayInfoOverrideFromWindowManagerLocked
 * and configureDisplayLocked, plus DisplayManagerService.performTraversalInternal.
 * The full display service depends on Android hardware display/power/sensor adapters.
 * This session has one logical display; private SurfaceFlinger task outputs never
 * enter this registry. Stock WM's override-field policy is reused verbatim.
 */
class HostedLogicalDisplay(physicalInfo: DisplayInfo, private val output: Output,
    private val changed: () -> Unit, private val requestTraversal: () -> Unit,
    private val installRotation: Int = Surface.ROTATION_0) {
    /** Production sends these operations to SF; tests retain the requested projection. */
    interface Output {
        fun configure(transaction: SurfaceControl.Transaction, projection: Projection)
    }
    data class Projection(val rotation: Int, val viewport: Rect, val frame: Rect)

    private val lock = Any()
    private val physical = DisplayInfo(physicalInfo)
    private var override: DisplayInfo? = null
    private var pendingTraversal = true
    private var offsetX = 0
    private var offsetY = 0
    private var scalingDisabled = false
    private val position = Point()
    private val transactionListeners = CopyOnWriteArrayList<DisplayTransactionListener>()

    init {
        require(physical.displayId == Display.DEFAULT_DISPLAY)
        require(physical.logicalWidth > 0 && physical.logicalHeight > 0)
        require(physical.logicalDensityDpi > 0)
    }

    fun getDisplayInfo(displayId: Int): DisplayInfo? = synchronized(lock) {
        if (displayId != Display.DEFAULT_DISPLAY) null else infoLocked()
    }

    private fun infoLocked(): DisplayInfo = DisplayInfo().also {
        DisplayInfoOverrides.copyDisplayInfoFields(it, physical, override,
            DisplayInfoOverrides.WM_OVERRIDE_FIELDS)
    }

    fun getNonOverrideDisplayInfo(displayId: Int, outInfo: DisplayInfo) = synchronized(lock) {
        if (displayId == Display.DEFAULT_DISPLAY) outInfo.copyFrom(physical)
    }

    fun getDisplayPosition(displayId: Int): Point? = synchronized(lock) {
        if (displayId == Display.DEFAULT_DISPLAY) Point(position) else null
    }

    fun getStableDisplaySize(): Point = Point(physical.logicalWidth, physical.logicalHeight)

    fun setDisplayInfoOverrideFromWindowManager(displayId: Int, info: DisplayInfo?) {
        val updated = synchronized(lock) {
            if (displayId != Display.DEFAULT_DISPLAY || override == info) false else {
                override = info?.let(::DisplayInfo)
                pendingTraversal = true
                true
            }
        }
        if (updated) {
            changed()
            requestTraversal()
        }
    }

    fun setDisplayOffsets(displayId: Int, x: Int, y: Int) {
        val updated = synchronized(lock) {
            if (displayId != Display.DEFAULT_DISPLAY || (offsetX == x && offsetY == y)) false else {
                offsetX = x; offsetY = y; pendingTraversal = true; true
            }
        }
        if (updated) requestTraversal()
    }

    fun setDisplayScalingDisabled(displayId: Int, disabled: Boolean) {
        val updated = synchronized(lock) {
            if (displayId != Display.DEFAULT_DISPLAY || scalingDisabled == disabled) false else {
                scalingDisabled = disabled; pendingTraversal = true; true
            }
        }
        if (updated) requestTraversal()
    }

    fun registerDisplayTransactionListener(listener: DisplayTransactionListener) {
        transactionListeners.add(listener)
    }
    fun unregisterDisplayTransactionListener(listener: DisplayTransactionListener) {
        transactionListeners.remove(listener)
    }

    fun performTraversal(transaction: SurfaceControl.Transaction,
        displayTransaction: SurfaceControl.Transaction = transaction) {
        synchronized(lock) {
            if (!pendingTraversal) return
            pendingTraversal = false
            val info = infoLocked()
            val rotation = (info.rotation + installRotation) % 4
            val rotated = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
            val width = if (rotated) physical.logicalHeight else physical.logicalWidth
            val height = if (rotated) physical.logicalWidth else physical.logicalHeight
            val logicalWidth = info.logicalWidth
            val logicalHeight = info.logicalHeight
            val frameWidth: Int
            val frameHeight: Int
            if (scalingDisabled || info.flags and Display.FLAG_SCALING_DISABLED != 0) {
                frameWidth = logicalWidth; frameHeight = logicalHeight
            } else if (width.toLong() * logicalHeight < height.toLong() * logicalWidth) {
                frameWidth = width
                frameHeight = (logicalHeight.toLong() * width / logicalWidth).toInt()
            } else {
                frameWidth = (logicalWidth.toLong() * height / logicalHeight).toInt()
                frameHeight = height
            }
            val left = (width - frameWidth) / 2
            val top = (height - frameHeight) / 2
            val frame = Rect(left, top, left + frameWidth, top + frameHeight)
            when (rotation) {
                Surface.ROTATION_0 -> frame.offset(offsetX, offsetY)
                Surface.ROTATION_90 -> frame.offset(offsetY, -offsetX)
                Surface.ROTATION_180 -> frame.offset(-offsetX, -offsetY)
                Surface.ROTATION_270 -> frame.offset(-offsetY, offsetX)
            }
            position.set(frame.left, frame.top)
            output.configure(displayTransaction, Projection(rotation, Rect(0, 0, logicalWidth, logicalHeight), frame))
        }
        for (listener in transactionListeners) listener.onDisplayTransaction(transaction)
    }
}
