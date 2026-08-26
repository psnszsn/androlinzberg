package dev.artzig.testkit

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

data class ApkDefinition(
    val environment: String,
    val sha256: String,
    val `package`: String,
    val versionCode: Int,
    val versionName: String,
)

data class ResolvedApk(val key: String, val definition: ApkDefinition, val path: Path)

class ApkRegistry private constructor(private val definitions: Map<String, ApkDefinition>) {
    fun resolve(key: String, environment: Map<String, String> = System.getenv()): ResolvedApk {
        val definition = definitions[key]
            ?: throw AssertionError("APK '$key' is not registered")
        val configured = environment[definition.environment]
            ?: throw AssertionError("${definition.environment} must point to APK '$key'")
        val path = Path.of(configured).toAbsolutePath().normalize()
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw AssertionError("APK '$key' is not a readable file: $path")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(definition.sha256, ignoreCase = true)) {
            throw AssertionError("APK '$key' checksum mismatch: expected ${definition.sha256}, got $actual")
        }
        return ResolvedApk(key, definition, path)
    }

    companion object {
        fun load(path: Path): ApkRegistry {
            if (!Files.isRegularFile(path)) throw AssertionError("APK registry not found: $path")
            val type = object : TypeToken<Map<String, ApkDefinition>>() {}.type
            val definitions: Map<String, ApkDefinition> = Files.newBufferedReader(path).use {
                Gson().fromJson(it, type)
            }
            return ApkRegistry(definitions)
        }
    }
}
