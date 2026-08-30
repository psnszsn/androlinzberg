package dev.artzig.testkit

import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelsTest {
    @Test
    fun launchSizeUsesLauncherSyntax() {
        assertEquals("412x915", LaunchSize(412, 915).toString())
        assertFailsWith<IllegalArgumentException> { LaunchSize(0, 915) }
        assertFailsWith<IllegalArgumentException> { LaunchSize(412, 0) }
    }

    @Test
    fun selectorUsesWireFieldNames() {
        val json = Selector(
            resourceId = "org.example:id/save",
            text = "Save",
            description = "Save document",
            className = "android.widget.Button",
            role = "button",
            index = 2,
            allWindows = true,
            visible = true,
            stateDescription = "ready",
        ).toJson()
        assertEquals("org.example:id/save", json["resource_id"].asString)
        assertEquals("Save document", json["content_description"].asString)
        assertEquals("android.widget.Button", json["class_name"].asString)
        assertEquals("all", json["window"].asString)
        assertEquals(true, json["visible"].asBoolean)
        assertEquals("ready", json["state_description"].asString)
    }

    @Test
    fun dragActionUsesNormalizedWireCoordinates() {
        val json = Action(
            ActionKind.DRAG,
            InputVia.WAYLAND,
            fromXFraction = 0.1,
            fromYFraction = 0.5,
            toXFraction = 0.8,
            toYFraction = 0.5,
        ).toJson()
        assertEquals("drag", json["kind"].asString)
        assertEquals(0.1, json["from_x_fraction"].asDouble)
        assertEquals(0.8, json["to_x_fraction"].asDouble)
        assertFailsWith<IllegalArgumentException> {
            Action(ActionKind.DRAG, InputVia.WAYLAND, fromXFraction = 1.1)
        }
    }

    @Test
    fun markCanBeExtractedFromNestedResponse() {
        val mark = JsonObject().apply {
            addProperty("epoch", "epoch-1")
            addProperty("command_seq", 4)
            addProperty("input_seq", 5)
            addProperty("tree_revision", 6)
            addProperty("presented_frame_seq", 7)
        }
        val response = JsonObject().apply {
            add("parameters", JsonObject().apply {
                add("observation", JsonObject().apply { add("mark", mark) })
            })
        }
        val extracted = TestControlClient("/tmp/not-used").extractMark(response)
        assertEquals("epoch-1", extracted.epoch)
        assertEquals(6, extracted.treeRevision)
        assertEquals(7, extracted.presentedFrameSeq)
        assertEquals(7, extracted.toJson()["presented_frame_seq"].asLong)
    }

    @Test
    fun cliUsageErrorsUseExitCodeTwo() {
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        val status = AzkTestCli.run(
            arrayOf("click", "--text"),
            mapOf("AZK_TEST_SOCKET" to "/tmp/not-used"),
            PrintStream(output),
            PrintStream(errors),
        )
        assertEquals(2, status)
        assertTrue(errors.toString().contains("requires a value"))
    }

    @Test
    fun structuredIssuesFailUnlessTheyMatchAnExplicitExpectation() {
        val issue = RuntimeIssue(3, "unimplemented", "android.view.View", "probe()", "missing", "main")
        val diagnostics = Diagnostics(JsonObject(), emptyList(), listOf(issue))
        val result = stepResult(diagnostics)

        assertFailsWith<AssertionError> { result.requireClean() }
        result.requireExpectedIssue(IssueExpectation("unimplemented", "android.view.View", "probe()"))
        assertFailsWith<AssertionError> {
            result.requireExpectedIssue(IssueExpectation("runtime_exception", "android.view.View", "probe()"))
        }
    }

    @Test
    fun fatalRawOutputFailsEvenWhenStructuredIssueIsExpected() {
        val issue = RuntimeIssue(3, "unimplemented", "android.view.View", "probe()", "missing", "main")
        val result = stepResult(
            Diagnostics(JsonObject(), emptyList(), listOf(issue)),
            rawLog = "FATAL EXCEPTION: main",
        )
        assertFailsWith<AssertionError> {
            result.requireExpectedIssue(IssueExpectation("unimplemented", "android.view.View", "probe()"))
        }
    }

    private fun stepResult(diagnostics: Diagnostics, rawLog: String = ""): StepResult {
        val mark = Mark("epoch", 1, 1, 1)
        return StepResult(
            ok = diagnostics.issues.isEmpty(),
            action = JsonObject(),
            before = mark,
            after = mark,
            actionResult = JsonObject(),
            actionError = null,
            diagnosticError = null,
            diagnostics = diagnostics,
            rawLog = rawLog,
            rawLogTruncated = false,
            json = JsonObject(),
        )
    }
}
