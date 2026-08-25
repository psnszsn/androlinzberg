/* artzig: font pipeline probe. */
package dev.artzig;
import android.graphics.Typeface;
import android.graphics.fonts.SystemFonts;
import android.text.FontConfig;
public final class FontProbe {
    public static void main(String[] args) throws Exception {
        FontConfig fc = SystemFonts.getSystemFontConfig(new java.util.HashMap<>(), 0, 0);
        System.out.println("families=" + fc.getFontFamilies().size());
        System.out.println("aliases=" + fc.getAliases().size());
        java.util.Map<String, android.graphics.fonts.FontFamily[]> map = SystemFonts.buildSystemFallback(fc);
        System.out.println("fallback map=" + map.size() + " keys=" + map.keySet());
        System.out.println("DEFAULT=" + Typeface.DEFAULT);
    }
}
