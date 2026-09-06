package dev.artzig

import android.hardware.SyncFence
import android.opengl.EGL14
import android.opengl.EGL15
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.time.Duration

/** Public EGL 1.5 contracts against the existing real offscreen EGL context. */
internal object StockEgl15Smoke {
    fun run(display: EGLDisplay, context: EGLContext) {
        check(EGL15.EGL_NO_DISPLAY == EGL14.EGL_NO_DISPLAY)
        check(EGL15.EGL_NO_CONTEXT == EGL14.EGL_NO_CONTEXT)
        check(EGL15.EGL_NO_SYNC == null && EGL15.EGL_NO_IMAGE == null)
        check(runCatching {
            EGL15.eglCreateSync(display, EGL15.EGL_SYNC_FENCE, null, 0)
        }.exceptionOrNull()?.message == "attrib_list == null")
        check(runCatching {
            EGL15.eglCreateSync(display, EGL15.EGL_SYNC_FENCE, longArrayOf(EGL14.EGL_NONE.toLong()), -1)
        }.exceptionOrNull()?.message == "offset < 0")
        check(runCatching {
            EGL15.eglCreatePlatformPixmapSurface(display, null, null, null, 0)
        }.exceptionOrNull() is UnsupportedOperationException)
        check(runCatching {
            EGLExt.eglPresentationTimeANDROID(display, null, 0)
        }.exceptionOrNull() is IllegalArgumentException)

        val attributes = longArrayOf(-8, EGL14.EGL_NONE.toLong())
        val sync = checkNotNull(EGL15.eglCreateSync(display, EGL15.EGL_SYNC_FENCE, attributes, 1))
        try {
            val value = longArrayOf(-1, -1, -1)
            check(EGL15.eglGetSyncAttrib(display, sync, EGL15.EGL_SYNC_TYPE, value, 1))
            check(value.contentEquals(longArrayOf(-1, EGL15.EGL_SYNC_FENCE.toLong(), -1)))
            check(!EGL15.eglGetSyncAttrib(display, sync, -99, value, 1))
            check(EGL14.eglGetError() == EGL14.EGL_BAD_ATTRIBUTE)
            check(value.contentEquals(longArrayOf(-1, EGL15.EGL_SYNC_FENCE.toLong(), -1)))
            val initial = EGL15.eglClientWaitSync(display, sync, EGL15.EGL_SYNC_FLUSH_COMMANDS_BIT, 0)
            check(initial == EGL15.EGL_TIMEOUT_EXPIRED || initial == EGL15.EGL_CONDITION_SATISFIED)
            check(EGL15.eglWaitSync(display, sync, 0))
            GLES20.glFinish()
            check(EGL15.eglClientWaitSync(display, sync, 0, EGL15.EGL_FOREVER) == EGL15.EGL_CONDITION_SATISFIED)
            check(EGL15.eglGetSyncAttrib(display, sync, EGL15.EGL_SYNC_STATUS, value, 1))
            check(value[1] == EGL15.EGL_SIGNALED.toLong())
        } finally {
            check(EGL15.eglDestroySync(display, sync))
        }
        image(display, context)
        val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty().split(' ')
        if ("EGL_ANDROID_native_fence_sync" in extensions) {
            nativeFence(display)
        } else {
            System.err.println("stock-egl15: driver does not advertise native fence export")
        }
        check(EGL14.eglGetError() == EGL14.EGL_SUCCESS)
        System.err.println("stock-egl15: sync waits, attribute offsets, errors and texture image ownership passed")
    }

    private fun image(display: EGLDisplay, context: EGLContext) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 2, 2, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(16))
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
            val image = checkNotNull(EGL15.eglCreateImage(display, context, EGL15.EGL_GL_TEXTURE_2D,
                textures[0].toLong(), longArrayOf(EGL15.EGL_IMAGE_PRESERVED.toLong(),
                    EGL14.EGL_TRUE.toLong(), EGL14.EGL_NONE.toLong()), 0))
            check(image.nativeHandle != 0L)
            check(EGL15.eglDestroyImage(display, image))
            check(!EGL15.eglDestroyImage(display, image))
            check(EGL14.eglGetError() == EGL14.EGL_BAD_PARAMETER)
        } finally {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GLES20.glDeleteTextures(1, textures, 0)
        }
    }

    private fun nativeFence(display: EGLDisplay) {
        val sync = checkNotNull(EGL15.eglCreateSync(display, EGLExt.EGL_SYNC_NATIVE_FENCE_ANDROID,
            longArrayOf(EGL14.EGL_NONE.toLong()), 0))
        var destroyed = false
        try {
            GLES20.glFlush()
            EGLExt.eglDupNativeFenceFDANDROID(display, sync).use { exported ->
                check(exported.isValid)
                SyncFence(exported).use { copy ->
                    exported.close()
                    GLES20.glFinish()
                    check(EGL15.eglDestroySync(display, sync))
                    destroyed = true
                    check(copy.isValid && copy.await(Duration.ZERO))
                    check(copy.signalTime >= 0 && copy.signalTime != SyncFence.SIGNAL_TIME_PENDING)
                    copy.fdDup.use { check(it.fd >= 0) }
                }
            }
        } finally {
            if (!destroyed) check(EGL15.eglDestroySync(display, sync))
        }
        System.err.println("stock-egl15: exported GPU sync_file survives EGLSync and original SyncFence destruction")
    }
}
