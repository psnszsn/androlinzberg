package dev.artzig

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.ArtzigVideoLayer
import android.view.Surface
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Video-layer test: decodes /tmp/azk-test.m4v (u32le-size-prefixed mpeg4
 * frames) through MediaCodec into a Wayland subsurface, while the UI bitmap
 * punches a transparent hole. draw() is called per frame by the launcher.
 */
public final class VideoHost {
    companion object {
        private lateinit var sSurface: Surface
        private lateinit var sCodec: MediaCodec
        private lateinit var sStream: ByteBuffer
        private var sInfo: MediaCodec.BufferInfo = MediaCodec.BufferInfo()
        private var sInputDone: Boolean = false
        private var sOutputDone: Boolean = false
        private var sFrames: Int = 0
        private val VX: Int = 480
        private val VY: Int = 200
        private val VW: Int = 320
        private val VH: Int = 240

        @JvmStatic public fun motion(
            action: Int,
            x: Float,
            y: Float,
        ) {}

        @Throws(Exception::class)
        @JvmStatic
        public fun draw(
            width: Int,
            height: Int,
        ): Bitmap {
            if (!::sSurface.isInitialized) init()
            pump()
            val bmp: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val c: Canvas = Canvas(bmp)
            c.drawColor(0xFF203040.toInt())
            val p: Paint = Paint()
            p.setColor(Color.WHITE)
            p.setTextSize(28f)
            c.drawText(
                "artzig video layer: " + sFrames + " frames" +
                    (if (sOutputDone) " (done)" else ""),
                40f,
                80f,
                p,
            )
            // punch the hole for the subsurface
            p.setColor(Color.TRANSPARENT)
            p.setXfermode(
                android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR),
            )
            c.drawRect(VX.toFloat(), VY.toFloat(), (VX + VW).toFloat(), (VY + VH).toFloat(), p)
            return bmp
        }

        @Throws(Exception::class)
        @JvmStatic
        private fun init() {
            android.graphics.Typeface.loadPreinstalledSystemFontMap()
            var data: ByteArray
            FileInputStream("/tmp/azk-test.m4v").use { `in`: FileInputStream ->
                data = `in`.readAllBytes()
            }
            sStream = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            sSurface = ArtzigVideoLayer.createSurface()
            ArtzigVideoLayer.setRect(sSurface, VX, VY, VW, VH)
            sCodec = MediaCodec.createDecoderByType("video/mp4v-es")
            val fmt: MediaFormat = MediaFormat.createVideoFormat("video/mp4v-es", 320, 240)
            sCodec.configure(fmt, sSurface, null, 0)
            sCodec.start()
            System.out.println("VideoHost: stream bytes=" + data.size)
        }

        @JvmStatic private fun pump() {
            if (sOutputDone) return
            run {
                var spin: Int = 0
                while (spin < 4) {
                    if (!sInputDone) {
                        val idx: Int = sCodec.dequeueInputBuffer(0)
                        if (idx >= 0) {
                            if (sStream.remaining() < 4) {
                                sCodec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sInputDone = true
                            } else {
                                val size: Int = sStream.getInt()
                                val `in`: ByteBuffer = sCodec.getInputBuffer(idx)
                                val tmp: ByteArray = ByteArray(size)
                                sStream.get(tmp)
                                `in`.put(tmp)
                                sCodec.queueInputBuffer(idx, 0, size, sFrames * 33333L, 0)
                            }
                        }
                    }
                    val idx: Int = sCodec.dequeueOutputBuffer(sInfo, 0)
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f: MediaFormat = sCodec.getOutputFormat()
                        System.out.println(
                            "VideoHost: output " + f.getInteger(MediaFormat.KEY_WIDTH) +
                                "x" + f.getInteger(MediaFormat.KEY_HEIGHT),
                        )
                    } else if (idx >= 0) {
                        if ((sInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sOutputDone = true
                            System.out.println("VideoHost: EOS after " + sFrames + " frames")
                        }
                        val render: Boolean = sInfo.size > 0
                        sCodec.releaseOutputBuffer(idx, render)
                        if (render) {
                            sFrames++
                            break
                        } // one rendered frame per draw tick
                    } else {
                        break
                    }
                    spin++
                }
            }
        }
    }
}
