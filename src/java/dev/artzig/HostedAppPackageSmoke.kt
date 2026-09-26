package dev.artzig

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Import contracts observed through the real Android parser and signed APKs. */
object HostedAppPackageSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val (envelope, base, split, wrongSigner, otherPackage) = args.map(::File)
        val storage = Files.createTempDirectory("xapk-contract-").toFile()
        try {
            val code = HostedAppPackage.importXapk(envelope.path, storage)
            val lite = HostedAppPackage.parse(code)
            check(lite.packageName == "dev.artzig.fixtures.xapk")
            check(lite.splitNames.contentEquals(arrayOf("feature")))
            check(lite.allApkPaths.size == 2)
            check(File(lite.baseApkPath).readBytes().contentEquals(base.readBytes()))
            check(File(checkNotNull(lite.splitApkPaths)[0]).readBytes().contentEquals(split.readBytes()))
            check(HostedAppPackage.importXapk(envelope.path, storage) == code)
            val goodGenerations = File(storage, "packages/xapk").list()!!.toSet()
            fun archive(name: String, files: List<Pair<String, ByteArray>>): File {
                val target = File(storage, "$name.xapk")
                ZipOutputStream(target.outputStream()).use { zip ->
                    for ((path, bytes) in files) {
                        zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
                    }
                }
                return target
            }
            fun rejects(name: String, files: List<Pair<String, ByteArray>>, message: String) {
                val source = archive(name, files)
                val failure = runCatching { HostedAppPackage.importXapk(source.path, storage) }.exceptionOrNull()
                check(failure != null && failure.message.orEmpty().contains(message, ignoreCase = true)) {
                    "$name: expected '$message', got $failure"
                }
                check(File(storage, "packages/xapk").list()!!.toSet() == goodGenerations) { "$name left a partial import" }
            }
            val baseEntry = "base.apk" to base.readBytes()
            val splitEntry = "feature.apk" to split.readBytes()
            rejects("traversal", listOf("../escape.apk" to base.readBytes()), "Unsafe")
            rejects("absolute", listOf("/escape.apk" to base.readBytes()), "Unsafe")
            rejects("backslash", listOf("..\\escape.apk" to base.readBytes()), "Unsafe")
            rejects("missing-base", listOf(splitEntry), "Missing base")
            rejects("missing-split", listOf(baseEntry), "Missing split")
            rejects("duplicate-split", listOf(baseEntry, splitEntry, "again.apk" to split.readBytes()), "defined more than once")
            rejects("different-package", listOf(baseEntry, "other.apk" to otherPackage.readBytes()), "Inconsistent package")
            rejects("different-signer", listOf(baseEntry, "feature.apk" to wrongSigner.readBytes()), "verify")
            rejects("corrupt", listOf("base.apk" to byteArrayOf(1, 2, 3)), "parse")
            rejects("foreign-obb", listOf(baseEntry, splitEntry, "Android/obb/other/main.1.other.obb" to byteArrayOf(0)), "another package")
            rejects("nested-obb", listOf(baseEntry, splitEntry, "Android/obb/dev.artzig.fixtures.xapk/nested/file.obb" to byteArrayOf(0)), "Invalid XAPK OBB")
            rejects("unexpected", listOf(baseEntry, splitEntry, "payload.exe" to byteArrayOf(0)), "Unsupported")
            val single = archive("monolithic", listOf("app.apk" to otherPackage.readBytes()))
            check(HostedAppPackage.parse(HostedAppPackage.importXapk(single.path, storage)).splitNames.isNullOrEmpty())
            println("HostedAppPackageSmoke: signed split and monolithic imports, repeat import and 12 rejection contracts passed")
        } finally { storage.deleteRecursively() }
    }
}
