/* artzig stub: android.text.Html — escape only (fromHtml needs tagsoup; provide plain-text fallback). */
package android.text;
public class Html {
    public static final int TO_HTML_PARAGRAPH_LINES_CONSECUTIVE = 0x00000000;
    public static final int TO_HTML_PARAGRAPH_LINES_INDIVIDUAL = 0x00000001;

    public static final int FROM_HTML_MODE_LEGACY = 0, FROM_HTML_MODE_COMPACT = 63;
    private Html() {}
    public static Spanned fromHtml(String source) { return fromHtml(source, FROM_HTML_MODE_LEGACY); }
    public static Spanned fromHtml(String source, int flags) {
        return new SpannedString(source == null ? "" : source.replaceAll("<[^>]*>", ""));
    }
    public static String escapeHtml(CharSequence text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<') out.append("&lt;");
            else if (c == '>') out.append("&gt;");
            else if (c == '&') out.append("&amp;");
            else out.append(c);
        }
        return out.toString();
    }
    public static String toHtml(Spanned text) { return escapeHtml(text.toString()); }
    public static String toHtml(Spanned text, int option) { return toHtml(text); }
}
