/* artzig stub: protolog — log to nothing (perfetto unavailable on host). */
package com.android.internal.protolog;
import com.android.internal.protolog.common.IProtoLogGroup;
public class ProtoLog {
    public static void d(IProtoLogGroup group, String messageString, Object... args) {}
    public static void v(IProtoLogGroup group, String messageString, Object... args) {}
    public static void i(IProtoLogGroup group, String messageString, Object... args) {}
    public static void w(IProtoLogGroup group, String messageString, Object... args) {}
    public static void e(IProtoLogGroup group, String messageString, Object... args) {}
    public static void wtf(IProtoLogGroup group, String messageString, Object... args) {}
    public static boolean isEnabled(IProtoLogGroup group, Object level) { return false; }
    public static void init(IProtoLogGroup[] groups) {}
}
