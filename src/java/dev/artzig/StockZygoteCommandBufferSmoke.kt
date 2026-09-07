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

    fun run() {
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
