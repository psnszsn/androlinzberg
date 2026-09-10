package dev.artzig

import android.os.Environment
import android.system.Os
import android.util.ArrayMap
import android.util.Xml
import com.android.server.pm.permission.LegacyPermission
import com.android.server.pm.permission.LegacyPermissionSettings
import java.io.ByteArrayInputStream
import java.io.File

/** Malformed original permission-settings input retains diagnostics and valid siblings. */
internal object StockPackageDiagnosticsSmoke {
    fun run() {
        val directory = File(Environment.getDataDirectory(), "system")
        check(directory.isDirectory)
        val journal = File(directory, "uiderrors.txt")
        val before = if (journal.exists()) journal.readText() else ""
        val marker = "stockSettings${System.nanoTime()}"
        fun read(xml: String): ArrayMap<String, LegacyPermission> {
            val result = ArrayMap<String, LegacyPermission>()
            ByteArrayInputStream(xml.toByteArray()).use { input ->
                val parser = Xml.resolvePullParser(input)
                parser.nextTag()
                LegacyPermissionSettings.readPermissions(result, parser)
            }
            return result
        }
        val permissions = read("<permissions><$marker/><item package=\"fixture\"/>" +
            "<item name=\"fixture.permission\" package=\"fixture\" protection=\"0\"/></permissions>")
        check(permissions.size == 1 && permissions.containsKey("fixture.permission"))
        val after = journal.readText()
        check(after.startsWith(before))
        val appended = after.substring(before.length)
        check(appended.contains("Unknown element reading permissions: $marker"))
        check(appended.contains("Error in package manager settings: permissions has no name"))
        check(Os.stat(journal.path).st_mode and 0x1ff == 0x1fc) // Original 0774.
        // Another parser call appends; it must not truncate earlier settings failures.
        read("<permissions><${marker}Again/></permissions>")
        check(journal.readText().startsWith(after))
        check(journal.readText().contains("Unknown element reading permissions: ${marker}Again"))
    }
}
