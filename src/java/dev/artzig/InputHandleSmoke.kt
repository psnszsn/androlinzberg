package dev.artzig

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.Region
import android.os.Binder
import android.os.InputConfig
import android.os.Process
import android.util.Size
import android.view.InputApplicationHandle
import android.view.InputChannel
import android.view.InputWindowHandle
import android.view.WindowManager

/** Stock JNI metadata conversion, including the WMS-input/SF-output distinction. */
object InputHandleSmoke {
    @JvmStatic private external fun application(value: InputApplicationHandle?): InputApplicationHandle?
    @JvmStatic private external fun window(value: InputWindowHandle): InputWindowHandle
    @JvmStatic private external fun composedWindow(value: InputWindowHandle): InputWindowHandle
    @JvmStatic private external fun hasFocusTransfer(value: InputWindowHandle, target: InputWindowHandle): Boolean

    fun run(library: String) {
        System.load(java.io.File(library).absolutePath)
        check(application(null) == null)
        val app = InputApplicationHandle(Binder(), "window-app-λ", 4800)
        val copiedApp = checkNotNull(application(app))
        check(copiedApp !== app && copiedApp.token === app.token)
        check(copiedApp.name == app.name && copiedApp.dispatchingTimeoutMillis == 4800L)
        check(application(app)?.token === app.token)

        val channels = InputChannel.openInputChannelPair("window-token")
        try {
            val handle = InputWindowHandle(app, 0).apply {
                token = channels[0].token
                windowToken = Binder()
                name = "input-window-λ"
                packageName = "dev.artzig.window-contract"
                frame.set(30, 40, 230, 340)
                surfaceInset = 3
                scaleFactor = 0.5f
                dispatchingTimeoutMillis = 3700
                layoutParamsFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                layoutParamsType = WindowManager.LayoutParams.TYPE_APPLICATION
                inputConfig = InputConfig.NOT_FOCUSABLE
                ownerPid = Process.myPid()
                ownerUid = Process.myUid()
                touchableRegion.set(40, 50, 90, 100)
                touchableRegion.op(Rect(150, 200, 190, 260), Region.Op.UNION)
            }
            val copied = window(handle)
            check(copied !== handle && copied.token === channels[1].token)
            check(copied.windowToken === handle.windowToken)
            check(copied.inputApplicationHandle.token === app.token)
            check(copied.inputApplicationHandle.name == app.name)
            check(copied.name == handle.name && copied.packageName == handle.packageName)
            check(copied.frame == handle.frame && copied.surfaceInset == 3)
            check(copied.scaleFactor == 0.5f && copied.dispatchingTimeoutMillis == 3700L)
            check(copied.layoutParamsFlags == handle.layoutParamsFlags)
            check(copied.layoutParamsType == handle.layoutParamsType)
            check(copied.inputConfig == InputConfig.NOT_FOCUSABLE)
            check(copied.ownerPid == handle.ownerPid && copied.ownerUid == handle.ownerUid)
            check(copied.touchOcclusionMode == handle.touchOcclusionMode)
            check(copied.touchableRegion == handle.touchableRegion)
            check(!copied.touchableRegion.contains(120, 140))

            // Cached native handles must refresh mutable metadata and clear old regions/tokens.
            handle.frame.set(-50, -20, 50, 80)
            handle.touchableRegion.set(-40, -10, 10, 30)
            handle.name = "updated"
            handle.token = null
            handle.windowToken = null
            handle.inputConfig = 0
            val updated = window(handle)
            check(updated.token == null && updated.windowToken == null)
            check(updated.name == "updated" && updated.frame == handle.frame)
            check(updated.inputConfig == 0 && updated.touchableRegion == handle.touchableRegion)
            check(!updated.touchableRegion.contains(160, 220))
            check(copied.name == "input-window-λ" && copied.frame == Rect(30, 40, 230, 340))

            val target = InputWindowHandle(app, 0).apply { token = Binder() }
            handle.focusTransferTarget = target.token
            check(hasFocusTransfer(handle, target))
            handle.focusTransferTarget = null
            check(!hasFocusTransfer(handle, target))

            // AOSP intentionally ignores these fields on input; SF supplies the outputs.
            handle.contentSize = Size(1, 2)
            handle.transform = Matrix().apply { setScale(19f, 23f) }
            handle.alpha = 0.125f
            val withoutComposition = window(handle)
            check(withoutComposition.contentSize == Size(0, 0))
            check(checkNotNull(withoutComposition.transform).isIdentity)
            val composed = composedWindow(handle)
            check(composed.contentSize == Size(640, 480))
            check(composed.alpha == 0.625f && composed.canOccludePresentation)
            val points = floatArrayOf(100f, 200f)
            checkNotNull(composed.transform).mapPoints(points)
            check(points.contentEquals(floatArrayOf(10f, 20f))) { "Native transform mismatch: ${points.toList()}" }
        } finally {
            channels.forEach { it.dispose() }
        }
        println("InputHandle: stock JNI application/window identity, geometry, regions, mutation and SF output metadata passed")
    }
}
