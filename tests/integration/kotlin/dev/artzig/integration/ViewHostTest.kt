package dev.artzig.integration

import dev.artzig.testkit.AppSession
import dev.artzig.testkit.InputVia
import dev.artzig.testkit.LaunchSize
import kotlin.test.Test

class ViewHostTest {
    @Test
    fun clickingButtonUpdatesStatusWithoutRuntimeIssues() {
        val session = AppSession.startViewHost(
            testId = "dev.artzig.integration.ViewHostTest.clickingButtonUpdatesStatusWithoutRuntimeIssues",
            launchSize = LaunchSize(800, 600),
        )
        try {
            session.expectVisible(text = "TAP ME")
            session.click(text = "TAP ME")
            session.expectVisible(text = "Button clicked! Input dispatch works.")
            session.finishSuccessfully()
        } catch (error: Throwable) {
            session.captureFailure(error)
            throw error
        } finally {
            session.close()
        }
    }

    @Test
    fun semanticActionAcceptsAbsentOptionalText() {
        val session = AppSession.startViewHost(
            testId = "dev.artzig.integration.ViewHostTest.semanticActionAcceptsAbsentOptionalText",
            launchSize = LaunchSize(800, 600),
        )
        try {
            session.expectVisible(text = "TAP ME")
            session.click(text = "TAP ME", via = InputVia.SEMANTIC)
            session.expectVisible(text = "Button clicked! Input dispatch works.")
            session.finishSuccessfully()
        } catch (error: Throwable) {
            session.captureFailure(error)
            throw error
        } finally {
            session.close()
        }
    }
}
