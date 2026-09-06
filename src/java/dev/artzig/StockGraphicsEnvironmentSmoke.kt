package dev.artzig

import android.os.Bundle
import android.os.GraphicsEnvironment
import com.android.graphics.graphicsenv.flags.Flags as GraphicsFlags

internal object StockGraphicsEnvironmentSmoke {
    @JvmStatic private external fun nativeState(): String

    fun run() {
        val context = HostContexts.systemContext(
            checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 320, 240, 120, 0)
        val graphics = GraphicsEnvironment.getInstance()
        val settings = Bundle()
        check(graphics.getDebugLayerPathsFromSettings(settings,
            android.app.ActivityThread.getPackageManager(), context.packageName,
            context.applicationInfo) == null)
        graphics.setLayerPaths(context.classLoader, "/tmp/azk-graphics-layers", "/tmp")
        graphics.setup(context, settings)
        check(nativeState() == "/tmp/azk-graphics-layers\n\n\nno-namespace") {
            "stock GraphicsEnvironment native configuration: ${nativeState()}"
        }
        graphics.toggleAngleAsSystemDriver(false)
        check(!GraphicsFlags.angleFeatureOverrides())
        System.err.println("stock-graphics-environment: default driver and native layer-path configuration passed")
    }
}
