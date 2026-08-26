package dev.artzig.testkit

import org.junit.internal.TextListener
import org.junit.runner.JUnitCore
import org.junit.runner.Request
import java.io.File
import java.util.jar.JarFile
import kotlin.system.exitProcess

object IntegrationTestMain {
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(JUnitLauncher.run("dev.artzig.integration", args))
    }
}

object TestkitUnitTestMain {
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(JUnitLauncher.run("dev.artzig.testkit", args))
    }
}

internal object JUnitLauncher {
    fun run(packageName: String, args: Array<String>): Int {
        var classFilter: String? = null
        var methodFilter: String? = null
        var index = 0
        while (index < args.size) {
            when (args[index++]) {
                "--class" -> classFilter = args.getOrNull(index++)
                    ?: return usage("--class requires a name")
                "--method" -> methodFilter = args.getOrNull(index++)
                    ?: return usage("--method requires a name")
                else -> return usage("unknown test option ${args[index - 1]}")
            }
        }
        val discovered = discover(packageName)
        val selected = classFilter?.let { wanted ->
            discovered.filter { it.name == wanted || it.simpleName == wanted }
        } ?: discovered
        if (selected.isEmpty()) {
            System.err.println("no tests found in $packageName${classFilter?.let { " matching $it" } ?: ""}")
            return 1
        }
        if (methodFilter != null && selected.size != 1) {
            return usage("--method requires exactly one selected --class")
        }
        val request = if (methodFilter != null) {
            Request.method(selected.single(), methodFilter)
        } else {
            Request.classes(*selected.toTypedArray())
        }
        val core = JUnitCore().apply { addListener(TextListener(System.out)) }
        return if (core.run(request).wasSuccessful()) 0 else 1
    }

    private fun discover(packageName: String): List<Class<*>> {
        val prefix = packageName.replace('.', '/') + "/"
        val names = linkedSetOf<String>()
        for (entry in System.getProperty("java.class.path").split(File.pathSeparator)) {
            val file = File(entry)
            if (file.isFile && file.extension == "jar") {
                JarFile(file).use { jar ->
                    jar.entries().asSequence()
                        .map { it.name }
                        .filter { it.startsWith(prefix) && it.endsWith("Test.class") && '$' !in it }
                        .mapTo(names) { it.removeSuffix(".class").replace('/', '.') }
                }
            } else if (file.isDirectory) {
                val root = file.toPath().resolve(prefix)
                if (root.toFile().isDirectory) {
                    root.toFile().walkTopDown()
                        .filter { it.isFile && it.name.endsWith("Test.class") && '$' !in it.name }
                        .mapTo(names) { candidate ->
                            file.toPath().relativize(candidate.toPath()).toString()
                                .removeSuffix(".class").replace(File.separatorChar, '.')
                        }
                }
            }
        }
        return names.sorted().map { Class.forName(it) }
    }

    private fun usage(message: String): Int {
        System.err.println(message)
        System.err.println("usage: --class CLASS [--method METHOD]")
        return 2
    }
}
