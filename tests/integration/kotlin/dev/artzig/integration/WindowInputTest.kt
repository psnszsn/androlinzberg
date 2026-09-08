package dev.artzig.integration

import dev.artzig.testkit.ApkRule
import dev.artzig.testkit.InputVia
import dev.artzig.testkit.LaunchSize
import kotlin.test.Test
import org.junit.Rule

/** Input contracts exercised through a real APK activity and its window. */
class WindowInputTest {
    @get:Rule
    val app = ApkRule("window-contracts", LaunchSize(800, 600))

    @Test
    fun clickingButtonUpdatesStatusWithoutRuntimeIssues() = app.run {
        expectVisible(text = "Counter: 0")
        click(text = "INCREMENT COUNTER")
        expectVisible(text = "Counter: 1")
        assertLog("WindowFixture", "\"event\":\"increment\"")
    }

    @Test
    fun semanticActionAcceptsAbsentOptionalText() = app.run {
        expectVisible(text = "Counter: 0")
        click(text = "INCREMENT COUNTER", via = InputVia.SEMANTIC)
        expectVisible(text = "Counter: 1")
        assertLog("WindowFixture", "\"event\":\"increment\"")
    }
}
