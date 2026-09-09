package dev.artzig.testkit

import java.io.File
import javax.imageio.ImageIO

/** Verify rendered text/video and preservation of the displayed frame across pause. */
object WebViewFrameCheck {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 1..2) { "expected image and optional playing-frame baseline" }
        val image = checkNotNull(ImageIO.read(File(args[0]))) { "No compositor image" }
        val playing = args.getOrNull(1)?.let { checkNotNull(ImageIO.read(File(it))) }
        if (playing != null) check(image.width == playing.width && image.height == playing.height)
        check(image.width >= 320 && image.height >= 240) { "Unexpected compositor size" }
        var ink = 0
        var video = 0
        // CSS body color #16324f. Tolerate color conversion and antialiasing;
        // black/white empty buffers cannot satisfy this assertion.
        for (y in 0 until image.height / 2) {
            for (x in 0 until image.width) {
                val pixel = image.getRGB(x, y)
                val r = pixel ushr 16 and 255
                val g = pixel ushr 8 and 255
                val b = pixel and 255
                if (r in 14..30 && g in 42..58 && b in 71..87) ink++
            }
        }
        var compared = 0
        var changed = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val pixel = image.getRGB(x, y)
            if (isVideoGreen(pixel)) video++
            if (playing != null) {
                val before = playing.getRGB(x, y)
                if (isVideoGreen(before)) {
                    compared++
                    if (listOf(0, 8, 16).any { shift ->
                        kotlin.math.abs((pixel ushr shift and 255) - (before ushr shift and 255)) > 2
                    }) changed++
                }
            }
        }
        if (playing != null) {
            check(compared >= 10_000) { "No visible video in playing-frame baseline" }
            check(changed == 0) { "Paused video changed $changed of $compared displayed pixels" }
        }
        check(video >= 10_000) { "Chromium video frame is not visible: $video matching pixels" }
        check(ink >= 200) { "WebView heading is not visible: $ink matching pixels" }
    }

    // The fixture is green; red producer frames, black video fallbacks, the white
    // page and blue text cannot satisfy this. Compare the actual rendered pixels
    // across pause so color conversion differences cannot hide a changed frame.
    private fun isVideoGreen(pixel: Int): Boolean {
        val r = pixel ushr 16 and 255
        val g = pixel ushr 8 and 255
        val b = pixel and 255
        return r < 100 && g > 140 && b in 70..150 && g > r + 60 && g > b + 30
    }
}
