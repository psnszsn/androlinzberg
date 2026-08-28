// artzig: font pipeline probe.
package dev.artzig
import android.graphics.Typeface
import android.graphics.fonts.SystemFonts
import android.text.FontConfig

public final class FontProbe {
    companion object {
        @Throws(Exception::class)
        @JvmStatic
        public fun main(args: Array<String>) {
            val fc: FontConfig = SystemFonts.getSystemFontConfig(java.util.HashMap(), 0, 0)
            System.out.println("families=" + fc.getFontFamilies().size)
            System.out.println("aliases=" + fc.getAliases().size)
            val map: MutableMap<String, Array<android.graphics.fonts.FontFamily>> = SystemFonts.buildSystemFallback(fc)
            System.out.println("fallback map=" + map.size + " keys=" + map.keys)
            System.out.println("DEFAULT=" + Typeface.DEFAULT)
        }
    }
}
