package dev.artzig.systemtest

import android.app.HostedServiceClient
import android.content.pm.PackageManager
import android.os.Looper
import dev.artzig.HostContexts
import kotlin.concurrent.thread

/** Two independently attached app UIDs signed without the platform test certificate. */
internal object CallerIdentitySystemSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        val pkg = checkNotNull(System.getenv("AZK_BINDER_PACKAGE"))
        val client = pkg == "dev.artzig.identity.a"
        HostContexts.systemContext(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")), 320, 240, 120, 0)
        val fixture = checkNotNull(System.getenv(if (client) "AZK_FIXTURE_IDENTITY_A" else "AZK_FIXTURE_IDENTITY_B"))
        check(checkNotNull(HostedServiceClient.installerOrNull()).installPackage(fixture, null) == pkg)
        val context = HostedServiceClient.attachApplication(pkg)
        check(!context.applicationInfo.isSignedWithPlatformKey)
        check(context.packageManager.checkSignatures(pkg, "android") == PackageManager.SIGNATURE_NO_MATCH)
        ActivityUriGrantSystemSmoke.publish(context)
        if (client) thread(name = "caller-identity-test", isDaemon = true) {
            var failed = false
            try {
                ActivityUriGrantSystemSmoke.verifyCallerIdentity()
            } catch (error: Throwable) {
                failed = true
                error.printStackTrace()
            } finally {
                runCatching { ActivityUriGrantSystemSmoke.stopIdentityHost() }.onFailure {
                    failed = true
                    it.printStackTrace()
                }
            }
            Runtime.getRuntime().exit(if (failed) 1 else 0)
        }
        Looper.loop()
    }
}
