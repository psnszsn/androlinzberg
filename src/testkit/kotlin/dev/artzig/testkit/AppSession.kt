package dev.artzig.testkit

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class AppSession internal constructor(
    val apk: ResolvedApk?,
    val artifactDirectory: Path,
    private val runtimeDirectory: Path,
    private val process: ManagedProcess,
    private val client: TestControlClient,
    private val appLog: Path,
) : AutoCloseable {
    private var diagnosticCursor: Mark? = null
    private var closed = false
    private val pretty = GsonBuilder().setPrettyPrinting().create()
    private var expectedIssue: IssueExpectation? = null

    internal fun awaitReady(timeoutMillis: Long = 15_000) {
        val socket = runtimeDirectory.resolve("control.sock")
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!Files.exists(socket)) {
            requireAlive("before the test-control socket became ready")
            if (System.nanoTime() >= deadline) {
                throw AssertionError("timed out waiting for test-control socket; log: $appLog")
            }
            Thread.sleep(25)
        }
        client.observe(wait = WaitKind.READY)
        val startup = client.diagnose()
        val startupDiagnostics = Diagnostics.fromJson(startup.objectOrEmpty("diagnostics"))
        requireDiagnosticsClean(startupDiagnostics, "during hosted process startup")
        requireNoFatalOutput()
        diagnosticCursor = runCatching { client.extractMark(startup) }.getOrElse { client.mark() }
    }

    fun waitFor(
        resourceId: String? = null,
        text: String? = null,
        description: String? = null,
        className: String? = null,
        role: String? = null,
        index: Int? = null,
        absent: Boolean = false,
        visible: Boolean = true,
        stateDescription: String? = null,
    ): JsonObject {
        requireAlive("while waiting for accessibility state")
        val result = client.observe(
            Selector(
                resourceId, text, description, className, role, index,
                visible = visible,
                stateDescription = stateDescription,
            ),
            wait = if (absent) WaitKind.ABSENT else WaitKind.EXISTS,
        )
        checkPendingDiagnostics()
        return result
    }

    fun expectVisible(
        resourceId: String? = null,
        text: String? = null,
        description: String? = null,
        className: String? = null,
        role: String? = null,
        index: Int? = null,
    ): JsonObject = waitFor(resourceId, text, description, className, role, index, visible = true)

    fun isVisible(
        resourceId: String? = null,
        text: String? = null,
        description: String? = null,
        className: String? = null,
        role: String? = null,
    ): Boolean {
        requireAlive("while observing accessibility visibility")
        val observation = client.observe(
            Selector(resourceId, text, description, className, role, visible = true),
        ).objectOrEmpty("observation")
        return observation["matches"]?.takeIf { it.isJsonArray }?.asJsonArray?.size() != 0
    }

    fun click(
        resourceId: String? = null,
        text: String? = null,
        description: String? = null,
        className: String? = null,
        role: String? = null,
        index: Int? = null,
        via: InputVia = InputVia.WAYLAND,
        settle: WaitKind = WaitKind.IDLE,
    ): StepResult = action(
        Selector(resourceId, text, description, className, role, index, visible = true),
        Action(ActionKind.CLICK, via),
        settle,
    )

    fun setText(
        value: String,
        resourceId: String? = null,
        text: String? = null,
        description: String? = null,
        className: String? = null,
        role: String? = null,
        settle: WaitKind = WaitKind.IDLE,
    ): StepResult = action(
        Selector(resourceId, text, description, className, role, visible = true),
        Action(ActionKind.SET_TEXT, InputVia.SEMANTIC, text = value),
        settle,
    )

    fun type(
        value: String,
        resourceId: String? = null,
        text: String? = null,
        description: String? = null,
        className: String? = null,
        role: String? = null,
        settle: WaitKind = WaitKind.IDLE,
    ): StepResult = action(
        Selector(resourceId, text, description, className, role, visible = true),
        Action(ActionKind.TYPE_KEYS, InputVia.WAYLAND, text = value),
        settle,
    )

    fun key(
        value: String,
        resourceId: String? = null,
        text: String? = null,
        description: String? = null,
        className: String? = null,
        role: String? = null,
        settle: WaitKind = WaitKind.IDLE,
    ): StepResult = action(
        Selector(resourceId, text, description, className, role, visible = true),
        Action(ActionKind.KEY, InputVia.WAYLAND, key = value),
        settle,
    )

    fun expectIssue(
        kind: String,
        owner: String,
        member: String,
        block: AppSession.() -> StepResult,
    ): StepResult {
        check(expectedIssue == null) { "runtime issue expectations cannot be nested" }
        expectedIssue = IssueExpectation(kind, owner, member)
        return try {
            val result = block()
            check(expectedIssue == null) { "expected issue block did not perform an action" }
            result
        } finally {
            expectedIssue = null
        }
    }

    fun assertLog(tag: String, containing: String) {
        val all = client.diagnose().objectOrEmpty("diagnostics")
        val diagnostics = Diagnostics.fromJson(all)
        if (diagnostics.logs.none { it.tag == tag && containing in it.message }) {
            throw AssertionError("no Android log from '$tag' contains '$containing'; log: $appLog")
        }
    }

    fun waitForFullscreen(expected: Boolean, timeoutMillis: Long = 5_000) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (fullscreen() != expected) {
            requireAlive("while waiting for compositor fullscreen=$expected")
            if (System.nanoTime() >= deadline) {
                throw AssertionError("timed out waiting for compositor fullscreen=$expected; log: $appLog")
            }
            Thread.sleep(10)
        }
        checkPendingDiagnostics()
    }

    fun assertFullscreenRemains(expected: Boolean, durationMillis: Long = 1_500) {
        val deadline = System.nanoTime() + durationMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            requireAlive("while observing compositor fullscreen stability")
            val actual = fullscreen()
            if (actual != expected) {
                throw AssertionError(
                    "compositor fullscreen changed to $actual while it should remain $expected; log: $appLog",
                )
            }
            Thread.sleep(10)
        }
        checkPendingDiagnostics()
    }

    /** Wait for real compositor presentation callbacks, without wall-clock sleeps. */
    fun waitForPresentedFrames(count: Int) {
        require(count > 0) { "presented frame count must be positive" }
        requireAlive("while waiting for $count compositor frames")
        val before = client.mark()
        client.observe(
            wait = WaitKind.FRAME_PRESENTED,
            stableFrames = count,
            after = before,
        )
        checkPendingDiagnostics()
    }

    fun screenshot(fileName: String): Path {
        require(fileName.matches(Regex("[A-Za-z0-9._-]+"))) {
            "screenshot name must be a plain file name"
        }
        Files.createDirectories(artifactDirectory)
        val screenshot = artifactDirectory.resolve(fileName)
        val status = ProcessBuilder("grim", screenshot.toString())
            .inheritIO()
            .start()
            .waitFor()
        if (status != 0 || !Files.exists(screenshot) || Files.size(screenshot) == 0L) {
            throw AssertionError("failed to capture compositor screenshot: $screenshot")
        }
        return screenshot
    }

    internal fun finishSuccessfully() {
        checkPendingDiagnostics()
        requireAlive("at successful test completion")
        requireNoFatalOutput()
    }

    internal fun captureFailure(error: Throwable) {
        Files.createDirectories(artifactDirectory)
        val observation = runCatching { client.observe(includeTree = true) }
            .getOrElse { unavailable("accessibility observation", it) }
        Files.writeString(artifactDirectory.resolve("a11y.json"), pretty.toJson(observation))
        val diagnosis = runCatching { client.diagnose() }
            .getOrElse { unavailable("diagnosis", it) }
        val failure = JsonObject().apply {
            addProperty("error", error.toString())
            add("diagnosis", diagnosis)
        }
        Files.writeString(artifactDirectory.resolve("failure.json"), pretty.toJson(failure))
        val screenshotPath = artifactDirectory.resolve("screenshot.png")
        runCatching { screenshot("screenshot.png") }
        if (!Files.exists(screenshotPath)) Files.write(screenshotPath, byteArrayOf())
    }

    override fun close() {
        if (closed) return
        closed = true
        process.terminate()
        deleteTree(runtimeDirectory)
    }

    private fun action(selector: Selector, action: Action, settle: WaitKind): StepResult {
        if (selector.isEmpty) throw IllegalArgumentException("an action requires a selector")
        checkPendingDiagnostics()
        requireAlive("before ${action.kind.wireName}")
        val result = client.step(selector, action, settle = settle)
        val expectation = expectedIssue.also { expectedIssue = null }
        if (expectation == null) result.requireClean() else result.requireExpectedIssue(expectation)
        diagnosticCursor = result.after ?: result.before
        requireAlive("after ${action.kind.wireName}")
        return result
    }

    private fun fullscreen(): Boolean = client.observe()
        .objectOrEmpty("observation")["fullscreen"]?.asBoolean ?: false

    private fun checkPendingDiagnostics() {
        val response = client.diagnose(diagnosticCursor)
        val diagnostics = Diagnostics.fromJson(response.objectOrEmpty("diagnostics"))
        requireDiagnosticsClean(diagnostics, "between test actions")
        diagnosticCursor = runCatching { client.extractMark(response) }.getOrElse { client.mark() }
        requireNoFatalOutput()
    }

    private fun requireDiagnosticsClean(diagnostics: Diagnostics, context: String) {
        if (diagnostics.issues.isEmpty()) return
        val detail = diagnostics.issues.joinToString("\n") {
            "${it.kind}: ${it.owner}.${it.member}: ${it.detail}"
        }
        throw AssertionError("runtime issues $context:\n$detail\nlog: $appLog")
    }

    private fun requireAlive(context: String) {
        if (process.isAlive) return
        throw AssertionError("hosted process exited ${process.exitCode} $context; log: $appLog")
    }

    private fun requireNoFatalOutput() {
        val output = runCatching { Files.readString(appLog) }.getOrDefault("")
        if ("FATAL EXCEPTION:" in output || "Fatal signal " in output) {
            throw AssertionError("fatal runtime output; log: $appLog")
        }
    }

    private fun unavailable(operation: String, error: Throwable): JsonObject = JsonObject().apply {
        addProperty("error", "$operation unavailable: ${error.message ?: error.javaClass.simpleName}")
    }

    companion object {
        internal fun start(
            apkKey: String,
            testId: String,
            environment: Map<String, String> = System.getenv(),
            processAdapter: ProcessAdapter = SystemProcessAdapter,
            launchSize: LaunchSize? = null,
        ): AppSession {
            val registryPath = Path.of(environment["AZK_APK_REGISTRY"] ?: "tests/apks.json")
                .toAbsolutePath().normalize()
            val apk = ApkRegistry.load(registryPath).resolve(apkKey, environment)
            return startHosted(
                apk = apk,
                launcherArguments = listOf(apk.path.toString()),
                testId = testId,
                environment = environment,
                processAdapter = processAdapter,
                launchSize = launchSize,
            )
        }

        internal fun startViewHost(
            testId: String,
            environment: Map<String, String> = System.getenv(),
            processAdapter: ProcessAdapter = SystemProcessAdapter,
            launchSize: LaunchSize? = null,
        ): AppSession = startHosted(
            apk = null,
            launcherArguments = listOf("--java"),
            testId = testId,
            environment = environment,
            processAdapter = processAdapter,
            launchSize = launchSize,
        )

        private fun startHosted(
            apk: ResolvedApk?,
            launcherArguments: List<String>,
            testId: String,
            environment: Map<String, String>,
            processAdapter: ProcessAdapter,
            launchSize: LaunchSize?,
        ): AppSession {
            val artifactRoot = Path.of(
                environment["AZK_ARTIFACT_ROOT"] ?: "zig-out/test-results/integration",
            ).toAbsolutePath().normalize()
            val artifactDirectory = artifactRoot.resolve(sanitize(testId))
            deleteTree(artifactDirectory)
            Files.createDirectories(artifactDirectory)
            val appLog = artifactDirectory.resolve("app.log")
            val steps = artifactDirectory.resolve("steps.jsonl")
            Files.writeString(appLog, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            Files.writeString(steps, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

            val runtime = Files.createTempDirectory("azk-integration-")
            val socket = runtime.resolve("control.sock")
            val data = runtime.resolve("data")
            val nativeLibraries = runtime.resolve("native-libs")
            Files.createDirectories(data)
            Files.createDirectories(nativeLibraries)
            val sharedCache = Path.of(
                environment["AZK_SHARED_CACHE"] ?: "zig-out/test-cache/integration",
            ).toAbsolutePath().normalize()
            Files.createDirectories(sharedCache)
            val launcher = Path.of(environment["AZK_LAUNCHER"] ?: "zig-out/bin/androlinzberg")
                .toAbsolutePath().normalize()
            val inheritedLibraryPath = environment["LD_LIBRARY_PATH"]
            val artLibraryPath = launcher.parent.resolve("art/lib64").toString()
            val childEnvironment = mutableMapOf(
                "AZK_TEST_SOCKET" to socket.toString(),
                "ARTZIG_DATA_DIR" to data.toString(),
                "ARTZIG_NATIVE_LIB_DIR" to nativeLibraries.toString(),
                "XDG_CACHE_HOME" to sharedCache.toString(),
                "LD_LIBRARY_PATH" to listOfNotNull(artLibraryPath, inheritedLibraryPath)
                    .joinToString(java.io.File.pathSeparator),
            )
            launchSize?.let { childEnvironment["AZK_SIZE"] = it.toString() }
            configureHeadlessOutput(environment, launchSize)
            val process = try {
                processAdapter.start(
                    listOf(launcher.toString()) + launcherArguments,
                    childEnvironment,
                    appLog,
                )
            } catch (error: Throwable) {
                deleteTree(runtime)
                throw error
            }
            val client = TestControlClient(socket.toString(), rawLogPath = appLog, stepLogPath = steps)
            return AppSession(
                apk, artifactDirectory, runtime, process, client, appLog,
            ).also { session ->
                try {
                    session.awaitReady()
                } catch (error: Throwable) {
                    session.captureFailure(error)
                    session.close()
                    throw error
                }
            }
        }

        private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9_.-]+"), "_")

        private fun configureHeadlessOutput(environment: Map<String, String>, size: LaunchSize?) {
            val output = environment["AZK_HEADLESS_OUTPUT"] ?: return
            val requestedSize = size ?: return
            val status = ProcessBuilder(
                "wlr-randr",
                "--output",
                output,
                "--custom-mode",
                requestedSize.toString(),
            )
                .inheritIO()
                .start()
                .waitFor()
            check(status == 0) {
                "failed to configure headless Wayland output $output to $requestedSize"
            }
        }

        private fun deleteTree(path: Path) {
            if (!Files.exists(path)) return
            Files.walk(path).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
