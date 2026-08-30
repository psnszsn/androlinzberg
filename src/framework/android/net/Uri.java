/* artzig shim: android.net.Uri — thin wrapper over java.net.URI semantics. */
package android.net;
import android.os.Parcel;
import android.os.Parcelable;
public abstract class Uri implements Parcelable, Comparable<Uri> {
    public static final Uri EMPTY = Uri.parse("");
    /**
     * Hosted adaptation of AOSP Uri.isRelative()/isAbsolute(). The complete AOSP Uri depends on
     * internal encoded-part and Parcel machinery outside the host's source closure; this shim
     * preserves the observable scheme-presence contract used by its StringUri implementation.
     */
    public abstract boolean isRelative();
    public boolean isAbsolute() { return !isRelative(); }
    public abstract String getScheme();
    public abstract String getPath();
    public abstract String getAuthority();
    public abstract String getLastPathSegment();
    public abstract String toString();
    public Builder buildUpon() { return new Builder(this); }
    @Override public int compareTo(Uri other) { return toString().compareTo(other.toString()); }
    public java.util.List<String> getPathSegments() {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        String rawPath = null;
        try { rawPath = new java.net.URI(toString()).getRawPath(); } catch (Exception ignored) {}
        if (rawPath == null || rawPath.isEmpty()) return result;
        for (String segment : rawPath.split("/")) {
            if (!segment.isEmpty()) result.add(decode(segment));
        }
        return result;
    }
    public String getQueryParameter(String key) {
        String rawQuery = null;
        try { rawQuery = new java.net.URI(toString()).getRawQuery(); } catch (Exception ignored) {}
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            String rawKey = equals >= 0 ? pair.substring(0, equals) : pair;
            if (key.equals(decode(rawKey))) {
                return decode(equals >= 0 ? pair.substring(equals + 1) : "");
            }
        }
        return null;
    }
    public String getHost() { java.net.URI u; try { u = new java.net.URI(toString()); } catch (Exception e) { return null; } return u.getHost(); }
    public void checkContentUriWithoutPermission(String location, int flags) {}
    public void checkFileUriExposed(String location) {}
    public boolean isPathPrefixMatch(Uri prefix) { return false; }
    public static Uri parse(String uriString) { return new StringUri(uriString); }
    public static Uri fromFile(java.io.File file) { return new StringUri("file://" + file.getAbsolutePath()); }
    public static Uri withAppendedPath(Uri baseUri, String pathSegment) {
        if (baseUri == null) throw new NullPointerException("baseUri");
        String base = baseUri.toString();
        return new StringUri(base + (base.endsWith("/") ? "" : "/") + encode(pathSegment));
    }
    public static String encode(String value) {
        return encode(value, null);
    }
    /** Follows AOSP Uri.encode(String, String), including its URI-unreserved set. */
    public static String encode(String value, String allow) {
        if (value == null) return null;
        StringBuilder encoded = null;
        int current = 0;
        while (current < value.length()) {
            int nextToEncode = current;
            while (nextToEncode < value.length()
                    && isAllowed(value.charAt(nextToEncode), allow)) {
                nextToEncode++;
            }
            if (nextToEncode == value.length()) {
                if (current == 0) return value;
                encoded.append(value, current, value.length());
                return encoded.toString();
            }
            if (encoded == null) encoded = new StringBuilder();
            if (nextToEncode > current) encoded.append(value, current, nextToEncode);
            current = nextToEncode;
            int nextAllowed = current + 1;
            while (nextAllowed < value.length()
                    && !isAllowed(value.charAt(nextAllowed), allow)) {
                nextAllowed++;
            }
            byte[] bytes = value.substring(current, nextAllowed)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (byte item : bytes) {
                encoded.append('%');
                encoded.append(HEX_DIGITS[(item & 0xf0) >> 4]);
                encoded.append(HEX_DIGITS[item & 0x0f]);
            }
            current = nextAllowed;
        }
        return encoded == null ? value : encoded.toString();
    }
    public static String decode(String value) {
        if (value == null) return null;
        try { return java.net.URLDecoder.decode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { return value; }
    }
    public static void writeToParcel(Parcel out, Uri uri) {
        out.writeString(uri != null ? uri.toString() : null);
    }
    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();
    private static boolean isAllowed(char value, String allow) {
        return (value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9')
                || "_-!.~'()*".indexOf(value) >= 0
                || (allow != null && allow.indexOf(value) >= 0);
    }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) { dest.writeString(toString()); }
    public static final Creator<Uri> CREATOR = new Creator<Uri>() {
        public Uri createFromParcel(Parcel in) { return parse(in.readString()); }
        public Uri[] newArray(int size) { return new Uri[size]; }
    };
    public static final class Builder {
        private String mScheme;
        private String mAuthority;
        private String mEncodedPath = "";
        private String mEncodedQuery;
        private String mEncodedFragment;
        public Builder() {}
        Builder(Uri base) {
            try {
                java.net.URI parsed = new java.net.URI(base.toString());
                mScheme = parsed.getScheme();
                mAuthority = parsed.getRawAuthority();
                mEncodedPath = parsed.getRawPath();
                mEncodedQuery = parsed.getRawQuery();
                mEncodedFragment = parsed.getRawFragment();
                if (mEncodedPath == null) mEncodedPath = "";
            } catch (Exception ignored) {
                mEncodedPath = base.toString();
            }
        }
        public Builder scheme(String scheme) { mScheme = scheme; return this; }
        public Builder authority(String authority) { mAuthority = authority; return this; }
        public Builder encodedAuthority(String authority) { mAuthority = authority; return this; }
        public Builder path(String path) {
            mEncodedPath = path == null ? null : encode(path).replace("%2F", "/");
            return this;
        }
        public Builder encodedPath(String path) { mEncodedPath = path; return this; }
        public Builder appendPath(String segment) { return appendEncodedPath(encode(segment)); }
        public Builder appendEncodedPath(String segment) {
            // Android's PathPart concatenation turns a null segment into the literal "null".
            // Some apps rely on this when optional URI metadata is absent.
            if (segment == null) segment = "null";
            if (mEncodedPath == null || mEncodedPath.isEmpty()) mEncodedPath = "/";
            else if (!mEncodedPath.endsWith("/")) mEncodedPath += "/";
            while (segment.startsWith("/")) segment = segment.substring(1);
            mEncodedPath += segment;
            return this;
        }
        public Builder query(String query) {
            mEncodedQuery = query == null ? null : encode(query);
            return this;
        }
        public Builder encodedQuery(String query) { mEncodedQuery = query; return this; }
        public Builder appendQueryParameter(String key, String value) {
            String pair = encode(key) + "=" + encode(value);
            mEncodedQuery = mEncodedQuery == null || mEncodedQuery.isEmpty()
                    ? pair : mEncodedQuery + "&" + pair;
            return this;
        }
        public Builder clearQuery() { mEncodedQuery = null; return this; }
        public Builder fragment(String fragment) {
            mEncodedFragment = fragment == null ? null : encode(fragment);
            return this;
        }
        public Builder encodedFragment(String fragment) { mEncodedFragment = fragment; return this; }
        public Uri build() {
            StringBuilder out = new StringBuilder();
            if (mScheme != null) out.append(mScheme).append(':');
            if (mAuthority != null) out.append("//").append(mAuthority);
            if (mEncodedPath != null) out.append(mEncodedPath);
            if (mEncodedQuery != null) out.append('?').append(mEncodedQuery);
            if (mEncodedFragment != null) out.append('#').append(mEncodedFragment);
            return Uri.parse(out.toString());
        }
    }
    private static final class StringUri extends Uri {
        private final String mStr;
        StringUri(String s) { mStr = s; }
        private java.net.URI uri() { try { return new java.net.URI(mStr); } catch (Exception e) { return null; } }
        /** Follows AOSP Uri.StringUri.isRelative(), which tests for a scheme separator. */
        @Override public boolean isRelative() { return mStr.indexOf(':') == -1; }
        @Override public String getScheme() { java.net.URI u = uri(); return u != null ? u.getScheme() : null; }
        @Override public String getPath() { java.net.URI u = uri(); return u != null ? u.getPath() : null; }
        @Override public String getAuthority() { java.net.URI u = uri(); return u != null ? u.getAuthority() : null; }
        @Override public String getLastPathSegment() {
            String p = getPath();
            if (p == null) return null;
            int i = p.lastIndexOf('/');
            return i >= 0 ? p.substring(i + 1) : p;
        }
        @Override public String toString() { return mStr; }
        @Override public boolean equals(Object o) { return o instanceof StringUri && mStr.equals(((StringUri) o).mStr); }
        @Override public int hashCode() { return mStr.hashCode(); }
    }
}
