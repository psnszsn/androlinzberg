package dev.artzig.integration

import dev.artzig.testkit.ApkRule
import dev.artzig.testkit.LaunchSize
import dev.artzig.testkit.WaitKind
import kotlin.test.Test
import org.junit.Rule

class NewPipeTest {
    @get:Rule
    val app = ApkRule("newpipe-0.29.1", LaunchSize(412, 915))

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

    @Test
    fun revealingVideoControlsKeepsCompositorFullscreen() = app.run {
        waitFor(text = "LIVE")
        waitFor(text = "Associated Press")
        click(text = "Associated Press", index = 0)
        waitFor(description = "Comments")
        click(description = "Play video, duration:")
        waitFor(className = "android.widget.ProgressBar", stateDescription = "0%")
        click(description = "Play video, duration:")
        waitFor(description = "Toggle screen orientation")
        click(description = "Toggle screen orientation", settle = WaitKind.NOW)
        waitForFullscreen(true)

        waitFor(description = "Toggle screen orientation", absent = true)
        click(description = "Play video, duration:", settle = WaitKind.NOW)
        assertFullscreenRemains(true, durationMillis = 2_000)
        waitFor(description = "Toggle screen orientation")
    }

    @Test
    fun fullscreenButtonExitsAndRestoresVideoDetails() = app.run {
        waitFor(text = "LIVE")
        waitFor(text = "Associated Press")
        click(text = "Associated Press", index = 0)
        waitFor(description = "Comments")
        waitFor(text = "Next")
        waitFor(text = "Background")
        waitForFullscreen(false)

        click(description = "Play video, duration:")
        waitFor(className = "android.widget.ProgressBar", stateDescription = "0%")
        click(description = "Play video, duration:")
        waitFor(description = "Toggle screen orientation")
        click(description = "Toggle screen orientation", settle = WaitKind.NOW)

        waitForFullscreen(true)
        waitFor(text = "Next", absent = true)
        if (isVisible(description = "Toggle screen orientation")) {
            click(description = "Play video, duration:", settle = WaitKind.NOW)
        }
        waitFor(description = "Toggle screen orientation", absent = true)

        click(description = "Play video, duration:", settle = WaitKind.NOW)
        waitFor(description = "Toggle screen orientation")
        waitForFullscreen(true)
        click(description = "Toggle screen orientation")

        waitForFullscreen(false)
        waitFor(description = "Play video, duration:")
        waitFor(text = "Comments are disabled")
        waitFor(text = "Next")
        waitFor(text = "Background")
    }
}
