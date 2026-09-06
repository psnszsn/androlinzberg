package dev.artzig

import android.opengl.EGL14
import android.view.Surface
import com.google.android.gles_jni.EGLImpl
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.opengles.GL10

/** Public legacy EGL contracts and display ownership shared with EGL14. */
internal object StockLegacyEglSmoke {
    fun run() {
        val egl = EGLContext.getEGL() as EGL10
        val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        check(display === egl.eglGetDisplay(Any())) // Pinned native ignores native_display.
        check(egl.eglGetCurrentContext() === EGL10.EGL_NO_CONTEXT)
        check(egl.eglGetCurrentDisplay() === EGL10.EGL_NO_DISPLAY)
        check(EGLImpl.getInitCount(null) == 0)
        check(EGLImpl.getInitCount(EGL10.EGL_NO_DISPLAY) == 0)
        check(runCatching { egl.eglInitialize(display, IntArray(1)) }.exceptionOrNull() is IllegalArgumentException)
        check(!egl.eglInitialize(EGL10.EGL_NO_DISPLAY, null))
        check(egl.eglGetError() == EGL10.EGL_BAD_DISPLAY)
        check(runCatching { egl.eglGetCurrentSurface(-1) }.exceptionOrNull() is IllegalArgumentException)
        val modern = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (!EGL14.eglInitialize(modern, null, 0, null, 0)) {
            check(System.getenv("AZK_TEST_REQUIRE_EGL") != "1")
            return
        }
        var context = EGL10.EGL_NO_CONTEXT
        var surface = EGL10.EGL_NO_SURFACE
        try {
            check(EGLImpl.getInitCount(display) == 1)
            val version = intArrayOf(-7, -7, -7)
            check(egl.eglInitialize(display, version))
            check(version.contentEquals(intArrayOf(1, 0, -7)))
            check(EGLImpl.getInitCount(display) == 2)
            check(egl.eglInitialize(display, null))
            check(EGLImpl.getInitCount(display) == 3)
            check(egl.eglTerminate(display))
            check(EGLImpl.getInitCount(display) == 2)
            val count = intArrayOf(-9, -9)
            check(egl.eglGetConfigs(display, null, 0, count) && count[0] > 0 && count[1] == -9)
            val configs = arrayOfNulls<EGLConfig>(2)
            check(egl.eglGetConfigs(display, configs, 1, count))
            val first = checkNotNull(configs[0])
            check(configs[1] == null && count[0] == 1)
            configs[1] = first
            check(runCatching { egl.eglChooseConfig(display, intArrayOf(), configs, 1, count) }.exceptionOrNull() is IllegalArgumentException)
            check(runCatching { egl.eglGetConfigs(display, configs, 3, count) }.exceptionOrNull() is IllegalArgumentException)
            check(egl.eglChooseConfig(display, intArrayOf(EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_NONE), configs, 1, count))
            check(count[0] == 1 && configs[1] === first)
            val config = checkNotNull(configs[0])
            val value = intArrayOf(-8, -8)
            check(!egl.eglGetConfigAttrib(display, config, -1, value))
            check(value.contentEquals(intArrayOf(-8, -8)))
            check(egl.eglGetError() == EGL10.EGL_BAD_ATTRIBUTE)
            check(egl.eglGetConfigAttrib(display, config, EGL10.EGL_SURFACE_TYPE, value))
            check(value[0] and EGL10.EGL_PBUFFER_BIT != 0 && value[1] == -8)
            check(runCatching { egl.eglCreatePixmapSurface(display, config, Any(), null) }.exceptionOrNull() is UnsupportedOperationException)
            val invalidSurface = Surface()
            try {
                check(runCatching { egl.eglCreateWindowSurface(display, config, invalidSurface, null) }.exceptionOrNull() is IllegalArgumentException)
            } finally {
                invalidSurface.release()
            }
            context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, null)
            check(context != EGL10.EGL_NO_CONTEXT)
            surface = egl.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL10.EGL_WIDTH, 16, EGL10.EGL_HEIGHT, 8, EGL10.EGL_NONE))
            check(surface != EGL10.EGL_NO_SURFACE)
            check(egl.eglMakeCurrent(display, surface, surface, context))
            check(egl.eglGetCurrentContext() == context)
            check(egl.eglGetCurrentContext() === egl.eglGetCurrentContext())
            check(egl.eglGetCurrentDisplay() === display)
            check(egl.eglGetCurrentSurface(EGL10.EGL_DRAW) == surface)
            check(egl.eglGetCurrentSurface(EGL10.EGL_READ) == surface)
            check(egl.eglQuerySurface(display, surface, EGL10.EGL_WIDTH, value) && value[0] == 16 && value[1] == -8)
            check(egl.eglQueryContext(display, context, 0x3098, value) && value[0] == 1)
            check(!egl.eglCopyBuffers(display, surface, Any()))
            // The modern client's remaining reference must keep legacy objects usable.
            check(egl.eglTerminate(display))
            check(EGLImpl.getInitCount(display) == 1)
            concurrentOwners(egl, display)
            val gl = context.gl as GL10
            gl.glClearColor(0f, 0f, 1f, 1f)
            gl.glClear(GL10.GL_COLOR_BUFFER_BIT)
            check(egl.eglSwapBuffers(display, surface))
            check(egl.eglWaitGL())
            val pixels = ByteBuffer.allocateDirect(4)
            gl.glReadPixels(0, 0, 1, 1, GL10.GL_RGBA, GL10.GL_UNSIGNED_BYTE, pixels)
            check(pixels[0] == 0.toByte() && pixels[1] == 0.toByte() && pixels[2].toInt() and 255 == 255)
            check(gl.glGetError() == GL10.GL_NO_ERROR)
            check(egl.eglGetError() == EGL10.EGL_SUCCESS)
        } finally {
            egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
            if (surface != EGL10.EGL_NO_SURFACE) egl.eglDestroySurface(display, surface)
            if (context != EGL10.EGL_NO_CONTEXT) egl.eglDestroyContext(display, context)
            while (EGLImpl.getInitCount(display) > 0) check(EGL14.eglTerminate(modern))
            egl.eglReleaseThread()
        }
        check(EGLImpl.getInitCount(display) == 0)
        check(egl.eglTerminate(display))
        check(egl.eglInitialize(display, null))
        check(EGLImpl.getInitCount(display) == 1)
        check(egl.eglTerminate(display))
        check(EGLImpl.getInitCount(display) == 0)
        System.err.println("stock-legacy-egl: EGL 1.0 reporting, handle/config contracts, shared display references and pbuffer pixels passed")
    }

    private fun concurrentOwners(egl: EGL10, display: javax.microedition.khronos.egl.EGLDisplay) {
        val ready = CountDownLatch(4)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val threads = List(4) {
            Thread {
                var initialized = false
                try {
                    check(egl.eglInitialize(display, null))
                    initialized = true
                    ready.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                } finally {
                    if (initialized) {
                        try { check(egl.eglTerminate(display)) }
                        catch (error: Throwable) { failure.compareAndSet(null, error) }
                    }
                    egl.eglReleaseThread()
                }
            }.apply { start() }
        }
        try {
            check(ready.await(10, TimeUnit.SECONDS))
            check(EGLImpl.getInitCount(display) == 5)
        } finally {
            release.countDown()
            threads.forEach { it.join(10_000) }
        }
        failure.get()?.let { throw AssertionError("concurrent EGL display owner", it) }
        check(threads.none { it.isAlive })
        check(EGLImpl.getInitCount(display) == 1)
    }
}
