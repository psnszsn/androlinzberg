package dev.artzig.testkit

import com.google.gson.GsonBuilder
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

object AzkTestCli {
    private val pretty = GsonBuilder().setPrettyPrinting().create()

    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args, System.getenv(), System.out, System.err))
    }

    fun run(
        args: Array<String>,
        environment: Map<String, String>,
        out: PrintStream,
        err: PrintStream,
    ): Int {
        return try {
            val parsed = parse(args, environment)
            if (parsed.help) {
                out.print(USAGE)
                return 0
            }
            val socket = parsed.socket ?: throw UsageException("--socket or AZK_TEST_SOCKET is required")
            val client = TestControlClient(
                socket,
                timeoutMillis = (parsed.timeoutSeconds * 1000).toLong().coerceAtLeast(1),
                rawLogPath = environment["AZK_TEST_RAW_LOG"]?.let(Path::of),
                stepLogPath = environment["AZK_TEST_STEP_LOG"]?.let(Path::of),
            )
            val result = execute(client, parsed)
            out.println(pretty.toJson(result.first))
            result.second
        } catch (error: UsageException) {
            err.println("azk-test: ${error.message}")
            err.print(USAGE)
            2
        } catch (error: Exception) {
            err.println("azk-test: ${error.message ?: error.javaClass.simpleName}")
            1
        }
    }

    private fun execute(client: TestControlClient, args: Parsed): Pair<JsonObject, Int> {
        return when (args.command) {
            "observe" -> client.observe(
                args.selector.takeUnless(Selector::isEmpty),
                args.wait,
                args.includeTree,
                args.stableFrames,
            ) to 0
            "wait" -> client.observe(
                args.selector.takeUnless(Selector::isEmpty),
                args.wait,
                includeTree = false,
                stableFrames = args.stableFrames,
            ) to 0
            "mark" -> JsonObject().apply { add("mark", client.mark().toJson()) } to 0
            "diagnose" -> client.diagnose(args.since?.let { loadMark(client, it) }) to 0
            "step" -> {
                val step = client.step(
                    args.selector,
                    args.action ?: throw UsageException("step requires an action"),
                    args.settle,
                    args.stableFrames,
                    args.allowMissing,
                )
                step.json to if (step.ok) 0 else 3
            }
            "click", "drag", "scroll-forward", "scroll-backward", "type", "set-text", "key" -> client.perform(
                args.selector,
                args.action ?: throw UsageException("${args.command} requires an action"),
                args.settle,
                args.stableFrames,
            ) to 0
            else -> throw UsageException("unknown command ${args.command}")
        }
    }

    private fun loadMark(client: TestControlClient, source: String): Mark {
        val text = if (source.startsWith("@")) Files.readString(Path.of(source.drop(1))) else source
        val json = JsonParser.parseString(text)
        if (!json.isJsonObject) throw IllegalArgumentException("mark JSON must be an object")
        return client.extractMark(json.asJsonObject)
    }

    private fun parse(args: Array<String>, environment: Map<String, String>): Parsed {
        if (args.isEmpty()) throw UsageException("a command is required")
        var socket = environment["AZK_TEST_SOCKET"]
        var timeout = 10.0
        var cursor = 0
        while (cursor < args.size && args[cursor].startsWith("--")) {
            val argument = args[cursor]
            when {
                argument == "--help" -> return Parsed(help = true)
                argument == "--socket" -> socket = args.valueAfter(cursor++, "--socket").also { cursor++ }
                argument.startsWith("--socket=") -> {
                    socket = argument.substringAfter('=')
                    cursor++
                }
                argument == "--timeout" -> timeout = args.valueAfter(cursor++, "--timeout").toDoubleOrNull()
                    ?.also { cursor++ } ?: throw UsageException("--timeout requires a number")
                argument.startsWith("--timeout=") -> {
                    timeout = argument.substringAfter('=').toDoubleOrNull()
                        ?: throw UsageException("--timeout requires a number")
                    cursor++
                }
                else -> break
            }
        }
        if (cursor >= args.size) throw UsageException("a command is required")
        var command = args[cursor++]
        if (command == "--help" || command == "help") return Parsed(help = true)
        if (command == "step") {
            if (cursor >= args.size) throw UsageException(
                "step requires click, drag, scroll-forward, scroll-backward, type, set-text, or key",
            )
            command = "step:${args[cursor++]}"
        }

        var resourceId: String? = null
        var text: String? = null
        var description: String? = null
        var className: String? = null
        var role: String? = null
        var selectorIndex: Int? = null
        var allWindows = false
        var visible: Boolean? = null
        var stateDescription: String? = null
        var wait = if (command == "observe") WaitKind.NOW else WaitKind.IDLE
        var settle = WaitKind.IDLE
        var stableFrames = 2
        var via = InputVia.WAYLAND
        var includeTree = false
        var allowMissing = false
        var since: String? = null
        var fromXFraction: Double? = null
        var fromYFraction: Double? = null
        var toXFraction: Double? = null
        var toYFraction: Double? = null
        val positional = mutableListOf<String>()

        while (cursor < args.size) {
            val rawArgument = args[cursor++]
            val equals = if (rawArgument.startsWith("--")) rawArgument.indexOf('=') else -1
            val argument = if (equals >= 0) rawArgument.substring(0, equals) else rawArgument
            val attachedValue = if (equals >= 0) rawArgument.substring(equals + 1) else null
            fun value(): String = attachedValue
                ?: args.valueAfter(cursor - 1, argument).also { cursor++ }
            fun flag() {
                if (attachedValue != null) throw UsageException("$argument does not take a value")
            }
            when (argument) {
                "--id" -> resourceId = value()
                "--text" -> text = value()
                "--description" -> description = value()
                "--class" -> className = value()
                "--role" -> role = value()
                "--state-description" -> stateDescription = value()
                "--index" -> selectorIndex = value().toIntOrNull()
                    ?: throw UsageException("--index requires an integer")
                "--visible" -> visible = value().toBooleanStrictOrNull()
                    ?: throw UsageException("--visible requires true or false")
                "--all-windows" -> { flag(); allWindows = true }
                "--wait" -> wait = parseWait(value(), observeOnly = true)
                "--settle" -> settle = parseWait(value(), observeOnly = false)
                "--stable-frames" -> stableFrames = value().toIntOrNull()
                    ?: throw UsageException("--stable-frames requires an integer")
                "--via" -> via = when (value()) {
                    "wayland" -> InputVia.WAYLAND
                    "semantic" -> InputVia.SEMANTIC
                    else -> throw UsageException("--via must be wayland or semantic")
                }
                "--tree" -> { flag(); includeTree = true }
                "--allow-missing" -> { flag(); allowMissing = true }
                "--since" -> since = value()
                "--from-x" -> fromXFraction = parseFraction(value(), "--from-x")
                "--from-y" -> fromYFraction = parseFraction(value(), "--from-y")
                "--to-x" -> toXFraction = parseFraction(value(), "--to-x")
                "--to-y" -> toYFraction = parseFraction(value(), "--to-y")
                "--help" -> { flag(); return Parsed(help = true) }
                else -> if (argument.startsWith("--")) {
                    throw UsageException("unknown option $argument")
                } else positional += argument
            }
        }

        val actualCommand = command.substringAfter("step:", command)
        if (actualCommand != "drag" && listOf(toXFraction, toYFraction).any { it != null }) {
            throw UsageException("--to-x/--to-y require the drag command")
        }
        if (actualCommand != "drag" && actualCommand != "click" &&
            listOf(fromXFraction, fromYFraction).any { it != null }
        ) {
            throw UsageException("--from-x/--from-y require the click or drag command")
        }
        if (command == "wait") {
            if (positional.size != 1) throw UsageException("wait requires ready, idle, exists, or absent")
            wait = parseWait(positional.removeAt(0), observeOnly = true).also {
                if (it == WaitKind.NOW) throw UsageException("wait does not accept now")
            }
        }
        val selector = Selector(
            resourceId, text, description, className, role, selectorIndex, allWindows, visible,
            stateDescription,
        )
        val action = when (actualCommand) {
            "click" -> {
                if (positional.isNotEmpty()) throw UsageException("click does not take a value")
                if (via != InputVia.WAYLAND && (fromXFraction != null || fromYFraction != null)) {
                    throw UsageException("click position fractions require --via wayland")
                }
                Action(ActionKind.CLICK, via, fromXFraction = fromXFraction, fromYFraction = fromYFraction)
            }
            "drag" -> {
                if (positional.isNotEmpty()) throw UsageException("drag does not take a value")
                if (via != InputVia.WAYLAND) throw UsageException("drag only supports --via wayland")
                Action(
                    ActionKind.DRAG,
                    InputVia.WAYLAND,
                    fromXFraction = fromXFraction ?: 0.25,
                    fromYFraction = fromYFraction ?: 0.5,
                    toXFraction = toXFraction ?: 0.75,
                    toYFraction = toYFraction ?: 0.5,
                )
            }
            "scroll-forward", "scroll-backward" -> {
                if (positional.isNotEmpty()) throw UsageException("$actualCommand does not take a value")
                if (via != InputVia.SEMANTIC) {
                    throw UsageException("$actualCommand requires --via semantic")
                }
                Action(
                    if (actualCommand == "scroll-forward") ActionKind.SCROLL_FORWARD else ActionKind.SCROLL_BACKWARD,
                    InputVia.SEMANTIC,
                )
            }
            "type" -> {
                if (positional.size != 1) throw UsageException("type requires a value")
                Action(if (via == InputVia.SEMANTIC) ActionKind.SET_TEXT else ActionKind.TYPE_KEYS, via, text = positional[0])
            }
            "set-text" -> {
                if (positional.size != 1) throw UsageException("set-text requires a value")
                Action(ActionKind.SET_TEXT, InputVia.SEMANTIC, text = positional[0])
            }
            "key" -> {
                if (positional.size != 1) throw UsageException("key requires a key name")
                Action(ActionKind.KEY, InputVia.WAYLAND, key = positional[0])
            }
            else -> null
        }
        if (action != null && selector.isEmpty) throw UsageException("at least one selector option is required")
        if (actualCommand !in COMMANDS) throw UsageException("unknown command $actualCommand")
        if (actualCommand !in setOf("type", "set-text", "key", "wait") && positional.isNotEmpty()) {
            throw UsageException("unexpected argument ${positional.first()}")
        }
        return Parsed(
            socket = socket,
            timeoutSeconds = timeout,
            command = if (command.startsWith("step:")) "step" else actualCommand,
            selector = selector,
            wait = wait,
            settle = settle,
            stableFrames = stableFrames,
            includeTree = includeTree,
            allowMissing = allowMissing,
            since = since,
            action = action,
        )
    }

    private fun parseWait(value: String, observeOnly: Boolean): WaitKind {
        val result = WaitKind.entries.firstOrNull { it.wireName == value }
            ?: throw UsageException("unknown wait kind $value")
        val accepted = if (observeOnly) {
            setOf(WaitKind.NOW, WaitKind.READY, WaitKind.IDLE, WaitKind.EXISTS, WaitKind.ABSENT)
        } else {
            setOf(WaitKind.NOW, WaitKind.IDLE, WaitKind.TREE_CHANGED, WaitKind.FRAME_PRESENTED)
        }
        if (result !in accepted) throw UsageException("wait kind $value is not valid here")
        return result
    }

    private fun parseFraction(value: String, option: String): Double =
        value.toDoubleOrNull()?.takeIf { it in 0.0..1.0 }
            ?: throw UsageException("$option requires a number between 0 and 1")

    private fun Array<String>.valueAfter(index: Int, option: String): String =
        getOrNull(index + 1) ?: throw UsageException("$option requires a value")

    private data class Parsed(
        val help: Boolean = false,
        val socket: String? = null,
        val timeoutSeconds: Double = 10.0,
        val command: String = "",
        val selector: Selector = Selector(),
        val wait: WaitKind = WaitKind.NOW,
        val settle: WaitKind = WaitKind.IDLE,
        val stableFrames: Int = 2,
        val includeTree: Boolean = false,
        val allowMissing: Boolean = false,
        val since: String? = null,
        val action: Action? = null,
    )

    private class UsageException(message: String) : IllegalArgumentException(message)

    private val COMMANDS = setOf(
        "observe", "wait", "click", "drag", "scroll-forward", "scroll-backward",
        "type", "set-text", "key", "mark", "diagnose",
    )

    private const val USAGE = """usage: azk-test [--socket PATH] [--timeout SECONDS] COMMAND ...

Commands:
  observe [selector] [--tree] [--wait now|ready|idle|exists|absent]
  wait ready|idle|exists|absent [selector]
  click|drag|scroll-forward|scroll-backward|type|set-text|key [selector] [value] [settle options]
  mark
  diagnose [--since JSON|@PATH]
  step click|drag|scroll-forward|scroll-backward|type|set-text|key [selector] [value] [settle options]

Selectors: --id ID --text TEXT --description TEXT --class CLASS --role ROLE
           --state-description TEXT --index N --visible true|false --all-windows
Drag: --from-x FRACTION --from-y FRACTION --to-x FRACTION --to-y FRACTION
"""
}
