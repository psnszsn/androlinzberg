package dev.artzig.window

import com.android.internal.protolog.common.IProtoLogGroup

import com.android.internal.protolog.ProtoLog
import com.android.internal.protolog.WmProtoLogGroups

/**
 * ViewRootImpl.initializeProtoLogInProcess and SystemServer.startBootstrapServices
 * register their groups before logging. The view group catalog is package-private,
 * so the host bootstrap enters here while those process entry points are adapted.
 */
object HostedWindowLogging {
    @JvmStatic fun initialize() {
        ProtoLog.REQUIRE_PROTOLOGTOOL = false
        // LogcatOnlyProtoLogImpl also prints every proto-enabled group. There
        // is no Perfetto recording in this host, so retain AOSP's logcat group
        // policy without turning all WM trace groups into ordinary log output.
        // Bootstrap needs the package-private catalog before a ViewRoot exists.
        // Keep reflection at this host boundary, without adding Android classes.
        @Suppress("UNCHECKED_CAST")
        val viewGroups = Class.forName("android.view.ViewProtoLogGroups")
            .getDeclaredField("ALL_GROUPS").apply { isAccessible = true }
            .get(null) as Array<out IProtoLogGroup>
        viewGroups.forEach { it.setLogToProto(false) }
        WmProtoLogGroups.values().forEach { it.setLogToProto(false) }
        ProtoLog.init(*viewGroups, *WmProtoLogGroups.values())
    }
}
