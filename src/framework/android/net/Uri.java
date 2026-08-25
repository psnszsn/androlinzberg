/* artzig shim: android.net.Uri — thin wrapper over java.net.URI semantics. */
package android.net;
import android.os.Parcel;
import android.os.Parcelable;
public abstract class Uri implements Parcelable, Comparable<Uri> {
    public static final Uri EMPTY = Uri.parse("");
    public abstract String getScheme();
    public abstract String getPath();
    public abstract String getAuthority();
    public abstract String getLastPathSegment();
    public abstract String toString();
    @Override public int compareTo(Uri other) { return toString().compareTo(other.toString()); }
    public String getQueryParameter(String key) { return null; }
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
        if (value == null) return null;
        try { return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { return value; }
    }
    public static String decode(String value) {
        if (value == null) return null;
        try { return java.net.URLDecoder.decode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { return value; }
    }
    public static void writeToParcel(Parcel out, Uri uri) {
        out.writeString(uri != null ? uri.toString() : null);
    }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) { dest.writeString(toString()); }
    public static final Creator<Uri> CREATOR = new Creator<Uri>() {
        public Uri createFromParcel(Parcel in) { return parse(in.readString()); }
        public Uri[] newArray(int size) { return new Uri[size]; }
    };
    private static final class StringUri extends Uri {
        private final String mStr;
        StringUri(String s) { mStr = s; }
        private java.net.URI uri() { try { return new java.net.URI(mStr); } catch (Exception e) { return null; } }
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
