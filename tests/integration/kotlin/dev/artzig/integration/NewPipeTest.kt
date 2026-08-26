package dev.artzig.integration

import dev.artzig.testkit.ApkRule
import kotlin.test.Test
import org.junit.Rule

class NewPipeTest {
    @get:Rule
    val app = ApkRule("newpipe-0.29.1")

    @Test
    fun launchesHomeWithoutRuntimeIssues() = app.run {
        expectVisible(text = "Live")
        expectVisible(description = "Search")
        expectVisible(description = "Open Drawer")
        assertLog("ApkHost", "starting org.schabi.newpipe.MainActivity")
    }

    @Test
    fun opensAndEditsSearch() = app.run {
        click(description = "Search")
        waitFor(className = "android.widget.EditText")
        setText(value = "kotlin integration", className = "android.widget.EditText")
        expectVisible(text = "kotlin integration")
        key(value = "Escape", className = "android.widget.EditText")
        click(description = "Navigate up")
        expectVisible(text = "Live")
    }
}
