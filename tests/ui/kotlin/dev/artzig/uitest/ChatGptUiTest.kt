package dev.artzig.uitest

import androidx.test.uiautomator.By
import dev.artzig.uitest.UiApp.expectVisible
import org.junit.Test

class ChatGptUiTest {
    @Test
    fun launchesWelcomeWithoutRuntimeIssues() = capture {
        openGuestChat()
    }

    @Test
    fun sendsGuestMessageWithoutRuntimeIssues() = capture {
        openGuestChat()
        val prompt = "Reply with exactly this single word, no punctuation: pineapple"
        UiApp.waitFor(By.clazz("android.widget.EditText")).text = prompt
        UiApp.click(UiApp.description("Send Message"))
        expectVisible(prompt)
        expectVisible("pineapple")
    }

    private fun openGuestChat() {
        expectVisible("Welcome to ChatGPT")
        // The server selects either sign-in onboarding or the guest terms sheet.
        UiApp.click(By.text(java.util.regex.Pattern.compile("Skip|Continue")))
        UiApp.waitUntilGone(UiApp.text("Welcome to ChatGPT"))
        UiApp.waitFor(UiApp.description("Ask ChatGPT"))
        UiApp.waitFor(By.clazz("android.widget.EditText"))
    }

    private fun capture(action: () -> Unit) {
        try {
            action()
        } finally {
            try {
                val artifacts = java.io.File(checkNotNull(System.getenv("AZK_TEST_ARTIFACTS")))
                UiTest.device.dumpWindowHierarchy(java.io.File(artifacts, "window.xml"))
                java.io.File(artifacts, "screen.png").outputStream().use {
                    UiApp.screenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } catch (failure: Throwable) {
                println("ChatGptUiTest: could not capture UI: $failure")
            }
        }
    }
}
