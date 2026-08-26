package android.content;

import android.net.Uri;
import java.util.ArrayList;
import java.util.List;

/** Small compatible implementation of Android's authority/path URI matcher. */
public class UriMatcher {
    public static final int NO_MATCH = -1;

    private static final class Rule {
        final String authority;
        final String[] path;
        final int code;
        Rule(String authority, String path, int code) {
            this.authority = authority;
            this.path = path == null || path.isEmpty() ? new String[0] : path.split("/");
            this.code = code;
        }
    }

    private final int rootCode;
    private final List<Rule> rules = new ArrayList<>();

    public UriMatcher(int code) { rootCode = code; }

    public void addURI(String authority, String path, int code) {
        if (code < 0) throw new IllegalArgumentException("code must be non-negative");
        rules.add(new Rule(authority, path, code));
    }

    public int match(Uri uri) {
        if (uri == null) return NO_MATCH;
        List<String> pathSegments = uri.getPathSegments();
        String[] segments = pathSegments.toArray(new String[0]);
        for (Rule rule : rules) {
            if (rule.authority != null && !rule.authority.equals(uri.getAuthority())) continue;
            if (rule.path.length != segments.length) continue;
            boolean matches = true;
            for (int i = 0; i < segments.length; i++) {
                String expected = rule.path[i];
                if ("*".equals(expected)) continue;
                if ("#".equals(expected)) {
                    if (segments[i].isEmpty()) { matches = false; break; }
                    for (int j = 0; j < segments[i].length(); j++) {
                        if (!Character.isDigit(segments[i].charAt(j))) { matches = false; break; }
                    }
                    if (!matches) break;
                } else if (!expected.equals(segments[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) return rule.code;
        }
        return segments.length == 0 ? rootCode : NO_MATCH;
    }
}
