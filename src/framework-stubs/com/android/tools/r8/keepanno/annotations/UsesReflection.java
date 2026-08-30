/* artzig stub: r8 keep annotation used by AOSP services; no effect on this host. */
package com.android.tools.r8.keepanno.annotations;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
@Retention(RetentionPolicy.CLASS)
public @interface UsesReflection {
    KeepTarget[] value();
}
