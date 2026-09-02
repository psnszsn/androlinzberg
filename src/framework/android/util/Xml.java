/* artzig shim: android.util.Xml — KXml-backed (no ART binary-xml chain). */
package android.util;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;
public class Xml {
    private Xml() {}
    public static final String FEATURE_RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed";
    public static XmlPullParser newPullParser() {
        try {
            com.android.org.kxml2.io.KXmlParser parser = new com.android.org.kxml2.io.KXmlParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, true);
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            return parser;
        } catch (XmlPullParserException e) {
            throw new AssertionError(e);
        }
    }
    public static XmlSerializer newSerializer() {
        return new com.android.org.kxml2.io.KXmlSerializer();
    }
    /** AOSP Xml.newFastPullParser: the typed wrapper over the pull parser. */
    public static TypedXmlPullParser newFastPullParser() {
        return com.android.internal.util.XmlUtils.makeTyped(newPullParser());
    }
    public static TypedXmlPullParser newBinaryPullParser() { throw ArtzigTestDiagnostics.unsupported(
            "android.util.Xml", "newBinaryPullParser", null); }
    /** AOSP Xml.resolvePullParser without the binary-XML branch (no ART binary chain here). */
    public static TypedXmlPullParser resolvePullParser(InputStream in) throws IOException {
        final TypedXmlPullParser xml = newFastPullParser();
        try {
            xml.setInput(in, java.nio.charset.StandardCharsets.UTF_8.name());
        } catch (XmlPullParserException e) {
            throw new IOException(e);
        }
        return xml;
    }
    /** AOSP Xml.newFastSerializer: the typed wrapper over FastXmlSerializer. */
    public static TypedXmlSerializer newFastSerializer() {
        return com.android.internal.util.XmlUtils.makeTyped(new com.android.internal.util.FastXmlSerializer());
    }
    public static TypedXmlSerializer newBinarySerializer() { throw ArtzigTestDiagnostics.unsupported(
            "android.util.Xml", "newBinarySerializer", null); }
    /** AOSP Xml.resolveSerializer without the binary-XML branch. */
    public static TypedXmlSerializer resolveSerializer(OutputStream out) throws IOException {
        final TypedXmlSerializer xml = newFastSerializer();
        xml.setOutput(out, java.nio.charset.StandardCharsets.UTF_8.name());
        return xml;
    }
    public static void parse(String xml, org.xml.sax.ContentHandler contentHandler) throws SAXException {
        try {
            javax.xml.parsers.SAXParserFactory.newInstance().newSAXParser()
                    .getXMLReader().parse(new InputSource(new java.io.StringReader(xml)));
        } catch (Exception e) {
            throw new SAXException(e);
        }
    }
    public static void parse(Reader in, org.xml.sax.ContentHandler contentHandler) throws IOException, SAXException {}
    public static void parse(InputStream in, Encoding encoding, org.xml.sax.ContentHandler contentHandler)
            throws IOException, SAXException {}
    public enum Encoding {
        US_ASCII("US-ASCII"), UTF_8("UTF-8"), UTF_16("UTF-16"), ISO_8859_1("ISO-8859-1");
        final String expatName;
        Encoding(String expatName) { this.expatName = expatName; }
    }
    public static Encoding findEncodingByName(String encodingName) {
        if (encodingName == null) return Encoding.UTF_8;
        for (Encoding encoding : Encoding.values())
            if (encoding.expatName.equalsIgnoreCase(encodingName)) return encoding;
        return Encoding.UTF_8;
    }
    public static String asAttributeSet$() { return null; }
    public static AttributeSet asAttributeSet(XmlPullParser parser) {
        return (parser instanceof AttributeSet) ? (AttributeSet) parser : new XmlPullAttributes(parser);
    }
    static class XmlPullAttributes implements AttributeSet {
        private final XmlPullParser mParser;
        XmlPullAttributes(XmlPullParser parser) { mParser = parser; }
        public int getAttributeCount() { return mParser.getAttributeCount(); }
        public String getAttributeNamespace(int index) { return mParser.getAttributeNamespace(index); }
        public String getAttributeName(int index) { return mParser.getAttributeName(index); }
        public String getAttributeValue(int index) { return mParser.getAttributeValue(index); }
        public String getAttributeValue(String namespace, String name) { return mParser.getAttributeValue(namespace, name); }
        public String getPositionDescription() { return mParser.getPositionDescription(); }
        public int getAttributeNameResource(int index) { return 0; }
        public int getAttributeListValue(String namespace, String attribute, String[] options, int defaultValue) {
            return XmlUtilsListValue(getAttributeValue(namespace, attribute), options, defaultValue);
        }
        public boolean getAttributeBooleanValue(String namespace, String attribute, boolean defaultValue) {
            String s = getAttributeValue(namespace, attribute);
            return s == null ? defaultValue : Boolean.parseBoolean(s);
        }
        public int getAttributeResourceValue(String namespace, String attribute, int defaultValue) {
            return getAttributeIntValue(namespace, attribute, defaultValue);
        }
        public int getAttributeIntValue(String namespace, String attribute, int defaultValue) {
            return parseInt(getAttributeValue(namespace, attribute), defaultValue);
        }
        public int getAttributeUnsignedIntValue(String namespace, String attribute, int defaultValue) {
            return getAttributeIntValue(namespace, attribute, defaultValue);
        }
        public float getAttributeFloatValue(String namespace, String attribute, float defaultValue) {
            String s = getAttributeValue(namespace, attribute);
            try { return s != null ? Float.parseFloat(s) : defaultValue; }
            catch (NumberFormatException e) { return defaultValue; }
        }
        public int getAttributeListValue(int index, String[] options, int defaultValue) {
            return XmlUtilsListValue(getAttributeValue(index), options, defaultValue);
        }
        public boolean getAttributeBooleanValue(int index, boolean defaultValue) {
            String s = getAttributeValue(index);
            return s == null ? defaultValue : Boolean.parseBoolean(s);
        }
        public int getAttributeResourceValue(int index, int defaultValue) { return getAttributeIntValue(index, defaultValue); }
        public int getAttributeIntValue(int index, int defaultValue) { return parseInt(getAttributeValue(index), defaultValue); }
        public int getAttributeUnsignedIntValue(int index, int defaultValue) { return getAttributeIntValue(index, defaultValue); }
        public float getAttributeFloatValue(int index, float defaultValue) {
            String s = getAttributeValue(index);
            try { return s != null ? Float.parseFloat(s) : defaultValue; }
            catch (NumberFormatException e) { return defaultValue; }
        }
        public String getIdAttribute() { return getAttributeValue(null, "id"); }
        public String getClassAttribute() { return getAttributeValue(null, "class"); }
        public int getIdAttributeResourceValue(int defaultValue) { return defaultValue; }
        public int getStyleAttribute() { return 0; }
        private static int XmlUtilsListValue(String value, String[] options, int defaultValue) {
            if (value != null) for (int i = 0; i < options.length; i++) if (value.equals(options[i])) return i;
            return defaultValue;
        }
        private static int parseInt(String s, int defaultValue) {
            if (s == null) return defaultValue;
            try {
                if (s.startsWith("0x") || s.startsWith("0X")) return Integer.parseInt(s.substring(2), 16);
                return Integer.parseInt(s);
            } catch (NumberFormatException e) { return defaultValue; }
        }
    }
}
