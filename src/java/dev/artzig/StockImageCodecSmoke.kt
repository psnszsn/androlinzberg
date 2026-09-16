@file:Suppress("DEPRECATION")

package dev.artzig

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.ImageFormat
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfEditor
import android.graphics.pdf.PdfRenderer
import android.graphics.drawable.AnimatedImageDrawable
import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exercise original HWUI codec JNI through public Android APIs and observable pixels. */
internal object StockImageCodecSmoke {
    fun run() {
        byteBufferDecoding()
        gifFrames()
        yuvEncoding()
        pdfDocument()
        pdfEditing()
        pdfRendering()
        println("stock-image-codecs: PASS ByteBuffer, GIF/Movie/ImageDecoder, YUV/JPEG-R, PDF creation/editing/rendering")
    }

    private inline fun <reified T : Throwable> fails(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            check(error is T) { "Expected ${T::class.java.name}, got $error" }
            return
        }
        error("Expected ${T::class.java.name}")
    }

    private fun byteBufferDecoding() {
        val source = Bitmap.createBitmap(7, 5, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.MAGENTA)
        val encoded = ByteArrayOutputStream()
        check(source.compress(Bitmap.CompressFormat.PNG, 100, encoded))
        source.recycle()
        val png = encoded.toByteArray()
        for (direct in listOf(false, true)) {
            val backing = if (direct) ByteBuffer.allocateDirect(png.size + 16)
                          else ByteBuffer.allocate(png.size + 16)
            backing.position(7)
            backing.put(png)
            backing.limit(backing.position())
            backing.position(7)
            // Read-only heap buffers cannot expose an array: this forces the JNI stream adaptor.
            val input = backing.asReadOnlyBuffer()
            val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(input)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            check(input.position() == 7 && input.limit() == 7 + png.size)
            check(decoded.width == 7 && decoded.height == 5)
            check(decoded.getPixel(3, 2) == Color.MAGENTA)
            decoded.recycle()
        }
    }

    private fun gifFrames() {
        // GIF89a, 1x1, red/blue global palette; two independent LZW frames lasting 100ms each.
        val gif = byteArrayOf(71,73,70,56,57,97,1,0,1,0,128.toByte(),0,0,
            255.toByte(),0,0,0,0,255.toByte(),
            33,249.toByte(),4,0,10,0,0,0,44,0,0,0,0,1,0,1,0,0,2,2,68,1,0,
            33,249.toByte(),4,0,10,0,0,0,44,0,0,0,0,1,0,1,0,0,2,2,76,1,0,59)
        val first = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(gif))) { decoder, info, _ ->
            check(info.isAnimated && info.mimeType == "image/gif")
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        check(first.width == 1 && first.height == 1 && first.getPixel(0, 0) == Color.RED)
        first.recycle()
        val animation = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(gif)))
        check(animation is AnimatedImageDrawable)
        val frame = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        animation.setBounds(0, 0, 1, 1)
        animation.draw(Canvas(frame))
        check(frame.getPixel(0, 0) == Color.RED)
        frame.recycle()
        val padded = byteArrayOf(0, 0, 0) + gif + byteArrayOf(0)
        val fromArray = checkNotNull(Movie.decodeByteArray(padded, 3, gif.size))
        val fromStream = checkNotNull(Movie.decodeStream(ByteArrayInputStream(gif)))
        for (movie in listOf(fromArray, fromStream)) {
            check(movie.width() == 1 && movie.height() == 1 && movie.duration() == 200)
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            movie.setTime(0)
            movie.draw(canvas, 0f, 0f)
            check(bitmap.getPixel(0, 0) == Color.RED)
            movie.setTime(150)
            movie.draw(canvas, 0f, 0f)
            check(bitmap.getPixel(0, 0) == Color.BLUE)
            movie.setTime(0)
            movie.draw(canvas, 0f, 0f)
            check(bitmap.getPixel(0, 0) == Color.RED)
            bitmap.recycle()
        }
        check(Movie.decodeByteArray(byteArrayOf(1, 2, 3), 0, 3) == null)
        fails<ArrayIndexOutOfBoundsException> { Movie.decodeByteArray(gif, -1, gif.size) }
    }

    private fun yuvEncoding() {
        val width = 32
        val height = 24
        val stride = 40
        val nv21 = ByteArray(stride * height * 3 / 2) { 128.toByte() }
        for (y in 0 until height) for (x in 0 until width) nv21[y * stride + x] = 90
        val packed = ByteArray(stride * 2 * height)
        for (y in 0 until height) for (x in 0 until width) {
            packed[y * stride * 2 + x * 2] = 90
            packed[y * stride * 2 + x * 2 + 1] = 128.toByte()
        }
        for (image in listOf(
            YuvImage(nv21, ImageFormat.NV21, width, height, intArrayOf(stride, stride)),
            YuvImage(packed, ImageFormat.YUY2, width, height, intArrayOf(stride * 2)),
        )) {
            val output = ByteArrayOutputStream()
            val crop = Rect(4, 2, 28, 22)
            check(image.compressToJpeg(crop, 95, output))
            val bytes = output.toByteArray()
            val decoded = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            check(decoded.width == 24 && decoded.height == 20)
            val pixel = decoded.getPixel(12, 10)
            check(kotlin.math.abs(Color.red(pixel) - 90) < 8)
            check(kotlin.math.abs(Color.red(pixel) - Color.blue(pixel)) < 3)
            decoded.recycle()
            fails<IllegalArgumentException> { image.compressToJpeg(crop, 101, output) }
        }
        // Constant neutral P010 HDR plus planar SDR produces a real JPEG/R gain map.
        val size = 128
        val hdr = ByteBuffer.allocate(size * size * 3).order(ByteOrder.LITTLE_ENDIAN)
        repeat(size * size) { hdr.putShort((400 shl 6).toShort()) }
        repeat(size * size / 2) { hdr.putShort((512 shl 6).toShort()) }
        val sdr = ByteArray(size * size * 3 / 2) { if (it < size * size) 90 else 128.toByte() }
        val hdrImage = YuvImage(hdr.array(), ImageFormat.YCBCR_P010, size, size, null,
            ColorSpace.get(ColorSpace.Named.BT2020_HLG))
        val sdrImage = YuvImage(sdr, ImageFormat.YUV_420_888, size, size, null,
            ColorSpace.get(ColorSpace.Named.SRGB))
        val encoded = ByteArrayOutputStream()
        check(hdrImage.compressToJpegR(sdrImage, 90, encoded))
        val bytes = encoded.toByteArray()
        val decoded = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        check(decoded.width == size && decoded.height == size && decoded.hasGainmap())
        check(checkNotNull(decoded.gainmap).gainmapContents.width > 0)
        decoded.recycle()
    }

    private fun pdfDocument() {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(160, 120, 1).create()
        val page = document.startPage(pageInfo)
        page.canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.RED; textSize = 18f }
        page.canvas.drawRect(5f, 5f, 50f, 50f, paint)
        page.canvas.drawText("AOSP PDF", 8f, 90f, paint)
        fails<IllegalStateException> { document.writeTo(ByteArrayOutputStream()) }
        fails<IllegalStateException> { document.startPage(pageInfo) }
        document.finishPage(page)
        fails<IllegalStateException> { document.finishPage(page) }
        val second = document.startPage(PdfDocument.PageInfo.Builder(200, 100, 2).create())
        second.canvas.drawColor(Color.BLUE)
        document.finishPage(second)
        check(document.pages.size == 2)
        val output = ByteArrayOutputStream()
        document.writeTo(output)
        val bytes = output.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)
        check(text.startsWith("%PDF-") && text.trimEnd().endsWith("%%EOF"))
        check(text.contains("/Count 2") && text.contains("/Type /Font"))
        val xref = text.substringAfterLast("startxref").trim().substringBefore('\n').toInt()
        check(text.substring(xref).startsWith("xref"))
        document.close()
        fails<IllegalStateException> { document.startPage(pageInfo) }
    }

    private fun pdfEditing() {
        val input = File.createTempFile("stock-pdf-input-", ".pdf")
        val output = File.createTempFile("stock-pdf-edited-", ".pdf")
        try {
            val document = PdfDocument()
            for ((index, size) in listOf(160 to 120, 200 to 100).withIndex()) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(size.first, size.second, index).create())
                page.canvas.drawColor(if (index == 0) Color.RED else Color.BLUE)
                document.finishPage(page)
            }
            input.outputStream().use { document.writeTo(it) }
            document.close()
            val descriptor = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
            val editor = PdfEditor(descriptor)
            try {
                check(editor.pageCount == 2)
                val size = Point()
                editor.getPageSize(1, size)
                check(size == Point(200, 100))
                fails<IllegalArgumentException> { editor.removePage(2) }
                fails<IllegalArgumentException> { editor.getPageSize(-1, size) }
                val perspective = Matrix().apply {
                    setValues(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0.5f, 0f, 1f))
                }
                fails<IllegalStateException> { editor.setTransformAndClip(0, perspective, null) }
                editor.removePage(0)
                check(editor.pageCount == 1)
                editor.setPageMediaBox(0, Rect(0, 0, 180, 90))
                editor.setPageCropBox(0, Rect(10, 10, 170, 80))
                editor.setTransformAndClip(0, Matrix().apply { setTranslate(2f, 3f) }, Rect(0, 0, 180, 90))
                val destination = ParcelFileDescriptor.open(output,
                    ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
                editor.write(destination)
                check(!destination.fileDescriptor.valid())
            } finally {
                editor.close()
            }
            check(!descriptor.fileDescriptor.valid())
            fails<IllegalStateException> { editor.pageCount }
            fails<IllegalStateException> { editor.close() }
            val reopened = PdfEditor(ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY))
            try {
                check(reopened.pageCount == 1)
                val box = Rect()
                check(reopened.getPageMediaBox(0, box) && box == Rect(0, 0, 180, 90))
                check(reopened.getPageCropBox(0, box) && box == Rect(10, 10, 170, 80))
            } finally {
                reopened.close()
            }
            input.writeText("not a PDF")
            ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { invalid ->
                fails<IOException> { PdfEditor(invalid) }
            }
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                fails<IllegalArgumentException> { PdfEditor(pipe[0]) }
            } finally {
                pipe.forEach { it.close() }
            }
        } finally {
            input.delete()
            output.delete()
        }
    }
    private fun pdfRendering() {
        val input = File.createTempFile("stock-pdf-render-", ".pdf")
        try {
            val document = PdfDocument()
            for (index in 0..1) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(160, 120, index).create())
                page.canvas.drawColor(if (index == 0) Color.RED else Color.BLUE)
                page.canvas.drawText("Stock PDF text", 8f, 25f, Paint().apply { color = Color.BLACK; textSize = 16f })
                document.finishPage(page)
            }
            input.outputStream().use { document.writeTo(it) }
            document.close()
            val descriptor = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(descriptor)
            try {
                check(renderer.pageCount == 2)
                fails<IllegalArgumentException> { renderer.openPage(2) }
                // PdfEditor destroys its own PDFium library on close. Rendering
                // must continue in libpdfclient's independently owned instance.
                val editor = PdfEditor(ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY))
                try {
                    check(editor.pageCount == 2)
                } finally {
                    editor.close()
                }
                for (index in 0..1) {
                    val page = renderer.openPage(index)
                    check(page.width == 160 && page.height == 120 && page.index == index)
                    val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
                    try {
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        check(bitmap.getPixel(80, 80) == if (index == 0) Color.RED else Color.BLUE)
                        check(page.textContents.joinToString(" ") { it.text }.contains("Stock PDF text"))
                        check(page.searchText("PDF").isNotEmpty())
                    } finally {
                        bitmap.recycle()
                        page.close()
                    }
                    // AOSP retains cached dimensions after close; operations
                    // requiring the native page reject the closed owner.
                    check(page.width == 160 && page.height == 120)
                    fails<IllegalStateException> { page.textContents }
                }
            } finally {
                renderer.close()
            }
            check(!descriptor.fileDescriptor.valid())
            fails<IllegalStateException> { renderer.pageCount }
            input.writeText("not a PDF")
            ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
                fails<IOException> { PdfRenderer(it) }
            }
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                fails<IllegalArgumentException> { PdfRenderer(pipe[0]) }
            } finally {
                pipe.forEach { it.close() }
            }
        } finally {
            input.delete()
        }
    }

}
