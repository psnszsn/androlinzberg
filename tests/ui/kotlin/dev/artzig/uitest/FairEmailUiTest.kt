package dev.artzig.uitest

import androidx.test.uiautomator.By
import org.junit.Test

/** The original, signed FairEmail 1.2338a GitHub release. */
class FairEmailUiTest {
    @Test
    fun launchesWelcomeWithoutRuntimeIssues() = capture {
        UiApp.expectVisible("Welcome")
        UiApp.expectVisible("I agree")
    }

    @Test
    fun opensAccountSetupWithoutRuntimeIssues() = capture {
        openAccountSetup("mail-test@example.com")
    }

    @Test
    fun checksUnknownProviderWithoutResolverTransportErrors() = capture {
        // .invalid never reaches a mail server; this tests discovery without
        // credentials or an authentication attempt against someone's account.
        openAccountSetup("mail-test@androlinzberg.invalid")
        UiApp.waitFor(By.desc("Password").clazz("android.widget.EditText")).text = "unused-test-password"
        UiApp.click(By.res("eu.faircode.email:id/btnCheck"))
        val failure = UiApp.waitFor(By.res("eu.faircode.email:id/tvError"), 90_000)
        check(failure.text.contains("androlinzberg.invalid")) { failure.text }
        UiApp.waitFor(By.res("eu.faircode.email:id/btnManual"))
        UiApp.assertNoLog("resNetworkSend failed")
        UiApp.assertNoLog("resNetworkQuery failed")
        UiApp.assertNoLog("resNetworkResult failed")
    }

    @Test
    fun synchronizesAnImapInbox() = capture {
        val mailbox = createMailbox()
        openAccountSetup(mailbox.email)
        UiApp.waitFor(By.desc("Password").clazz("android.widget.EditText")).text = mailbox.password
        UiApp.click(By.res("eu.faircode.email:id/btnCheck"))
        UiApp.waitFor(By.res("eu.faircode.email:id/tvImap").text("${mailbox.imapHost}:993 ssl/tls 50"), 90_000)
        val run = System.currentTimeMillis()
        val messages = listOf(
            "Ada Lovelace <ada@example.org>" to "Notes on the analytical engine",
            "Grace Hopper <grace@example.org>" to "Found a moth in relay 70",
            "Linus Torvalds <linus@example.org>" to "Re: hosted Android on Linux",
            "Release bot <release@example.org>" to "androlinzberg sync $run",
        )
        messages.forEachIndexed { index, (from, subject) ->
            appendToInbox(mailbox, from, subject, java.time.ZonedDateTime.now().minusMinutes(10L * (messages.size - 1 - index)))
        }
        UiApp.click(By.res("eu.faircode.email:id/btnSave"))
        UiApp.expectVisible("An account has successfully been added")
        UiApp.click("OK")
        UiApp.click(By.desc("Close settings"))
        // Permissions and battery optimizations remain in the setup checklist.
        UiApp.expectVisible("Still to do")
        UiApp.click("DISMISS")
        // The message list opens with FairEmail's first-run notes.
        UiApp.expectVisible("Important")
        UiApp.click("OK")
        UiApp.waitFor(By.res("eu.faircode.email:id/tvSubject").text(messages.last().second), 120_000)
        for ((_, subject) in messages) UiApp.waitFor(By.res("eu.faircode.email:id/tvSubject").text(subject))
    }

    private class Mailbox(val email: String, val password: String, val imapHost: String)

    /** A new disposable mailbox from https://ethereal.email (Nodemailer's createTestAccount). */
    private fun createMailbox(): Mailbox {
        val connection = java.net.URL("https://api.nodemailer.com/user").openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write("""{"requestor":"androlinzberg-ui-test","version":"1.0.0"}""".toByteArray()) }
        val reply = org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        check(reply.getString("status") == "success") { reply.toString() }
        val imap = reply.getJSONObject("imap")
        check(imap.getInt("port") == 993 && imap.getBoolean("secure")) { imap.toString() }
        return Mailbox(reply.getString("user"), reply.getString("pass"), imap.getString("host"))
    }

    /** Stores one message with IMAP APPEND (RFC 3501 6.3.11) over implicit TLS. */
    private fun appendToInbox(mailbox: Mailbox, from: String, subject: String, sent: java.time.ZonedDateTime) {
        val email = mailbox.email
        val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(sent)
        val id = "<${java.util.UUID.randomUUID()}@androlinzberg.invalid>"
        val message = ("From: $from\r\nTo: $email\r\nDate: $date\r\nMessage-ID: $id\r\n" +
            "Subject: $subject\r\n\r\nSynchronization test\r\n")
            .toByteArray(Charsets.US_ASCII)
        javax.net.ssl.SSLSocketFactory.getDefault().createSocket(mailbox.imapHost, 993).use { socket ->
            val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            val output = socket.getOutputStream()
            fun send(line: String) = output.write("$line\r\n".toByteArray(Charsets.US_ASCII)).also { output.flush() }
            fun await(prefix: String): String {
                while (true) {
                    val line = checkNotNull(input.readLine()) { "IMAP closed before $prefix" }
                    if (line.startsWith(prefix)) return line
                }
            }
            check(await("* ").startsWith("* OK"))
            send("a LOGIN \"$email\" \"${mailbox.password}\"")
            check(await("a ").startsWith("a OK")) { "IMAP LOGIN failed" }
            send("b APPEND INBOX {${message.size}}")
            await("+")
            output.write(message)
            send("")
            check(await("b ").startsWith("b OK")) { "IMAP APPEND failed" }
            send("c LOGOUT")
        }
    }

    private fun openAccountSetup(email: String) {
        UiApp.click("I agree")
        UiApp.expectVisible("Add or change accounts")
        UiApp.waitUntilGone(By.res("eu.faircode.email:id/tvNoInternet"))
        UiApp.click("Wizard")
        // PopupMenuLifecycle prefixes each title with an icon span and spacing.
        UiApp.click(By.textEndsWith("Other provider"))
        UiApp.waitFor(By.res("eu.faircode.email:id/etName")).text = "Mail test"
        UiApp.waitFor(By.res("eu.faircode.email:id/etEmail")).text = email
        check(UiApp.waitFor(By.res("eu.faircode.email:id/etName")).text == "Mail test")
        check(UiApp.waitFor(By.res("eu.faircode.email:id/etEmail")).text == email)
        UiApp.waitFor(By.res("eu.faircode.email:id/btnCheck"))
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
                println("FairEmailUiTest: could not capture UI: $failure")
            }
        }
    }
}
