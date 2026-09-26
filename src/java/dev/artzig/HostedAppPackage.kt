@file:Suppress("DEPRECATION")
package dev.artzig

import android.content.pm.parsing.ApkLiteParseUtils
import android.content.pm.parsing.PackageLite
import android.content.pm.parsing.result.ParseInput
import android.content.pm.parsing.result.ParseTypeImpl
import android.os.Environment
import com.android.internal.pm.pkg.parsing.ParsingPackageUtils
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Host distribution envelope. Android's pinned ApkLiteParseUtils and
 * ParsingPackageUtils own cluster ordering, dependencies and APK signatures;
 * XAPK itself is not an Android package and has no AOSP installer to reuse. */
object HostedAppPackage {
    private const val MAX_ENTRIES = 10000
    private const val MAX_BYTES = 16L * 1024 * 1024 * 1024

    fun parse(path: String): PackageLite {
        // Also used in the metadata VM, before Binder identity is established.
        val input = ParseTypeImpl(ParseInput.Callback { _, _, _ -> true })
        val result = ApkLiteParseUtils.parsePackageLite(input, File(path), 0)
        require(!result.isError) { "Cannot parse package $path: ${result.errorMessage}" }
        val lite = result.result
        // PackageInstallerSession.validateApkInstallLocked (full install): the
        // hosted installer has no staging sessions/installd, so enforce these
        // session-level checks here around the original lite parser.
        val required = HashSet<String>()
        val provided = HashSet<String>()
        for (apk in lite.allApkPaths) {
            val part = ApkLiteParseUtils.parseApkLite(input.reset(), File(apk), 0)
            require(!part.isError) { "Cannot parse APK $apk: ${part.errorMessage}" }
            require(part.result.longVersionCode == lite.longVersionCode) { "Inconsistent version in $apk" }
            required.addAll(part.result.requiredSplitTypes.orEmpty())
            provided.addAll(part.result.splitTypes.orEmpty())
        }
        if (lite.isSplitRequired && (lite.splitNames.isNullOrEmpty() || !provided.containsAll(required))) {
            throw com.android.server.pm.PackageManagerException(
                android.content.pm.PackageManager.INSTALL_FAILED_MISSING_SPLIT,
                "Missing split for ${lite.packageName}")
        }
        return lite
    }

    @JvmStatic fun importXapk(path: String): String =
        importXapk(path, File(checkNotNull(System.getenv("ARTZIG_DATA_DIR"))))

    internal fun importXapk(path: String, storage: File): String {
        val archive = File(path).canonicalFile
        require(archive.isFile) { "No XAPK at $path" }
        val root = File(storage, "packages/xapk").canonicalFile
        Files.createDirectories(root.toPath())
        // Hash and extract the same snapshot, even if the download is replaced
        // while importing. Never publish partially extracted code.
        val snapshot = Files.createTempFile(root.toPath(), ".archive-", ".xapk").toFile()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            archive.inputStream().use { source ->
                copyBounded(source, snapshot) { bytes, count -> digest.update(bytes, 0, count) }
            }
            val key = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val destination = File(root, key)
            // Concurrent importers can reuse the winner of the atomic rename.
            if (!destination.exists()) {
                val staging = Files.createTempDirectory(root.toPath(), ".import-").toFile()
                try {
                    unpack(snapshot, staging)
                    val lite = parse(File(staging, "apks").path)
                    verify(lite)
                    validateExpansionFiles(staging, lite.packageName)
                    try {
                        Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    } catch (failure: java.io.IOException) {
                        if (!destination.isDirectory) throw failure
                    }
                } finally { staging.deleteRecursively() }
            }
            val code = File(destination, "apks").canonicalPath
            val lite = parse(code)
            verify(lite)
            validateExpansionFiles(destination, lite.packageName)
            return code
        } finally { snapshot.delete() }
    }

    private fun verify(lite: PackageLite) {
        val input = ParseTypeImpl(ParseInput.Callback { _, _, _ -> true })
        val signing = ParsingPackageUtils.getSigningDetails(input, lite.baseApkPath,
            lite.isIsStaticLibrary, lite.targetSdk, lite.splitApkPaths, false)
        require(!signing.isError) { "Cannot verify XAPK: ${signing.errorMessage}" }
    }

    private fun unpack(archive: File, staging: File) {
        val apks = File(staging, "apks").apply { mkdir() }
        val names = HashSet<String>()
        var total = 0L
        var count = 0
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(++count <= MAX_ENTRIES) { "Too many XAPK entries" }
                val name = entry.name.removeSuffix("/")
                require(name.isNotEmpty() && !name.contains('\\') && !name.contains('\u0000') &&
                    name.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
                    "Unsafe XAPK entry: ${entry.name}"
                }
                require(names.add(name)) { "Duplicate XAPK entry: $name" }
                if (entry.isDirectory) continue
                val output = when {
                    name.endsWith(".apk") -> File(apks, "$count.apk")
                    name.startsWith("Android/obb/") && name.endsWith(".obb") -> File(staging, name)
                    name == "manifest.json" || name == "icon.png" -> null
                    else -> throw IllegalArgumentException("Unsupported XAPK entry: $name")
                }
                require(entry.size in 0..MAX_BYTES && total <= MAX_BYTES - entry.size) {
                    "XAPK exceeds extraction limit"
                }
                output?.parentFile?.mkdirs()
                zip.getInputStream(entry).use { source ->
                    val crc = java.util.zip.CRC32()
                    val written = copyBounded(source, output) { bytes, size ->
                        crc.update(bytes, 0, size)
                        total += size
                        require(total <= MAX_BYTES) { "XAPK exceeds extraction limit" }
                    }
                    require(written == entry.size && crc.value == entry.crc) { "Corrupt XAPK entry: $name" }
                }
            }
        }
    }

    private fun copyBounded(source: InputStream, output: File?, observe: (ByteArray, Int) -> Unit): Long {
        var total = 0L
        val target = output?.outputStream()
        try {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val size = source.read(buffer)
                if (size == -1) break
                total += size
                require(total <= MAX_BYTES) { "XAPK exceeds size limit" }
                observe(buffer, size)
                target?.write(buffer, 0, size)
            }
        } finally { target?.close() }
        return total
    }

    private fun validateExpansionFiles(root: File, packageName: String) {
        val obbs = File(root, "Android/obb")
        for (owner in obbs.listFiles().orEmpty()) {
            require(owner.name == packageName && owner.isDirectory) { "XAPK OBB belongs to another package: ${owner.name}" }
            for (file in owner.listFiles().orEmpty()) {
                require(file.isFile && file.name.endsWith(".obb")) { "Invalid XAPK OBB: $file" }
            }
        }
    }

    /** Called only after the session installer has accepted the signed package.
     * Copying expansion data supports apps which read OBB files themselves;
     * StorageManager.mountObb retains its existing unsupported mount contract. */
    fun installExpansionFiles(codePath: String, packageName: String) {
        val code = File(codePath).canonicalFile
        val root = File(checkNotNull(System.getenv("ARTZIG_DATA_DIR")), "packages/xapk").canonicalFile
        if (code.name != "apks" || code.parentFile?.parentFile != root) return
        validateExpansionFiles(code.parentFile, packageName)
        val source = File(code.parentFile, "Android/obb/$packageName")
        if (!source.isDirectory) return
        var target = Environment.getExternalStorageDirectory().canonicalFile
        for (part in arrayOf("Android", "obb", packageName)) {
            target = File(target, part)
            try { Files.createDirectory(target.toPath()) }
            catch (_: java.nio.file.FileAlreadyExistsException) { }
            require(Files.isDirectory(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                "Unsafe OBB destination: $target"
            }
        }
        for (file in source.listFiles().orEmpty()) {
            val temporary = Files.createTempFile(target.toPath(), ".import-", ".obb")
            try {
                Files.copy(file.toPath(), temporary, StandardCopyOption.REPLACE_EXISTING)
                Files.move(temporary, File(target, file.name).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }
    }
}
