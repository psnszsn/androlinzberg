package dev.artzig.uitest.host

import androidx.test.uiautomator.By
import dev.artzig.uitest.UiApp.click
import dev.artzig.uitest.UiApp.expectVisible
import dev.artzig.uitest.UiApp.waitFor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.regex.Pattern

/**
 * The Wayland text-input protocol against a stock editor, in a desktop
 * (labwc) session with the test input method: Unicode preedit, commit and
 * deletion, password context, focus changes during composition, and removal
 * and restoration of the protocol while the app runs.
 */
class DesktopImeUiTest {
    @Test
    fun unicodeCompositionSurvivesFocusAndProtocolChanges() {
        click(By.text(Pattern.compile("show ime editor", Pattern.CASE_INSENSITIVE)))
        expectVisible("IME text=a😀中z select=3:3 composing=-1:-1")
        for ((action, expected) in listOf(
            "COMPOSE UNICODE" to "IME text=a😀é拼中z select=4:4 composing=3:5",
            "HIDE PREEDIT CARET" to "IME text=a😀候中z select=4:4 composing=3:4 caret=hidden",
            "COMMIT UNICODE" to "IME text=a😀文中z select=4:4 composing=-1:-1",
            "DELETE UNICODE" to "IME text=a😀éz select=4:4 composing=-1:-1",
            "FOCUS PASSWORD" to "Password length=14",
            "VERIFY PASSWORD CONTEXT" to "Password length=15",
            "FOCUS ORIGINAL EDITOR" to "IME text=a😀éz select=4:4 composing=-1:-1",
            "COMPOSE THEN LEAVE" to "IME text=a😀éé拼z select=5:5 composing=4:6",
            "REMOVE TEXT FOCUS" to "IME text=a😀éz select=4:4 composing=-1:-1",
            "RESTORE TEXT FOCUS" to "IME text=a😀éz select=4:4 composing=-1:-1",
            "COMPOSE THEN LEAVE" to "IME text=a😀éé拼z select=5:5 composing=4:6",
            "FOCUS PASSWORD" to "Password length=15",
            // Android closes the old InputConnection, finishing its
            // composition while retaining text and cursor position.
            "FOCUS ORIGINAL EDITOR" to "IME text=a😀éé拼z select=5:5 composing=-1:-1",
            "INVALIDATE EDITOR" to "IME text=a😀éé拼z select=5:5 composing=-1:-1",
            "COMPOSE UNICODE" to "IME text=a😀ééé拼拼z select=6:6 composing=5:7",
            "COMMIT UNICODE" to "IME text=a😀éé文拼z select=6:6 composing=-1:-1",
        )) {
            click(action)
            expectVisible(expected)
        }
        val editor = By.desc("IME Unicode editor")
        WaylandSeat.tapThenKey(waitFor(editor).visibleCenter, "Enter")
        expectVisible("Editor submitted: 1")
        click("REMOVE IME PROTOCOL")
        // Without the protocol the seat's keyboard types directly. The tap lands
        // after this short text, placing the caret at its end.
        WaylandSeat.tapThenType(waitFor(editor).visibleCenter, "X")
        waitFor(By.desc("IME Unicode editor").text("a😀éé文拼zX"))
        click("RESTORE IME PROTOCOL")
        click("COMMIT UNICODE")
        expectVisible("IME text=a😀éé文拼zX文 select=10:10 composing=-1:-1")
        HostSession.awaitLog("task text input available=false")
        HostSession.awaitLog("task text input available=true")
        assertEquals("a😀éé文拼zX文", waitFor(editor).text)
    }
}
