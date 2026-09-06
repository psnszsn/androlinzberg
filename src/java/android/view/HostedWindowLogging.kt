package android.view

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
        ProtoLog.init(*ViewProtoLogGroups.ALL_GROUPS, *WmProtoLogGroups.values())
    }
}
