package dev.artzig.testkit

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject

data class Selector(
    val resourceId: String? = null,
    val text: String? = null,
    val description: String? = null,
    val className: String? = null,
    val role: String? = null,
    val index: Int? = null,
    val allWindows: Boolean = false,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        resourceId?.let { addProperty("resource_id", it) }
        text?.let { addProperty("text", it) }
        description?.let { addProperty("content_description", it) }
        className?.let { addProperty("class_name", it) }
        role?.let { addProperty("role", it) }
        index?.let { addProperty("index", it) }
        if (allWindows) addProperty("window", "all")
    }

    val isEmpty: Boolean
        get() = resourceId == null && text == null && description == null &&
            className == null && role == null && index == null && !allWindows
}

enum class WaitKind(val wireName: String) {
    NOW("now"), READY("ready"), IDLE("idle"), EXISTS("exists"), ABSENT("absent"),
    TREE_CHANGED("tree_changed"), FRAME_PRESENTED("frame_presented");
}

enum class ActionKind(val wireName: String) {
    CLICK("click"), TYPE_KEYS("type_keys"), SET_TEXT("set_text"), KEY("key");
}

enum class InputVia(val wireName: String) {
    WAYLAND("wayland"), SEMANTIC("semantic");
}

data class Action(
    val kind: ActionKind,
    val via: InputVia,
    val text: String? = null,
    val key: String? = null,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("kind", kind.wireName)
        addProperty("via", via.wireName)
        add("text", text?.let(::stringJson) ?: JsonNull.INSTANCE)
        add("key", key?.let(::stringJson) ?: JsonNull.INSTANCE)
    }
}

data class Mark(
    val epoch: String,
    val commandSeq: Long,
    val inputSeq: Long,
    val treeRevision: Long,
    val frameSeq: Long? = null,
    val presentedFrameSeq: Long? = null,
    val logSeq: Long? = null,
    val issueSeq: Long? = null,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("epoch", epoch)
        addProperty("command_seq", commandSeq)
        addProperty("input_seq", inputSeq)
        addProperty("tree_revision", treeRevision)
        frameSeq?.let { addProperty("frame_seq", it) }
        presentedFrameSeq?.let { addProperty("presented_frame_seq", it) }
        logSeq?.let { addProperty("log_seq", it) }
        issueSeq?.let { addProperty("issue_seq", it) }
    }

    companion object {
        fun fromJson(json: JsonObject): Mark = Mark(
            epoch = json.requireString("epoch"),
            commandSeq = json.requireLong("command_seq"),
            inputSeq = json.requireLong("input_seq"),
            treeRevision = json.requireLong("tree_revision"),
            frameSeq = json.longOrNull("frame_seq"),
            presentedFrameSeq = json.longOrNull("presented_frame_seq"),
            logSeq = json.longOrNull("log_seq"),
            issueSeq = json.longOrNull("issue_seq"),
        )
    }
}

data class RuntimeLog(
    val sequence: Long,
    val level: String,
    val tag: String,
    val message: String,
    val thread: String,
)

data class RuntimeIssue(
    val sequence: Long,
    val kind: String,
    val owner: String,
    val member: String,
    val detail: String,
    val thread: String,
)

data class Diagnostics(
    val json: JsonObject,
    val logs: List<RuntimeLog>,
    val issues: List<RuntimeIssue>,
) {
    companion object {
        fun fromJson(json: JsonObject): Diagnostics {
            val runtime = json.objectOrEmpty("runtime")
            val logs = runtime.arrayOrEmpty("logs").mapObjects { record ->
                RuntimeLog(
                    sequence = record.longOrNull("seq") ?: 0,
                    level = record.stringOrEmpty("level"),
                    tag = record.stringOrEmpty("tag"),
                    message = record.stringOrEmpty("message"),
                    thread = record.stringOrEmpty("thread"),
                )
            }
            val issues = runtime.arrayOrEmpty("issues").mapObjects { record ->
                RuntimeIssue(
                    sequence = record.longOrNull("seq") ?: 0,
                    kind = record.stringOrEmpty("kind"),
                    owner = record.stringOrEmpty("owner"),
                    member = record.stringOrEmpty("member"),
                    detail = record.stringOrEmpty("detail"),
                    thread = record.stringOrEmpty("thread"),
                )
            }
            return Diagnostics(json, logs, issues)
        }
    }
}

data class AccessibilityState(
    val revision: Long,
    val json: JsonObject,
) {
    companion object {
        internal fun fromDiagnostics(diagnostics: Diagnostics): AccessibilityState {
            val tree = diagnostics.json.objectOrEmpty("tree")
            return AccessibilityState(tree.longOrNull("revision") ?: 0, tree)
        }
    }
}

data class StepResult(
    val ok: Boolean,
    val action: JsonObject,
    val before: Mark,
    val after: Mark?,
    val actionResult: JsonElement?,
    val actionError: JsonElement?,
    val diagnosticError: String?,
    val diagnostics: Diagnostics,
    val rawLog: String,
    val rawLogTruncated: Boolean,
    val json: JsonObject,
) {
    val accessibility: AccessibilityState
        get() = AccessibilityState.fromDiagnostics(diagnostics)

    fun requireClean(): StepResult {
        if (!ok || diagnostics.issues.isNotEmpty()) {
            val detail = diagnostics.issues.joinToString { "${it.kind}: ${it.owner}.${it.member}: ${it.detail}" }
            throw AssertionError(detail.ifEmpty { "test-control action failed: $json" })
        }
        if (rawLog.contains("FATAL EXCEPTION:") || rawLog.contains("Fatal signal ")) {
            throw AssertionError("fatal runtime output after action:\n$rawLog")
        }
        return this
    }

    internal fun requireExpectedIssue(expectation: IssueExpectation): StepResult {
        if (actionError != null || diagnosticError != null) {
            throw AssertionError("test-control action failed while expecting an issue: $json")
        }
        val matching = diagnostics.issues.filter(expectation::matches)
        val unexpected = diagnostics.issues.filterNot(expectation::matches)
        if (matching.isEmpty()) {
            throw AssertionError(
                "expected runtime issue ${expectation.describe()}, but the action reported none",
            )
        }
        if (unexpected.isNotEmpty()) {
            val detail = unexpected.joinToString { "${it.kind}: ${it.owner}.${it.member}: ${it.detail}" }
            throw AssertionError("unexpected runtime issues while expecting ${expectation.describe()}: $detail")
        }
        if (rawLog.contains("FATAL EXCEPTION:") || rawLog.contains("Fatal signal ")) {
            throw AssertionError("fatal runtime output after action:\n$rawLog")
        }
        return this
    }
}

data class IssueExpectation(
    val kind: String,
    val owner: String,
    val member: String,
) {
    internal fun matches(issue: RuntimeIssue): Boolean =
        issue.kind == kind && issue.owner == owner && issue.member == member

    internal fun describe(): String = "$kind: $owner.$member"
}

internal fun JsonObject.objectOrEmpty(name: String): JsonObject =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

internal fun JsonObject.arrayOrEmpty(name: String): JsonArray =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

internal fun JsonObject.stringOrEmpty(name: String): String =
    get(name)?.takeIf { !it.isJsonNull }?.asString ?: ""

internal fun JsonObject.longOrNull(name: String): Long? =
    get(name)?.takeIf { !it.isJsonNull }?.asLong

internal fun JsonObject.requireString(name: String): String =
    get(name)?.takeIf { !it.isJsonNull }?.asString
        ?: throw IllegalArgumentException("missing string field $name")

internal fun JsonObject.requireLong(name: String): Long =
    get(name)?.takeIf { !it.isJsonNull }?.asLong
        ?: throw IllegalArgumentException("missing integer field $name")

internal inline fun <T> JsonArray.mapObjects(transform: (JsonObject) -> T): List<T> =
    mapNotNull { element -> element.takeIf { it.isJsonObject }?.asJsonObject?.let(transform) }

private fun stringJson(value: String): JsonElement = com.google.gson.JsonPrimitive(value)
