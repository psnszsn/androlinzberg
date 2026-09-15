package dev.artzig

import android.content.Context
import android.opengl.GLSurfaceView
import com.google.android.gles_jni.EGLImpl
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
import javax.microedition.khronos.opengles.GL10

/** Stock GLThread lifecycle through the public offscreen surface-factory seam. */
internal object StockGlSurfaceViewSmoke {
    private class TestView(context: Context) : GLSurfaceView(context) {
        fun finish() = super.onDetachedFromWindow()
    }

    fun run() {
        val egl = EGLContext.getEGL() as EGL10
        val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        val existingOwners = EGLImpl.getInitCount(display)
        if (!egl.eglInitialize(display, null)) {
            check(System.getenv("AZK_TEST_REQUIRE_EGL") != "1")
            return
        }
        val events = LinkedBlockingQueue<String>()
        val failure = AtomicReference<Throwable>()
        val renderThread = AtomicReference<Thread>()
        val created = AtomicInteger()
        val drawn = AtomicInteger()
        val surfaces = AtomicInteger()
        val destroyed = AtomicInteger()
        val view = TestView(HostContexts.systemContext(
            checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 320, 240, 120, 0))
        fun onRenderThread(action: () -> Unit) {
            try {
                renderThread.compareAndSet(null, Thread.currentThread())
                check(Thread.currentThread() === renderThread.get())
                action()
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
                events.offer("failure")
            }
        }
        fun expect(vararg expected: String) {
            for (event in expected) {
                val actual = events.poll(10, TimeUnit.SECONDS)
                failure.get()?.let { throw AssertionError("stock GLThread callback", it) }
                check(actual == event) { "GLSurfaceView expected $event, got $actual" }
            }
        }
        view.setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        view.setEGLWindowSurfaceFactory(object : GLSurfaceView.EGLWindowSurfaceFactory {
            override fun createWindowSurface(egl: EGL10, display: EGLDisplay, config: EGLConfig, nativeWindow: Any?): EGLSurface {
                surfaces.incrementAndGet()
                return egl.eglCreatePbufferSurface(display, config,
                    intArrayOf(EGL10.EGL_WIDTH, 16, EGL10.EGL_HEIGHT, 8, EGL10.EGL_NONE))
            }
            override fun destroySurface(egl: EGL10, display: EGLDisplay, surface: EGLSurface) {
                check(egl.eglDestroySurface(display, surface))
                destroyed.incrementAndGet()
            }
        })
        view.debugFlags = GLSurfaceView.DEBUG_CHECK_GL_ERROR
        view.preserveEGLContextOnPause = true
        view.setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl: GL10, config: EGLConfig) = onRenderThread {
                created.incrementAndGet()
                events.offer("created")
            }
            override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) = onRenderThread {
                check(width == 16 && height == 8)
                gl.glViewport(0, 0, width, height)
                events.offer("changed")
            }
            override fun onDrawFrame(gl: GL10) = onRenderThread {
                gl.glClearColor(1f, 0f, 0f, 1f)
                gl.glClear(GL10.GL_COLOR_BUFFER_BIT)
                val pixel = ByteBuffer.allocateDirect(4)
                gl.glReadPixels(0, 0, 1, 1, GL10.GL_RGBA, GL10.GL_UNSIGNED_BYTE, pixel)
                check(pixel[0].toInt() and 255 == 255 && pixel[1] == 0.toByte() && pixel[2] == 0.toByte())
                drawn.incrementAndGet()
                events.offer("draw")
            }
        })
        view.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        try {
            check(view.debugFlags == GLSurfaceView.DEBUG_CHECK_GL_ERROR)
            check(runCatching { view.renderMode = -1 }.exceptionOrNull() is IllegalArgumentException)
            view.surfaceCreated(view.holder)
            view.surfaceChanged(view.holder, 0, 16, 8)
            expect("created", "changed", "draw")
            check(renderThread.get() !== Thread.currentThread())
            check(EGLImpl.getInitCount(display) == existingOwners + 2)
            val redraw = CountDownLatch(1)
            view.surfaceRedrawNeededAsync(view.holder) { redraw.countDown() }
            check(redraw.await(10, TimeUnit.SECONDS))
            expect("draw")
            val beforePause = drawn.get()
            view.onPause()
            check(drawn.get() == beforePause && destroyed.get() == 1)
            val queued = CountDownLatch(1)
            view.queueEvent {
                onRenderThread {
                    check(egl.eglGetCurrentSurface(EGL10.EGL_DRAW) === EGL10.EGL_NO_SURFACE)
                    queued.countDown()
                }
            }
            check(queued.await(10, TimeUnit.SECONDS))
            check(drawn.get() == beforePause)
            view.onResume()
            expect("changed", "draw")
            check(created.get() == 1)
            view.preserveEGLContextOnPause = false
            view.onPause()
            check(EGLImpl.getInitCount(display) == existingOwners + 1)
            view.onResume()
            expect("created", "changed", "draw")
            check(created.get() == 2)
            view.surfaceDestroyed(view.holder)
            view.surfaceCreated(view.holder)
            view.surfaceChanged(view.holder, 0, 16, 8)
            expect("changed", "draw")
            check(created.get() == 2)
            view.requestRender()
            expect("draw")
        } finally {
            view.finish()
            check(egl.eglTerminate(display))
        }
        failure.get()?.let { throw AssertionError("stock GLThread callback", it) }
        check(surfaces.get() == destroyed.get())
        check(renderThread.get()?.isAlive == false)
        check(EGLImpl.getInitCount(display) == existingOwners)
        System.err.println("stock-glsurfaceview: renderer ordering, queued work, dirty redraws, pause/context preservation and surface/thread cleanup passed")
    }
}
