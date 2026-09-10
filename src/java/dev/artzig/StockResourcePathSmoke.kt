package dev.artzig

import android.content.res.ApkAssets
import android.content.res.AssetManager
import android.database.sqlite.SQLiteGlobal
import android.graphics.Paint
import android.graphics.fonts.SystemFonts
import android.os.Environment
import android.os.StatFs
import android.os.SystemProperties
import android.system.Os
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Original Java file/resource clients must observe the real configured files. */
internal object StockResourcePathSmoke {
    private fun digest(path: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(path).use { input ->
            val buffer = ByteArray(16384)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    fun run() {
        check(AssetManager.FRAMEWORK_APK_PATH == "/system/framework/framework-res.apk")
        check(SystemFonts.SYSTEM_FONT_DIR == "/system/fonts/")
        check(SystemFonts.LEGACY_FONTS_XML == "/system/etc/fonts.xml")
        val files = listOf(
            "/system/etc/font_fallback.xml" to checkNotNull(System.getenv("ARTZIG_FONT_FALLBACK_XML")),
            SystemFonts.LEGACY_FONTS_XML to checkNotNull(System.getenv("ARTZIG_FONTS_XML")),
            AssetManager.FRAMEWORK_APK_PATH to checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")),
        )
        for ((logical, physical) in files) {
            check(File(logical).isFile && File(logical).canRead())
            check(File(logical).length() == File(physical).length())
            check(Os.stat(logical).st_ino == Os.stat(physical).st_ino)
            check(Files.size(Path.of(logical)) == Files.size(Path.of(physical)))
            check(Files.getLastModifiedTime(Path.of(logical)) == Files.getLastModifiedTime(Path.of(physical)))
            check(digest(logical).contentEquals(digest(physical)))
        }
        check(Files.readAllBytes(Path.of(files[0].first)).contentEquals(File(files[0].second).readBytes()))
        check(!File("/system/etc/font_fallback.xml.unmapped").exists())
        check(!Files.exists(Path.of("/system/framework/framework-res.apk.unmapped")))

        val apk = ApkAssets.loadFromPath(AssetManager.FRAMEWORK_APK_PATH)
        try {
            check(apk.assetPath == AssetManager.FRAMEWORK_APK_PATH)
            check(apk.isUpToDate)
            apk.openXml("AndroidManifest.xml").use { xml ->
                while (xml.next() != org.xmlpull.v1.XmlPullParser.START_TAG) {
                    check(xml.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT)
                }
                check(xml.name == "manifest")
            }
        } finally {
            apk.close()
        }
        val fonts = SystemFonts.getSystemPreinstalledFontConfig().fontFamilies.flatMap { it.fontList }
        check(fonts.isNotEmpty())
        val font = fonts.first().file
        check(font.path.startsWith(SystemFonts.SYSTEM_FONT_DIR))
        check(FileInputStream(font).use { it.read() } >= 0)
        check(Files.size(font.toPath()) == font.length() && font.length() > 0)
        check(Paint().measureText("Stock resource paths") > 0)

        val expectedBlock = StatFs(Environment.getDataDirectory().path).blockSizeLong
        check(StatFs("/data").blockSizeLong == expectedBlock)
        check(SQLiteGlobal.getDefaultPageSize() ==
            SystemProperties.getInt("debug.sqlite.pagesize", expectedBlock.toInt()))
        println("stock-resource-paths: PASS original names, real bytes/metadata, APK identity, fonts and SQLite partition probe")
    }
}
