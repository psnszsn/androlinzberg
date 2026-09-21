package dev.artzig.uitest.host

import android.os.SystemClock
import androidx.test.uiautomator.By
import dev.artzig.uitest.UiApp.click
import dev.artzig.uitest.UiApp.expectVisible
import dev.artzig.uitest.UiApp.waitFor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.regex.Pattern

/**
 * Android's clipboard against the desktop selection, in a desktop (labwc)
 * session: text, HTML and file representations in both directions, clearing,
 * removal and restoration of the data-device protocol, and URI grants.
 * wl-copy and wl-paste are the native peer.
 */
class DesktopClipboardUiTest {
    @Test
    fun representationsCrossTheDesktopSelectionInBothDirections() {
        click(By.text(Pattern.compile("show clipboard", Pattern.CASE_INSENSITIVE)))
        expectVisible("Clipboard fixture ready")

        copy("COPY UNICODE TEXT", "Clipboard text=Android 😀 café html=none")
        assertNative(PLAIN, "Android 😀 café")
        copy("COPY HTML", "Clipboard text=Android bold 😀 html=<b>Android bold 😀</b>")
        assertNative(PLAIN, "Android bold 😀")
        assertNative(HTML, "<b>Android bold 😀</b>")
        copy("COPY RAPID SEQUENCE", "Clipboard text=Android final 😀 html=<b>Android final 😀</b>", wayland = true)
        assertNative(PLAIN, "Android final 😀")
        assertNative(HTML, "<b>Android final 😀</b>")
        // An own-source echo must not rebuild ClipData and discard metadata,
        // even after a burst of copy and clear publications.
        copy("READ CLIPBOARD", "Clipboard text=Android final 😀 html=<b>Android final 😀</b>", wayland = true)
        expectVisible("Clipboard label=Final sequence metadata")

        nativeCopy(PLAIN, "Native 😀 café", "Clipboard text=Native 😀 café html=none")
        copy("READ CLIPBOARD", "Clipboard text=Native 😀 café html=none")
        nativeCopy(HTML, "<b>Native rich 😀</b>", "Clipboard text=<b>Native rich 😀</b> html=<b>Native rich 😀</b>")
        // wl-copy advertises text aliases containing the same bytes, so its
        // explicit plain representation is retained alongside HTML.
        assertNative(PLAIN, "<b>Native rich 😀</b>")
        copy("CLEAR CLIPBOARD", "Clipboard is empty")
        nativeCopy("text/plain", "After clear", "Clipboard text=After clear html=none")
        HostSession.run("wl-copy", "--clear", capture = false)
        expectVisible("Clipboard is empty")

        click("REMOVE CLIPBOARD PROTOCOL")
        HostSession.awaitLog("task clipboard available=false")
        copy("COPY HTML", "Clipboard text=Android bold 😀 html=<b>Android bold 😀</b>")
        click("RESTORE CLIPBOARD PROTOCOL")
        HostSession.awaitLog("task clipboard available=true")
        assertNative(PLAIN, "Android bold 😀")
        assertNative(HTML, "<b>Android bold 😀</b>")
        nativeCopy(PLAIN, "After recreation", "Clipboard text=After recreation html=none")

        copy("COPY FILE", "Clipboard files=Android file 😀 café")
        // A programmatic copy can carry an obsolete selection serial. Real
        // input must republish it with a fresh source and serial.
        copy("READ CLIPBOARD", "Clipboard files=Android file 😀 café", wayland = true)
        // Staging is asynchronous: read the URI list once its type is offered.
        val deadline = SystemClock.uptimeMillis() + 10_000
        while ("text/uri-list" !in String(HostSession.run("wl-paste", "--list-types"))) {
            check(SystemClock.uptimeMillis() < deadline) { "Android's URI export was never published" }
            SystemClock.sleep(20)
        }
        val exported = native("text/uri-list").lines().filter(String::isNotBlank).map { File(URI(it.trim())) }
        assertEquals(1, exported.size)
        assertEquals("Android café.txt", exported[0].name)
        assertEquals("Android file 😀 café", exported[0].readText())

        val files = listOf("Native café.txt" to "Native file 😀 café", "Native second.txt" to "Second native file")
            .map { (name, content) -> File(HostSession.artifacts, name).apply { writeText(content) } }
        val uris = files.joinToString("") { URI("file", "", it.absolutePath, null).toASCIIString() + "\r\n" }
        nativeCopy("text/uri-list", uris, "Clipboard files=Native file 😀 café|Second native file")
        copy("CHECK FILE PERMISSIONS", "Clipboard read grant only; control denied")
        copy("CLEAR CLIPBOARD", "Clipboard is empty")
        copy("CHECK REVOKED FILE", "Clipboard file grant revoked")
        // A native recipient may open the export after ownership changed;
        // immutable export leases survive for the session.
        assertEquals("Android file 😀 café", exported[0].readText())
    }

    private fun copy(button: String, expected: String, wayland: Boolean = false) {
        if (wayland) WaylandSeat.tap(waitFor(By.text(button)).visibleCenter) else click(button)
        expectVisible(expected)
    }

    private fun native(mime: String) = String(HostSession.run("wl-paste", "--no-newline", "--type", mime))

    private fun assertNative(mime: String, expected: String) = assertEquals(mime, expected, native(mime))

    private fun nativeCopy(mime: String, content: String, expected: String) {
        HostSession.run("wl-copy", "--type", mime, content, capture = false)
        expectVisible(expected)
    }

    private companion object {
        const val PLAIN = "text/plain;charset=utf-8"
        const val HTML = "text/html"
    }
}
