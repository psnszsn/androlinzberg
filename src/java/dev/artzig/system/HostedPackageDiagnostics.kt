package dev.artzig.system

import android.os.Environment
import android.os.FileUtils
import android.util.Slog
import com.android.internal.util.FastPrintWriter
import com.android.server.EventLogTags
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date

/** Pinned PackageManagerService.reportSettingsProblem / PackageManagerServiceUtils.logCriticalInfo.
 * Full PMS utilities depend on installation/scanning infrastructure absent from this host.
 * Keep the diagnostic operation at the package authority: Android log, binary event,
 * append-only settings journal and original permissions/failure behavior. */
object HostedPackageDiagnostics {
    @JvmStatic fun reportSettingsProblem(priority: Int, message: String) {
        Slog.println(priority, "PackageManager", message)
        EventLogTags.writePmCriticalInfo(message)
        try {
            val file = File(Environment.getDataDirectory(), "system/uiderrors.txt")
            val writer = FastPrintWriter(FileOutputStream(file, true))
            writer.println(SimpleDateFormat().format(Date(System.currentTimeMillis())) + ": " + message)
            writer.close()
            FileUtils.setPermissions(file.toString(),
                FileUtils.S_IRWXU or FileUtils.S_IRWXG or FileUtils.S_IROTH, -1, -1)
        } catch (_: IOException) {
            // AOSP retains the log/event when the settings journal is unavailable.
        }
    }
}
