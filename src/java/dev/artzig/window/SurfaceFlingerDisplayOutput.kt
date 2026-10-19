package dev.artzig.window

import android.content.Context
import android.os.Handler
import android.view.Display
import android.view.DisplayAddress
import com.android.server.display.DisplayControl
import android.view.DisplayInfo
import android.view.SurfaceControl

/** LocalDisplayAdapter's SF metadata/projection boundary for the host primary output. */
class SurfaceFlingerDisplayOutput private constructor() : HostedLogicalDisplay.Output {
    private val physicalId = DisplayControl.getPhysicalDisplayIds().single()
    private val token = checkNotNull(DisplayControl.getPhysicalDisplayToken(physicalId))
    private val staticInfo = checkNotNull(SurfaceControl.getStaticDisplayInfo(physicalId))
    private val dynamicInfo = checkNotNull(SurfaceControl.getDynamicDisplayInfo(physicalId))
    private val compositorMode = dynamicInfo.supportedDisplayModes.single().also {
        check(it.id == dynamicInfo.activeDisplayModeId)
        check(it.width > 0 && it.height > 0 && it.peakRefreshRate > 0f)
    }
    // HostComposer has one fixed physical compositorMode. Private task composition outputs
    // are virtual displays and cannot become Android display discoveries here.
    val info = DisplayInfo().apply {
        displayId = Display.DEFAULT_DISPLAY
        displayGroupId = Display.DEFAULT_DISPLAY_GROUP
        layerStack = 0
        name = "Androlinzberg desktop"
        uniqueId = "local:$physicalId"
        address = DisplayAddress.fromPhysicalDisplayId(physicalId)
        type = Display.TYPE_INTERNAL
        flags = Display.FLAG_TRUSTED
        appWidth = compositorMode.width; logicalWidth = compositorMode.width
        appHeight = compositorMode.height; logicalHeight = compositorMode.height
        logicalDensityDpi = (staticInfo.density * 160).toInt().also { check(it > 0) }
        physicalXDpi = compositorMode.xDpi; physicalYDpi = compositorMode.yDpi
        modeId = 1; defaultModeId = 1
        supportedModes = arrayOf(Display.Mode(1, compositorMode.width, compositorMode.height, compositorMode.peakRefreshRate,
            compositorMode.vsyncRate, floatArrayOf(), compositorMode.supportedHdrTypes))
        renderFrameRate = dynamicInfo.renderFrameRate
        appVsyncOffsetNanos = compositorMode.appVsyncOffsetNanos
        presentationDeadlineNanos = compositorMode.presentationDeadlineNanos
        supportedColorModes = dynamicInfo.supportedColorModes
        colorMode = dynamicInfo.activeColorMode
        hdrCapabilities = dynamicInfo.hdrCapabilities
        deviceProductInfo = staticInfo.deviceProductInfo
        state = Display.STATE_ON
        committedState = Display.STATE_ON
    }

    override fun configure(transaction: SurfaceControl.Transaction,
        projection: HostedLogicalDisplay.Projection) {
        transaction.setDisplayLayerStack(token, 0)
            .setDisplayFlags(token, SurfaceControl.DISPLAY_RECEIVES_INPUT)
            .setDisplayProjection(token, projection.rotation, projection.viewport, projection.frame)
    }

    companion object {
        /** The composer's real mode must cover the synthetic task plane. Merely
         * advertising a larger mode to Android leaves SF clipping cursor layers
         * at the smaller physical boundary. Stock FramebufferSurface separately
         * caps primary client buffers; task virtual displays keep their own size.
         */
        internal fun taskDisplayInfo(compositor: DisplayInfo): DisplayInfo {
            check(compositor.logicalWidth == TaskGeometry.DESKTOP_SIZE &&
                compositor.logicalHeight == TaskGeometry.DESKTOP_SIZE &&
                compositor.supportedModes.all { it.physicalWidth == TaskGeometry.DESKTOP_SIZE &&
                    it.physicalHeight == TaskGeometry.DESKTOP_SIZE }) { "Composer does not cover the task coordinate plane" }
            return DisplayInfo(compositor)
        }

        fun createService(context: Context, handler: Handler): HostedSessionDisplayService {
            val output = SurfaceFlingerDisplayOutput()
            // Like LocalDisplayDevice.requestDisplayStateLocked, power the physical
            // compositor output before publishing its ON state to framework clients.
            SurfaceControl.setDisplayPowerMode(output.token, SurfaceControl.POWER_MODE_NORMAL)
            val info = if (System.getenv("AZK_TASK_WINDOWS") == "1") taskDisplayInfo(output.info) else output.info
            return HostedSessionDisplayService(context, handler, info, output,
                SurfaceControl.getCompositionColorSpaces()[1].id, SurfaceControl.getOverlaySupport(),
                output.staticInfo.installOrientation)
        }
    }
}
