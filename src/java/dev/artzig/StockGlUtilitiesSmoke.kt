package dev.artzig

import android.graphics.Bitmap
import android.opengl.ETC1
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Visibility
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Stock OpenGL utilities on the framework fixture's current EGL context. */
object StockGlUtilitiesSmoke {
    fun run() {
        val sphere = FloatArray(6) { -9f }
        Visibility.computeBoundingSphere(floatArrayOf(-1f, 0f, 0f, 1f, 0f, 0f), 0, 2, sphere, 1)
        check(sphere.contentEquals(floatArrayOf(-9f, 0f, 0f, 0f, 1f, -9f)))
        val header = ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder())
        repeat(20) { header.put(it, 0x55.toByte()) }
        header.position(2)
        ETC1.formatHeader(header, 17, 9)
        check(ETC1.isValid(header) && ETC1.getWidth(header) == 17 && ETC1.getHeight(header) == 9)
        check(header.position() == 2 && header.get(0) == 0x55.toByte() && header.get(19) == 0x55.toByte())
        check(ETC1.getEncodedDataSize(17, 9) == 120)
        check(runCatching { ETC1.formatHeader(ByteBuffer.allocateDirect(1), 4, 4) }.exceptionOrNull() is IllegalArgumentException)
        val encoded = ByteBuffer.allocateDirect(ETC1.ENCODED_BLOCK_SIZE)
        val decoded = ByteBuffer.allocateDirect(ETC1.DECODED_BLOCK_SIZE)
        // The pinned JNI accepts only masks 0..15 for encodeBlock, despite the
        // documented 16-bit mask. Preserve that behavior; encodeImage covers all pixels.
        check(runCatching {
            ETC1.encodeBlock(ByteBuffer.allocateDirect(ETC1.DECODED_BLOCK_SIZE), 0xffff, encoded)
        }.exceptionOrNull() is IllegalArgumentException)
        ETC1.encodeImage(ByteBuffer.allocateDirect(ETC1.DECODED_BLOCK_SIZE), 4, 4, 3, 12, encoded)
        ETC1.decodeBlock(encoded, decoded)
        check((0 until decoded.capacity()).all { (decoded.get(it).toInt() and 255) <= 4 })

        val previousTexture = IntArray(1)
        val previousFramebuffer = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, previousTexture, 0)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previousFramebuffer, 0)
        val texture = IntArray(1)
        val framebuffer = IntArray(1)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val patch = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xff123456.toInt())
            patch.eraseColor(0xffabcdef.toInt())
            check(GLUtils.getInternalFormat(bitmap) == GLES20.GL_RGBA)
            check(GLUtils.getType(bitmap) == GLES20.GL_UNSIGNED_BYTE)
            GLES20.glGenTextures(1, texture, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 1, 0, patch)
            check(bitmap.getPixel(0, 0) == 0xff123456.toInt())
            check(patch.getPixel(0, 0) == 0xffabcdef.toInt())
            GLES20.glGenFramebuffers(1, framebuffer, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texture[0], 0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE)
            val pixels = ByteBuffer.allocateDirect(16)
            GLES20.glReadPixels(0, 0, 2, 2, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            repeat(4) { index ->
                val color = if (index == 1) intArrayOf(0xab, 0xcd, 0xef, 255) else intArrayOf(0x12, 0x34, 0x56, 255)
                repeat(4) { channel ->
                    check((pixels.get(index * 4 + channel).toInt() and 255) == color[channel]) {
                        "GLUtils pixel $index channel $channel: expected ${color[channel]}, got " +
                            (pixels.get(index * 4 + channel).toInt() and 255)
                    }
                }
            }
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previousFramebuffer[0])
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexture[0])
            GLES20.glDeleteFramebuffers(1, framebuffer, 0)
            GLES20.glDeleteTextures(1, texture, 0)
            bitmap.recycle()
            patch.recycle()
        }
    }
}
