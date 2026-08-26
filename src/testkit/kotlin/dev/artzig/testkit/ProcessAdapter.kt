package dev.artzig.testkit

import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal interface ManagedProcess {
    val isAlive: Boolean
    val exitCode: Int?
    fun terminate()
}

internal fun interface ProcessAdapter {
    fun start(command: List<String>, environment: Map<String, String>, log: Path): ManagedProcess
}

internal object SystemProcessAdapter : ProcessAdapter {
    override fun start(command: List<String>, environment: Map<String, String>, log: Path): ManagedProcess {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .apply { this.environment().putAll(environment) }
            .start()
        return object : ManagedProcess {
            override val isAlive: Boolean get() = process.isAlive
            override val exitCode: Int? get() = if (process.isAlive) null else process.exitValue()

            override fun terminate() {
                process.toHandle().descendants().forEach { child -> child.destroy() }
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.toHandle().descendants().forEach { child -> child.destroyForcibly() }
                    process.destroyForcibly()
                    process.waitFor(2, TimeUnit.SECONDS)
                }
            }
        }
    }
}
