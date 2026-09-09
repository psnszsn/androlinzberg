package dev.artzig.systemtest

/** The caller API fixture must enable the same aconfig flag in stock ATMS and
 * its app peers. Production SystemServer keeps the generated flag defaults. */
internal object SystemTestServer {
    @JvmStatic fun main(args: Array<String>) {
        ActivityUriGrantSystemSmoke.setCallerApis(true)
        dev.artzig.system.SystemServer.main(args)
    }
}

/** Acts as the external launcher for the independently signed identity test.
 * Subsequent launches originate from its visible activity using ordinary policy. */
internal object IdentityLaunchSystemSmoke {
    @JvmStatic fun main(args: Array<String>) {
        dev.artzig.BinderSession.setIdentity("android", true)
        checkNotNull(android.os.ServiceManager.waitForService(
            "dev.artzig.identity.a.caller-ready"))
        val intent = android.content.Intent().setComponent(android.content.ComponentName(
            "dev.artzig.identity.a", BUriGrantActivity::class.java.name))
            .putExtra("id", 100).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        check(android.app.ActivityTaskManager.getService().startActivity(null, "android", null,
            intent, null, null, null, 0, 0, null, null) >= 0)
    }
}
