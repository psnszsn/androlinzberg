package dev.artzig

import android.app.ActivityThread
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.Camera
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/** Legacy parameter encoding and service/error contracts without camera hardware. */
internal object StockLegacyCameraSmoke {
    private inline fun <reified T : Throwable> rejects(action: () -> Unit): T {
        try { action() } catch (error: Throwable) {
            val cause = if (error is InvocationTargetException) error.targetException else error
            check(cause is T) { "Expected ${T::class.java.name}, got $cause" }
            return cause
        }
        error("Expected ${T::class.java.name}")
    }

    private fun parameters() {
        val parameters = Camera.getEmptyParameters()
        rejects<StringIndexOutOfBoundsException> { parameters.flatten() }
        parameters.setPreviewSize(640, 480)
        parameters.setPictureSize(1280, 720)
        parameters.setPreviewFormat(ImageFormat.NV21)
        parameters.setPictureFormat(ImageFormat.JPEG)
        parameters.setRotation(270)
        parameters.setPreviewFpsRange(15_000, 30_000)
        parameters.setFocusAreas(listOf(Camera.Area(Rect(-100, -200, 300, 400), 500)))
        check(parameters.previewSize.width == 640 && parameters.previewSize.height == 480)
        check(parameters.pictureSize.width == 1280 && parameters.pictureSize.height == 720)
        check(parameters.previewFormat == ImageFormat.NV21 && parameters.pictureFormat == ImageFormat.JPEG)
        val fps = IntArray(2)
        parameters.getPreviewFpsRange(fps)
        check(fps.contentEquals(intArrayOf(15_000, 30_000)))
        val focus = parameters.focusAreas.single()
        check(focus.rect == Rect(-100, -200, 300, 400) && focus.weight == 500)
        val copy = Camera.getParametersCopy(parameters)
        check(copy.same(parameters))
        copy.setPreviewSize(320, 240)
        check(!copy.same(parameters) && parameters.previewSize.width == 640)
        val decoded = Camera.getEmptyParameters()
        decoded.unflatten(parameters.flatten())
        check(decoded.same(parameters))
        rejects<IllegalArgumentException> { parameters.setRotation(45) }
        rejects<IllegalArgumentException> { parameters.setPreviewFormat(-1) }
        val before = parameters.flatten()
        parameters.set("bad;key", "value")
        parameters.set("bad-value", "bad=value")
        check(parameters.flatten() == before)
        decoded.unflatten("first=1;ignored;second=2;first=3")
        check(decoded["first"] == "3" && decoded["second"] == "2" && decoded["ignored"] == null)
        decoded.set("first", 4)
        check(decoded.flatten() == "second=2;first=4")
    }

    private fun nativeFailures() {
        val camera = Camera.openUninitialized()
        val excluded = setOf("_getNumberOfCameras", "_getCameraInfo", "native_setup", "native_release", "_addCallbackBuffer")
        var checked = 0
        for (method in Camera::class.java.declaredMethods) {
            if (!Modifier.isNative(method.modifiers) || method.name in excluded) continue
            method.isAccessible = true
            val arguments = method.parameterTypes.map { type ->
                when (type) { Integer.TYPE -> -1; java.lang.Boolean.TYPE -> false; else -> null }
            }.toTypedArray()
            val error = rejects<RuntimeException> { method.invoke(camera, *arguments) }
            val expected = if (method.name in setOf("setAudioRestriction", "getAudioRestriction"))
                "camera has been disconnected" else "Camera is being used after Camera.release() was called"
            check(error.message == expected) { "${method.name}: ${error.message}" }
            checked++
        }
        check(checked == 24)
        camera.addCallbackBuffer(null)
        camera.release()
        camera.release()
        rejects<RuntimeException> { camera.startPreview() }
    }

    private fun services() {
        check(android.os.SystemProperties.getBoolean("config.disable_cameraservice", false))
        val context = ActivityThread.currentApplication().applicationContext
        check(Camera.getNumberOfCameras(context) == 0 && Camera.open() == null)
        for (id in listOf(-1, 0, Int.MAX_VALUE)) {
            check(rejects<RuntimeException> { Camera.getCameraInfo(id, context, 0, null) }.message == "Unknown camera ID")
        }
        check(rejects<RuntimeException> { Camera.open(0, context, 0) }.message == "Fail to connect to camera service")
    }

    fun run() {
        parameters()
        nativeFailures()
        services()
        println("stock legacy camera parameters, service queries and absent/released errors: PASS")
    }
}
