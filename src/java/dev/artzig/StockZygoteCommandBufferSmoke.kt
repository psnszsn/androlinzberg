package dev.artzig

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.lang.reflect.InvocationTargetException
import org.json.JSONObject

/** The original command-buffer JNI boundary, without an Android zygote process. */
internal object StockZygoteCommandBufferSmoke {
    private val type = Class.forName("com.android.internal.os.ZygoteCommandBuffer")
    private fun method(name: String, vararg types: Class<*>) =
        type.getDeclaredMethod(name, *types).apply { isAccessible = true }
    private fun invoke(name: String, target: Any?, vararg args: Pair<Class<*>, Any?>): Any? = try {
        method(name, *args.map { it.first }.toTypedArray()).invoke(target, *args.map { it.second }.toTypedArray())
    } catch (failure: InvocationTargetException) { throw failure.targetException }

    fun checkLaunchAuthority(system: Boolean) {
        val name = "azk-launch-authority-${android.os.Process.myPid()}-${System.nanoTime()}"
        android.net.LocalServerSocket(name).use { server ->
            android.net.LocalSocket().use { client ->
                client.connect(android.net.LocalSocketAddress(name))
                server.accept().use { peer ->
                    check(BinderSession.isSystemSocketPeer(peer.fileDescriptor) == system)
                    check(BinderSession.isSystemSocketPeer(client.fileDescriptor) == system)
                }
            }
        }
        check(!BinderSession.isSystemSocketPeer(java.io.FileDescriptor()))
        val packageName = "dev.artzig.launch.authority"
        if (!system) {
            for (kind in BinderSession.LaunchKind.entries) {
                check(runCatching { BinderSession.reserveLaunchForUid(packageName, packageName,
                    kind, 0, 0, -1) }.exceptionOrNull() is SecurityException)
            }
            return
        }
        val uid = BinderSession.reservePackageUid(packageName)
        val ids = mutableListOf<Long>()
        fun reserve(kind: BinderSession.LaunchKind, user: Int, requested: Int): Int {
            val parts = BinderSession.reserveLaunchForUid(packageName, packageName,
                kind, 0, user, requested).split(':', limit = 3)
            check(parts.size == 3 && parts[2].length == 64)
            ids += parts[0].toLong()
            return parts[1].toInt()
        }
        try {
            check(reserve(BinderSession.LaunchKind.APP, 1, uid + 100000) == uid + 100000)
            check(runCatching { reserve(BinderSession.LaunchKind.APP, 1, uid) }
                .exceptionOrNull() is SecurityException)
            check(reserve(BinderSession.LaunchKind.ISOLATED, 1, 199009) == 199009)
            check(runCatching { reserve(BinderSession.LaunchKind.ISOLATED, 1, 199009) }
                .exceptionOrNull() is IllegalStateException)
            check(reserve(BinderSession.LaunchKind.WEBVIEW_CONTROLLER, 0, 1053) == 1053)
        } finally { ids.forEach { check(BinderSession.revokeLaunch(it)) } }
    }

    fun run() {
        checkLaunchAuthority(false)
        val long = Long::class.javaPrimitiveType!!
        val int = Int::class.javaPrimitiveType!!
        val constructor = type.getDeclaredConstructor(Array<String>::class.java).apply { isAccessible = true }
        val args = arrayOf("--runtime-args", "雪/🙂", "\u0000tail", "")
        // Closing must release the single-instance allocation for the next request.
        repeat(3) {
            val buffer = constructor.newInstance(args as Any)
            try {
                check(invoke("getCount", buffer) == args.size)
                check(invoke("nextArg", buffer) == args[0])
                invoke("readFullyAndReset", buffer)
                check(invoke("getCount", buffer) == args.size)
                args.forEach { check(invoke("nextArg", buffer) == it) }
            } finally { invoke("close", buffer) }
        }

        val pipe = ParcelFileDescriptor.createPipe()
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write("2\nfirst\nsecond\n".toByteArray()) }
            val handle = invoke("getNativeBuffer", null, int to pipe[0].fd) as Long
            try {
                check(invoke("nativeGetCount", null, long to handle) == 2)
                check(invoke("nativeNextArg", null, long to handle) == "first")
                check(invoke("nativeNextArg", null, long to handle) == "second")
                check(invoke("nativeGetCount", null, long to handle) == 0)
                val cursor = JSONObject(ArtzigTestDiagnostics.snapshot(0, 0)).getLong("issue_cursor")
                try {
                    invoke("nativeForkRepeatedly", null, long to handle, int to -1, int to -1,
                        int to 10000, String::class.java to "zygote-smoke")
                    error("Missing hosted fork boundary was accepted")
                } catch (expected: UnsupportedOperationException) {
                    val issues = JSONObject(ArtzigTestDiagnostics.snapshot(0, cursor)).getJSONArray("issues")
                    check(issues.length() == 1)
                    check(issues.getJSONObject(0).getString("member") == "nativeForkRepeatedly")
                }
            } finally { invoke("freeNativeBuffer", null, long to handle) }
            check(Os.fcntlInt(pipe[0].fileDescriptor, OsConstants.F_GETFD, 0) >= 0)
        } finally { pipe.forEach { it.close() } }
    }
}
