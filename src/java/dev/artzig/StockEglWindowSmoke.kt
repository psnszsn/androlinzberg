package dev.artzig

import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.view.Surface
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLContext as LegacyContext

/** Exercises the original EGL JNI against real Android buffer-queue consumers. */
internal object StockEglWindowSmoke {
    private external fun nativeSameBuffer(library: String, surface: Long,
        buffer: android.hardware.HardwareBuffer): Boolean
    private external fun nativeDamageSwap(library: String, display: Long, surface: Long): Boolean
    private fun verifyStorage(image: android.media.Image, surface: Long) {
        val path = checkNotNull(System.getenv("ARTZIG_INSTALL_ROOT")) + "/lib/libandroid_platform.so"
        checkNotNull(image.hardwareBuffer).use { check(nativeSameBuffer(path, surface, it)) {
            "EGL copied the frame: consumer storage differs from Mesa's rendered DMA-BUF"
        } }
    }
    fun run() {
        val cache = java.nio.file.Files.createTempDirectory("azk-egl-cache-").toFile()
        try {
            android.graphics.HardwareRenderer.setupDiskCache(cache)
            modern(cache.name)
            check(java.io.File(cache, "com.android.opengl.shaders_cache").length() > 8) {
                "Mesa shader blobs were not persisted by the Android EGL cache"
            }
            // Reopen the original file format and recreate the same program/context.
            android.graphics.HardwareRenderer.setupDiskCache(cache)
            modern(cache.name)
            legacy()
        } finally {
            // HWUI's process-wide cache keeps this directory until VM shutdown.
            cache.deleteOnExit()
            java.io.File(cache, "com.android.opengl.shaders_cache").deleteOnExit()
            java.io.File(cache, "com.android.skia.shaders_cache").deleteOnExit()
        }
        println("stock-egl-cache: original shader cache persisted and reopened across EGL contexts")
        println("stock-egl-window: EGL14/EGL10 DMA-BUF identity, pixels, timestamps, GL state, resize and held-buffer ownership passed")
    }

    private fun compileCacheProgram(seed: String) {
        val shaders = listOf(
            GLES20.GL_VERTEX_SHADER to "// $seed\nvoid main() { gl_Position=vec4(0.0,0.0,0.0,1.0); gl_PointSize=1.0; }",
            GLES20.GL_FRAGMENT_SHADER to "// $seed\nprecision mediump float; void main() { gl_FragColor=vec4(1.0,0.0,0.0,1.0); }")
            .map { (type, source) ->
                GLES20.glCreateShader(type).also { shader ->
                    GLES20.glShaderSource(shader, source)
                    GLES20.glCompileShader(shader)
                    val status = IntArray(1)
                    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
                    check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
                }
            }
        val program = GLES20.glCreateProgram()
        try {
            shaders.forEach { GLES20.glAttachShader(program, it) }
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(program) }
            GLES20.glUseProgram(program)
            GLES20.glViewport(0, 0, 16, 8)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)
            GLES20.glFinish()
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
        } finally {
            GLES20.glUseProgram(0)
            GLES20.glDeleteProgram(program)
            shaders.forEach { GLES20.glDeleteShader(it) }
        }
    }

    private fun modern(shaderSeed: String) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display, null, 0, null, 0))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] == 1)
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, 0x3570, 1, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT)
        var window = EGL14.EGL_NO_SURFACE
        val reader = ImageReader.newInstance(16, 8, PixelFormat.RGBA_8888, 2)
        try {
            window = EGL14.eglCreateWindowSurface(display, configs[0], reader.surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(window != EGL14.EGL_NO_SURFACE) { "create window: ${EGL14.eglGetError().toString(16)}" }
            check(EGL14.eglMakeCurrent(display, window, window, context))
            compileCacheProgram(shaderSeed)
            repeat(20) { frame ->
                paint(16, 8)
                val timestamp = 123_456_789L + frame
                check(EGLExt.eglPresentationTimeANDROID(display, window, timestamp))
                val swapped = if (frame % 2 == 0) EGL14.eglSwapBuffers(display, window) else
                    nativeDamageSwap(checkNotNull(System.getenv("ARTZIG_INSTALL_ROOT")) + "/lib/libandroid_platform.so",
                        display.nativeHandle, window.nativeHandle)
                check(swapped) { "swap: ${EGL14.eglGetError().toString(16)}" }
                check(EGL14.eglGetCurrentContext() == context)
                check(EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == window)
                check(GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST))
                check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
                verifyImage(reader, timestamp, window.nativeHandle)
            }
            paint(16, 8)
            check(EGL14.eglSwapBuffers(display, window))
            checkNotNull(reader.acquireNextImage()).use { held ->
                verifyStorage(held, window.nativeHandle)
                repeat(12) {
                    GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                    GLES20.glClearColor(0f, 1f, 0f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    check(EGL14.eglSwapBuffers(display, window))
                    checkNotNull(reader.acquireNextImage()).use { next ->
                        verifyStorage(next, window.nativeHandle)
                        val p = next.planes[0]
                        check((p.buffer.get(1).toInt() and 255) == 255)
                    }
                }
                // Map only after repeated reuse of the other buffers: a CPU
                // snapshot taken earlier could hide premature producer reuse.
                val p = held.planes[0]
                val offset = p.rowStride + p.pixelStride
                check((0..3).map { p.buffer.get(offset + it).toInt() and 255 } == listOf(0, 0, 255, 255))
            }
            // Let another thread release two acquired images after the
            // producer has filled the remaining queue. Exercise backpressure
            // without requiring the consumer to enter EGL on this thread.
            fun solid(r: Float, g: Float, b: Float) {
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                GLES20.glClearColor(r, g, b, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                check(EGL14.eglSwapBuffers(display, window))
            }
            solid(0f, 0f, 1f)
            checkNotNull(reader.acquireNextImage()).use { first ->
                solid(0f, 1f, 0f)
                checkNotNull(reader.acquireNextImage()).use { second ->
                    solid(1f, 0f, 0f)
                    val releaseError = java.util.concurrent.atomic.AtomicReference<Throwable>()
                    val releaser = Thread {
                        try {
                            Thread.sleep(100)
                            check((first.planes[0].buffer.get(2).toInt() and 255) == 255)
                            check((second.planes[0].buffer.get(1).toInt() and 255) == 255)
                        } catch (failure: Throwable) { releaseError.set(failure) }
                        finally { first.close(); second.close() }
                    }
                    releaser.start()
                    try { solid(1f, 1f, 1f) } finally { releaser.join(5000) }
                    check(!releaser.isAlive)
                    releaseError.get()?.let { throw it }
                }
            }
            checkNotNull(reader.acquireLatestImage()).use { latest ->
                verifyStorage(latest, window.nativeHandle)
                check((0..3).all { (latest.planes[0].buffer.get(it).toInt() and 255) == 255 })
            }
            reader.close()
            check(!EGL14.eglSwapBuffers(display, window))
            check(EGL14.eglGetError() == EGL14.EGL_BAD_NATIVE_WINDOW)
            check(EGL14.eglDestroySurface(display, window))
            // Destruction defers releasing the drawable until its current binding is removed.
            check(EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == window)
            check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT))
            window = EGL14.EGL_NO_SURFACE

            val retainedReader = ImageReader.newInstance(16, 8, PixelFormat.RGBA_8888, 2)
            try {
                window = EGL14.eglCreateWindowSurface(display, configs[0], retainedReader.surface, intArrayOf(EGL14.EGL_NONE), 0)
                check(window != EGL14.EGL_NO_SURFACE)
                check(EGL14.eglMakeCurrent(display, window, window, context))
                paint(16, 8)
                check(EGL14.eglSwapBuffers(display, window))
                checkNotNull(retainedReader.acquireNextImage()).use { retained ->
                    verifyStorage(retained, window.nativeHandle)
                    check(EGL14.eglDestroySurface(display, window))
                    check(!EGL14.eglMakeCurrent(display, window, window, context))
                    check(EGL14.eglGetError() == EGL14.EGL_BAD_SURFACE)
                    check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT))
                    window = EGL14.EGL_NO_SURFACE
                    // Importing retains storage independently of both Mesa's
                    // drawable and our native-window adapter.
                    val p = retained.planes[0]
                    val offset = p.rowStride + p.pixelStride
                    check((0..3).map { p.buffer.get(offset + it).toInt() and 255 } == listOf(0, 0, 255, 255))
                }
            } finally { retainedReader.close() }

            val consumer = SurfaceTexture(false)
            consumer.setDefaultBufferSize(16, 8)
            val producer = Surface(consumer)
            try {
                window = EGL14.eglCreateWindowSurface(display, configs[0], producer, intArrayOf(EGL14.EGL_NONE), 0)
                check(window != EGL14.EGL_NO_SURFACE)
                check(EGL14.eglMakeCurrent(display, window, window, context))
                // The EGL connection owns its native window independently of the Java Surface.
                producer.release()
                val texture = IntArray(1)
                GLES20.glGenTextures(1, texture, 0)
                consumer.attachToGLContext(texture[0])
                for ((width, height) in (listOf(16 to 8, 32 to 12, 7 to 5) + List(20) { 7 to 5 })) {
                    consumer.setDefaultBufferSize(width, height)
                    paint(width, height)
                    check(EGL14.eglSwapBuffers(display, window)) { "resize swap: ${EGL14.eglGetError().toString(16)}" }
                    consumer.updateTexImage()
                    val size = IntArray(1)
                    check(EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, size, 0) && size[0] == width)
                    check(EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, size, 0) && size[0] == height)
                    check(EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == window)
                    check(EGL14.eglGetCurrentContext() == context)
                    check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
                }
                consumer.detachFromGLContext()
            } finally { producer.release(); consumer.release() }
        } finally {
            reader.close()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
        }
    }

    private fun legacy() {
        val egl = LegacyContext.getEGL() as EGL10
        val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        check(egl.eglInitialize(display, IntArray(2)))
        val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(1)
        val count = IntArray(1)
        check(egl.eglChooseConfig(display, intArrayOf(EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT,
            EGL10.EGL_RENDERABLE_TYPE, 4, EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_NONE), configs, 1, count))
        val context = egl.eglCreateContext(display, configs[0], EGL10.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE))
        val reader = ImageReader.newInstance(16, 8, PixelFormat.RGBA_8888, 2)
        val window = egl.eglCreateWindowSurface(display, configs[0], reader.surface, intArrayOf(EGL10.EGL_NONE))
        try {
            check(window != EGL10.EGL_NO_SURFACE)
            check(egl.eglMakeCurrent(display, window, window, context))
            paint(16, 8)
            check(egl.eglSwapBuffers(display, window))
            check(egl.eglGetCurrentSurface(EGL10.EGL_DRAW) == window)
            check(egl.eglGetCurrentContext() == context)
            verifyImage(reader, null)
        } finally {
            egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
            egl.eglDestroySurface(display, window)
            egl.eglDestroyContext(display, context)
            egl.eglTerminate(display)
            reader.close()
            EGL14.eglReleaseThread()
        }
    }

    private fun paint(width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 1f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(0, 0, width / 2, height / 2)
        GLES20.glClearColor(1f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    private fun verifyImage(reader: ImageReader, timestamp: Long?, surface: Long? = null) {
        val image = checkNotNull(reader.acquireNextImage()) { "EGL did not deliver an Android image" }
        try {
            if (timestamp != null) check(image.timestamp == timestamp)
            if (surface != null) verifyStorage(image, surface)
            val plane = image.planes[0]
            val bytes = plane.buffer
            fun pixel(x: Int, y: Int, r: Int, g: Int, b: Int) {
                val offset = y * plane.rowStride + x * plane.pixelStride
                val actual = (0..3).map { bytes.get(offset + it).toInt() and 255 }
                check(actual == listOf(r, g, b, 255)) { "EGL image ($x,$y): $actual expected [$r,$g,$b,255]" }
            }
            pixel(1, 1, 0, 0, 255)
            pixel(1, 6, 255, 0, 0)
            pixel(14, 6, 0, 0, 255)
        } finally { image.close() }
    }
}
