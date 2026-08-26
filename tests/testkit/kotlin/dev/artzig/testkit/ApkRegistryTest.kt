package dev.artzig.testkit

import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApkRegistryTest {
    @Test
    fun resolvesConfiguredApkAndChecksDigest() {
        val directory = Files.createTempDirectory("apk-registry-test")
        try {
            val apk = directory.resolve("fixture.apk")
            Files.writeString(apk, "fixture")
            val digest = MessageDigest.getInstance("SHA-256").digest("fixture".toByteArray())
                .joinToString("") { "%02x".format(it) }
            val registryFile = directory.resolve("apks.json")
            Files.writeString(registryFile, """
                {"fixture":{"environment":"FIXTURE_APK","sha256":"$digest",
                "package":"org.example","versionCode":1,"versionName":"1"}}
            """.trimIndent())
            val resolved = ApkRegistry.load(registryFile).resolve(
                "fixture",
                mapOf("FIXTURE_APK" to apk.toString()),
            )
            assertEquals(apk.toAbsolutePath(), resolved.path)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun missingEnvironmentFailsSelectedTest() {
        val file = Files.createTempFile("apk-registry", ".json")
        try {
            Files.writeString(file, """
                {"fixture":{"environment":"FIXTURE_APK","sha256":"00",
                "package":"org.example","versionCode":1,"versionName":"1"}}
            """.trimIndent())
            val error = assertFailsWith<AssertionError> {
                ApkRegistry.load(file).resolve("fixture", emptyMap())
            }
            assertEquals("FIXTURE_APK must point to APK 'fixture'", error.message)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun unreadablePathAndChecksumMismatchFailClearly() {
        val directory = Files.createTempDirectory("apk-registry-invalid")
        try {
            val apk = directory.resolve("fixture.apk")
            Files.writeString(apk, "fixture")
            val registryFile = directory.resolve("apks.json")
            Files.writeString(registryFile, """
                {"fixture":{"environment":"FIXTURE_APK","sha256":"00",
                "package":"org.example","versionCode":1,"versionName":"1"}}
            """.trimIndent())
            val registry = ApkRegistry.load(registryFile)
            val unreadable = assertFailsWith<AssertionError> {
                registry.resolve("fixture", mapOf("FIXTURE_APK" to directory.resolve("missing.apk").toString()))
            }
            assertTrue(unreadable.message!!.contains("not a readable file"))
            val mismatch = assertFailsWith<AssertionError> {
                registry.resolve("fixture", mapOf("FIXTURE_APK" to apk.toString()))
            }
            assertTrue(mismatch.message!!.contains("checksum mismatch"))
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
