package dev.artzig.testing

import android.os.Looper
import dalvik.system.PathClassLoader

/**
 * Entry point of a host test process on ART (`androlinzberg --join <session>
 * --javatest` with AZK_TEST_CLASS naming this class). It plays the part of
 * `am instrument`: test code and its libraries (JUnit, androidx UiAutomator)
 * live in the dex jar AZK_TEST_DEX, outside the framework, and run on a worker
 * thread while the main thread loops, as instrumentation tests expect.
 *
 * Arguments: the main class inside the dex jar, then that class's arguments.
 */
object HostTestRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        check(args.isNotEmpty()) { "usage: HostTestRunner <main class> [arguments...]" }
        val dex = checkNotNull(System.getenv("AZK_TEST_DEX")) { "AZK_TEST_DEX is not set" }
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        // Instrumentation.getContext(): this process's ActivityThread and system
        // context, attached on the thread that becomes its main looper.
        dev.artzig.HostContexts.bootstrap(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")) { "ARTZIG_FRAMEWORK_RES is not set" })
        Thread({
            val status = try {
                val loader = PathClassLoader(dex, HostTestRunner::class.java.classLoader)
                Thread.currentThread().contextClassLoader = loader
                loader.loadClass(args[0]).getMethod("main", Array<String>::class.java)
                    .invoke(null, args.copyOfRange(1, args.size)) as? Int ?: 0
            } catch (error: Throwable) {
                (error as? java.lang.reflect.InvocationTargetException)?.cause?.printStackTrace() ?: error.printStackTrace()
                2
            }
            // The main looper cannot quit; end the process as instrumentation does.
            System.exit(status)
        }, "HostTestRunner").start()
        Looper.loop()
    }
}
