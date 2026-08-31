/* artzig stub: /system/etc config XMLs do not exist in a hosted session; the
 * hidden-api allowlist is empty. */
package com.android.server;

import java.util.Collections;
import java.util.Set;

public final class SystemConfig {
    private static final SystemConfig INSTANCE = new SystemConfig();
    private SystemConfig() {}
    public static SystemConfig getInstance() { return INSTANCE; }
    public Set<String> getHiddenApiWhitelistedApps() { return Collections.emptySet(); }
}
