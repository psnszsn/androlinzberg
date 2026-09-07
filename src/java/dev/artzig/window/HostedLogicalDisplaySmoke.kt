package dev.artzig.window

import android.graphics.Point
import android.graphics.Rect
import android.view.Display
import android.view.DisplayInfo
import android.view.Surface
import android.view.SurfaceControl
import android.hardware.display.DisplayManagerInternal.DisplayTransactionListener

/** WM overrides affect geometry without changing display identity or supported modes. */
object HostedLogicalDisplaySmoke {
    fun run() {
        val base = DisplayInfo().apply {
            displayId = Display.DEFAULT_DISPLAY
            name = "desktop"
            logicalWidth = 1280; appWidth = 1280
            logicalHeight = 720; appHeight = 720
            logicalDensityDpi = 160
            modeId = 1; defaultModeId = 1
            state = Display.STATE_ON
            supportedModes = arrayOf(Display.Mode(1, 1280, 720, 60f))
        }
        var changed = 0
        var traversalRequests = 0
        val projections = mutableListOf<HostedLogicalDisplay.Projection>()
        val order = mutableListOf<String>()
        val display = HostedLogicalDisplay(base, object : HostedLogicalDisplay.Output {
            override fun configure(transaction: SurfaceControl.Transaction,
                projection: HostedLogicalDisplay.Projection) {
                projections.add(projection)
                order.add("configure")
            }
        }, { changed++ }, { traversalRequests++ })
        val listener = DisplayTransactionListener { order.add("listener") }
        display.registerDisplayTransactionListener(listener)
        SurfaceControl.Transaction().use { transaction ->
            display.performTraversal(transaction)
            check(order == listOf("configure", "listener"))
            check(projections.single() == HostedLogicalDisplay.Projection(0,
                Rect(0, 0, 1280, 720), Rect(0, 0, 1280, 720)))
            display.performTraversal(transaction)
            check(projections.size == 1)
            val override = DisplayInfo(base).apply {
                name = "must not replace identity"; modeId = 88; displayId = 900
                supportedModes = arrayOf(Display.Mode(88, 400, 400, 30f))
                logicalWidth = 400; appWidth = 390
                logicalHeight = 400; appHeight = 380
                logicalDensityDpi = 240
            }
            display.setDisplayInfoOverrideFromWindowManager(99, override)
            check(changed == 0 && display.getDisplayInfo(99) == null)
            display.setDisplayInfoOverrideFromWindowManager(0, override)
            check(changed == 1 && traversalRequests == 1)
            val info = checkNotNull(display.getDisplayInfo(0))
            check(info.name == "desktop" && info.displayId == 0 && info.modeId == 1)
            check(info.logicalWidth == 400 && info.logicalHeight == 400 && info.appWidth == 390)
            check(info.logicalDensityDpi == 240)
            val physical = DisplayInfo()
            display.getNonOverrideDisplayInfo(0, physical)
            check(physical.logicalWidth == 1280 && physical.logicalDensityDpi == 160)
            check(display.getStableDisplaySize() == Point(1280, 720))
            display.setDisplayInfoOverrideFromWindowManager(0, DisplayInfo(override))
            check(changed == 1)
            override.logicalWidth = 800 // Caller mutation must not change the retained override.
            check(display.getDisplayInfo(0)!!.logicalWidth == 400)
            display.performTraversal(transaction)
            check(projections.last().frame == Rect(280, 0, 1000, 720))
            check(display.getDisplayPosition(0) == Point(280, 0))
            val rotated = DisplayInfo(info).apply { rotation = Surface.ROTATION_90 }
            display.setDisplayInfoOverrideFromWindowManager(0, rotated)
            display.setDisplayOffsets(0, 10, 20)
            display.performTraversal(transaction)
            check(projections.last() == HostedLogicalDisplay.Projection(1,
                Rect(0, 0, 400, 400), Rect(20, 270, 740, 990)))
            display.setDisplayScalingDisabled(0, true)
            display.performTraversal(transaction)
            check(projections.last().frame == Rect(180, 430, 580, 830))
            display.unregisterDisplayTransactionListener(listener)
            display.setDisplayInfoOverrideFromWindowManager(0, null)
            display.setDisplayOffsets(0, 0, 0)
            display.setDisplayScalingDisabled(0, false)
            display.performTraversal(transaction)
            check(order.last() == "configure")
            check(display.getDisplayInfo(0)!!.logicalWidth == 1280)
            check(projections.last().frame == Rect(0, 0, 1280, 720))
            val previousRequests = traversalRequests
            val previousProjections = projections.size
            display.setDisplayProperties(0, true, true, true)
            check(display.isMinimalPostProcessingRequested() && traversalRequests == previousRequests)
            display.performTraversal(transaction)
            check(projections.size == previousProjections + 1)
            display.setDisplayProperties(0, true, true, false)
            check(traversalRequests == previousRequests)
            display.setDisplayProperties(900, false, false, false)
            check(display.isMinimalPostProcessingRequested())
            display.setDisplayProperties(0, false, false, false)
            check(!display.isMinimalPostProcessingRequested() && traversalRequests == previousRequests + 1)
        }
    }
}
