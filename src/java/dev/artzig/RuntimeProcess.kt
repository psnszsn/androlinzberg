package dev.artzig

import com.android.internal.os.RuntimeInit
import com.android.internal.os.ZygoteInit
import org.json.JSONObject

/** ZygoteInit.zygoteInit/applicationInit after the host has exec'd and
 * authenticated an actual child. Common RuntimeInit setup already ran in
 * RuntimeBootstrap; this entry preserves the original target SDK, compatibility
 * policy and main method, without constructing or attaching an ActivityThread. */
object RuntimeProcess {
    @JvmStatic fun main(arguments: Array<String>) {
        BinderSession.setExitOnDisconnect()
        require(arguments.size == 1)
        val config = JSONObject(android.util.Base64.decode(arguments[0], android.util.Base64.NO_WRAP)
            .toString(Charsets.UTF_8))
        val entry = config.getJSONArray("entry").let { list -> Array(list.length()) { list.getString(it) } }
        val disabled = config.getJSONArray("disabledCompat").let { list -> LongArray(list.length()) { list.getLong(it) } }
        val exemptions = config.getJSONArray("exemptions").let { list -> Array(list.length()) { list.getString(it) } }
        val logRate = config.getInt("logRate")
        val statsRate = config.getInt("statsRate")
        ZygoteInit.setApiDenylistExemptions(exemptions)
        ZygoteInit.setHiddenApiAccessLogSampleRate(maxOf(logRate, statsRate))
        // ZygoteConnection uses this original package-private logger. Exec
        // applies its captured policy in the child, never to the supervisor VM.
        val logger = Class.forName("com.android.internal.os.StatsdHiddenApiUsageLogger")
        logger.getDeclaredMethod("setHiddenApiAccessLogSampleRates", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(null, logRate, statsRate)
        val instance = logger.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
        ZygoteInit.setHiddenApiUsageLogger(instance as dalvik.system.VMRuntime.HiddenApiUsageLogger)
        val frameworkRes = checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES"))
        if (entry.first() == dev.artzig.system.HostedWebViewZygote.ENTRY) {
            HostContexts.prepareApplicationEnvironment(frameworkRes)
            android.os.Process.setArgV0(checkNotNull(System.getenv("AZK_APP_PROCESS_NAME")))
            dev.artzig.system.HostedWebViewZygote.run(config.getString("webViewProvider"),
                entry.copyOfRange(1, entry.size))
            return
        }
        if (entry.first() == "android.app.ActivityThread") {
            HostContexts.prepareForActivityThreadMain(frameworkRes)
            dev.artzig.testing.ProcessInspection.observeActivityMainStartup()
        } else HostContexts.prepareApplicationEnvironment(frameworkRes)
        if (java.security.Security.getProvider("AndroidKeyStore") == null)
            java.security.Security.insertProviderAt(ArtzigKeyStoreProvider(), 1)
        android.os.Process.setArgV0(checkNotNull(System.getenv("AZK_APP_PROCESS_NAME")))
        ApplicationEntry().run(config.getInt("targetSdk"), disabled, entry)
    }

    private class ApplicationEntry : RuntimeInit() {
        fun run(sdk: Int, disabled: LongArray, arguments: Array<String>) {
            applicationInit(sdk, disabled, arguments, RuntimeProcess::class.java.classLoader).run()
        }
    }
}
