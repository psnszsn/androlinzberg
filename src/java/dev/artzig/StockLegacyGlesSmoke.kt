package dev.artzig

import android.opengl.EGL14
import com.google.android.gles_jni.GLImpl
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.opengles.GL10
import javax.microedition.khronos.opengles.GL10Ext
import javax.microedition.khronos.opengles.GL11
import javax.microedition.khronos.opengles.GL11ExtensionPack
import kotlin.math.abs
import kotlin.math.pow

/** Fixed-function rendering and public NIO contracts through stock GLImpl. */
internal object StockLegacyGlesSmoke {
    fun run() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (!EGL14.eglInitialize(display, null, 0, null, 0)) {
            check(System.getenv("AZK_TEST_REQUIRE_EGL") != "1")
            return
        }
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] == 1)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 1, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 8, EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, surface, surface, context))
            val gl = GLImpl()
            check(gl.glGetString(GL10.GL_VERSION).contains("OpenGL ES-CM 1."))
            gl.glViewport(0, 0, 16, 8)
            gl.glDisable(GL10.GL_DITHER)
            typedBuffers(gl)
            gl.glMatrixMode(GL10.GL_PROJECTION)
            gl.glLoadIdentity()
            gl.glMatrixMode(GL10.GL_MODELVIEW)
            gl.glLoadIdentity()
            gl.glClearColor(0.25f, 0.5f, 0.75f, 1f)
            gl.glClear(GL10.GL_COLOR_BUFFER_BIT)
            pixel(gl, 64, 128, 191)
            val vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            vertices.put(floatArrayOf(-99f, -99f, -1f, -1f, 3f, -1f, -1f, 3f)).position(2)
            gl.glVertexPointer(2, GL10.GL_FLOAT, 0, vertices)
            gl.glEnableClientState(GL10.GL_VERTEX_ARRAY)
            gl.glColor4f(1f, 0f, 0f, 1f)
            gl.glDrawArrays(GL10.GL_TRIANGLES, 0, 3)
            gl.glDisableClientState(GL10.GL_VERTEX_ARRAY)
            pixel(gl, 255, 0, 0)
            check(vertices.position() == 2)
            val extensions = gl.glGetString(GL10.GL_EXTENSIONS).split(' ')
            if ("GL_OES_framebuffer_object" in extensions) framebuffer(gl)
            if ("GL_OES_query_matrix" in extensions) queryMatrix(gl)
            check(gl.glGetError() == GL10.GL_NO_ERROR)
            System.err.println("stock-legacy-gles: ES 1.x fixed-function pixels, direct/heap offsets and typed bounds passed")
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
        }
    }

    private fun typedBuffers(gl: GL11) {
        val names = IntArray(8) { -7 }
        val parent = IntBuffer.wrap(names).apply { position(2); limit(6) }
        val slice = parent.slice().apply { position(1); limit(3) }
        gl.glGenTextures(2, slice)
        check(names[3] > 0 && names[4] > 0 && names[3] != names[4])
        check(names.filterIndexed { i, _ -> i != 3 && i != 4 }.all { it == -7 })
        check(slice.position() == 1 && slice.limit() == 3)
        gl.glBindTexture(GL10.GL_TEXTURE_2D, names[3])
        check(gl.glIsTexture(names[3]))
        gl.glDeleteTextures(2, slice)
        check(!gl.glIsTexture(names[3]))
        check(runCatching { gl.glGenTextures(2, IntBuffer.allocate(1)) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { gl.glGetIntegerv(GL11.GL_VIEWPORT, IntBuffer.allocate(3)) }.exceptionOrNull() is IllegalArgumentException)
        val viewport = IntArray(6) { -7 }
        val output = IntBuffer.wrap(viewport).apply { position(1); limit(5) }
        gl.glGetIntegerv(GL11.GL_VIEWPORT, output)
        check(viewport.contentEquals(intArrayOf(-7, 0, 0, 16, 8, -7)) && output.position() == 1)
        val identity = FloatArray(16) { if (it % 5 == 0) 1f else 0f }
        val source = FloatArray(20) { -9f }
        identity.copyInto(source, 2)
        gl.glMatrixMode(GL10.GL_MODELVIEW)
        gl.glLoadMatrixf(FloatBuffer.wrap(source).apply { position(2); limit(18) })
        val values = FloatArray(22) { -9f }
        val matrix = FloatBuffer.wrap(values).apply { position(3); limit(19) }
        gl.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, matrix)
        check(values.sliceArray(3 until 19).contentEquals(identity))
        check(values.take(3).all { it == -9f } && values.drop(19).all { it == -9f })
        check(matrix.position() == 3)
    }

    private fun pixel(gl: GL10, red: Int, green: Int, blue: Int) {
        val values = ByteArray(8) { 17 }
        val buffer = ByteBuffer.wrap(values).position(2).limit(6)
        gl.glReadPixels(0, 0, 1, 1, GL10.GL_RGBA, GL10.GL_UNSIGNED_BYTE, buffer)
        check(buffer.position() == 2 && values.take(2).all { it == 17.toByte() } && values.drop(6).all { it == 17.toByte() })
        for ((index, expected) in intArrayOf(red, green, blue).withIndex()) {
            check(abs((values[index + 2].toInt() and 255) - expected) <= 1) { "legacy GLES pixel: ${values.toList()}" }
        }
    }

    private fun framebuffer(gl: GLImpl) {
        val textures = IntArray(1)
        val framebuffers = IntArray(1)
        gl.glGenTextures(1, textures, 0)
        gl.glGenFramebuffersOES(1, framebuffers, 0)
        try {
            gl.glBindTexture(GL10.GL_TEXTURE_2D, textures[0])
            gl.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_NEAREST)
            gl.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_NEAREST)
            gl.glTexImage2D(GL10.GL_TEXTURE_2D, 0, GL10.GL_RGBA, 2, 2, 0, GL10.GL_RGBA, GL10.GL_UNSIGNED_BYTE, null)
            gl.glBindFramebufferOES(GL11ExtensionPack.GL_FRAMEBUFFER_OES, framebuffers[0])
            gl.glFramebufferTexture2DOES(GL11ExtensionPack.GL_FRAMEBUFFER_OES, GL11ExtensionPack.GL_COLOR_ATTACHMENT0_OES,
                GL10.GL_TEXTURE_2D, textures[0], 0)
            check(gl.glCheckFramebufferStatusOES(GL11ExtensionPack.GL_FRAMEBUFFER_OES) == GL11ExtensionPack.GL_FRAMEBUFFER_COMPLETE_OES)
            gl.glViewport(0, 0, 2, 2)
            gl.glClearColor(0f, 1f, 0f, 1f)
            gl.glClear(GL10.GL_COLOR_BUFFER_BIT)
            pixel(gl, 0, 255, 0)
        } finally {
            gl.glBindFramebufferOES(GL11ExtensionPack.GL_FRAMEBUFFER_OES, 0)
            gl.glDeleteFramebuffersOES(1, framebuffers, 0)
            gl.glDeleteTextures(1, textures, 0)
        }
        System.err.println("stock-legacy-gles: OES framebuffer extension dispatch and pixels passed")
    }

    private fun queryMatrix(gl: GL10Ext) {
        val mantissa = IntArray(20) { -777 }
        val exponent = IntArray(21) { -888 }
        val m = IntBuffer.wrap(mantissa).apply { position(2); limit(18) }
        val e = IntBuffer.wrap(exponent).apply { position(3); limit(19) }
        check(gl.glQueryMatrixxOES(m, e) == 0)
        for (i in 0 until 16) {
            val value = mantissa[i + 2] / 65536.0 * 2.0.pow(exponent[i + 3])
            check(abs(value - if (i % 5 == 0) 1.0 else 0.0) < 0.0001)
        }
        check(mantissa.take(2).all { it == -777 } && mantissa.drop(18).all { it == -777 })
        check(exponent.take(3).all { it == -888 } && exponent.drop(19).all { it == -888 })
        check(m.position() == 2 && e.position() == 3)
        System.err.println("stock-legacy-gles: OES matrix query preserves both heap buffer slices")
    }
}
