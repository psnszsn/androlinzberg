package dev.artzig.integration

import dev.artzig.testkit.ApkRule
import dev.artzig.testkit.LaunchSize
import kotlin.test.Test
import org.junit.Rule

class WindowFixtureTest {
    @get:Rule
    val app = ApkRule("window-contracts", LaunchSize(412, 915))

    @Test
    fun launchesAndUpdatesState() = app.run {
        expectVisible(text = "Counter: 0")
        click(text = "INCREMENT COUNTER")
        expectVisible(text = "Counter: 1")
        assertLog("WindowFixture", "\"event\":\"increment\"")
    }

    @Test
    fun dialogRemainsAttachedToTheActivity() = app.run {
        click(text = "SHOW ATTACHED DIALOG")
        expectVisible(text = "Fixture dialog")
        click(text = "CLOSE DIALOG")
        waitFor(text = "Fixture dialog", absent = true)
        expectVisible(text = "Counter: 0")
    }

    @Test
    fun recreationRestoresSavedCounter() = app.run {
        click(text = "INCREMENT COUNTER")
        click(text = "RECREATE ACTIVITY")
        expectVisible(text = "Counter: 1")
        assertLog("WindowFixture", "\"event\":\"save\"")
        assertLog("WindowFixture", "\"event\":\"restore\"")
    }
}
