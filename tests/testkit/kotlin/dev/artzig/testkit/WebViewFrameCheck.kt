package dev.artzig.testkit

import java.io.File
import javax.imageio.ImageIO

/** The fixture's blue heading must be visible, beyond its accessible DOM state. */
object WebViewFrameCheck {
    @JvmStatic fun main(args: Array<String>) {
        val image = checkNotNull(ImageIO.read(File(args.single()))) { "No compositor image" }
        check(image.width >= 320 && image.height >= 240) { "Unexpected compositor size" }
        var ink = 0
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
        check(ink >= 200) { "WebView heading is not visible: $ink matching pixels" }
    }
}
