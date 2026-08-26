package dev.artzig.testkit

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppSessionTest {
    @Test
    fun closeTerminatesProcessAndRemovesPerTestRuntime() {
        val runtime = Files.createTempDirectory("app-session-runtime")
        val artifacts = Files.createTempDirectory("app-session-artifacts")
        val log = artifacts.resolve("app.log")
        Files.writeString(log, "")
        val process = InMemoryProcess()
        val apk = ResolvedApk(
            "fixture",
            ApkDefinition("FIXTURE_APK", "00", "org.example", 1, "1"),
            artifacts.resolve("fixture.apk"),
        )
        val session = AppSession(
            apk,
            artifacts,
            runtime,
            process,
            TestControlClient(runtime.resolve("control.sock").toString()),
            log,
        )

        session.close()

        assertTrue(process.terminated)
        assertFalse(Files.exists(runtime))
        assertTrue(Files.exists(artifacts))
        Files.walk(artifacts).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private class InMemoryProcess : ManagedProcess {
        var terminated = false
        override val isAlive: Boolean get() = !terminated
        override val exitCode: Int? get() = if (terminated) 0 else null
        override fun terminate() {
            terminated = true
        }
    }
}
