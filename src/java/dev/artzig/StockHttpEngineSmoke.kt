package dev.artzig

import android.app.ActivityThread
import android.content.Context
import android.net.http.DnsOptions
import android.net.http.HttpEngine
import android.net.http.HttpEngineJavaClasses
import java.lang.reflect.Modifier
import java.time.Duration
import java.time.Instant
import org.chromium.base.CommandLine
import org.chromium.base.ApiCompatibilityUtils
import org.chromium.net.impl.ImplVersion
import org.jni_zero.NativeLibraryLoadedStatus

/** Public Java configuration and linkage contracts, without starting a native HTTP engine. */
internal object StockHttpEngineSmoke {
    fun run() {
        check(ImplVersion.getApiLevel() == 34)
        check(ImplVersion.getCronetVersion() == "133.0.6876.3")
        val context: Context = ActivityThread.currentActivityThread().systemContext
        val builder = HttpEngine.Builder(context)
        check(builder.setUserAgent("fixture/1") === builder)
        check(builder.defaultUserAgent.contains("133.0.6876.3"))
        check(runCatching {
            builder.setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISK, 1024)
        }.exceptionOrNull()?.message == "Storage path must be set")
        check(runCatching { builder.setEnableHttpCache(999, 1024) }.exceptionOrNull() is IllegalArgumentException)
        val storage = java.nio.file.Files.createTempDirectory("artzig-httpengine-").toFile()
        try {
            check(builder.setStoragePath(storage.absolutePath) === builder)
            check(builder.setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISK, 1L shl 34) === builder)
            check(runCatching { builder.setStoragePath(java.io.File(storage, "missing").path) }
                .exceptionOrNull()?.message == "Storage path must be set to existing directory")
            val bitmap = android.graphics.Bitmap.createBitmap(2, 3, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.RED)
            val file = java.io.File(storage, "image.png")
            file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            val uri = android.net.Uri.fromFile(file)
            val decoded = checkNotNull(android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri))
            check(decoded.width == 2 && decoded.height == 3 && decoded.getPixel(0, 0) == android.graphics.Color.RED)
            decoded.recycle()
            val modern = ApiCompatibilityUtils.getBitmapByUri(context.contentResolver, uri)
            check(modern.width == 2 && modern.height == 3)
            modern.recycle()
            file.writeText("invalid image")
            check(android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri) == null)
            file.delete()
            check(runCatching { android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri) }
                .exceptionOrNull() is java.io.FileNotFoundException)
        } finally { storage.deleteRecursively() }
        check(runCatching {
            builder.addPublicKeyPins("example.com", setOf(ByteArray(31)), true, Instant.now())
        }.exceptionOrNull()?.message == "Public key pin is invalid")
        check(builder.addPublicKeyPins("example.com", setOf(ByteArray(32)), true,
            Instant.now().plusSeconds(60)) === builder)

        val dnsBuilder = DnsOptions.Builder().setPersistHostCachePeriod(Duration.ofSeconds(3))
        val first = dnsBuilder.build()
        dnsBuilder.setPersistHostCachePeriod(Duration.ofSeconds(9))
        check(first.persistHostCachePeriod == Duration.ofSeconds(3))
        check(dnsBuilder.build().persistHostCachePeriod == Duration.ofSeconds(9))
        check(builder.setDnsOptions(first) === builder)

        val preload = HttpEngineJavaClasses.ALL_CLASSES.toSet()
        check(preload.size == HttpEngineJavaClasses.ALL_CLASSES.size && preload.size > 300)
        check("org.chromium.net.impl.CronetUrlRequestContext" in preload)
        check("android.net.http.CronetEngineWrapper" in preload)
        check("com.google.protobuf.GeneratedMessageLite" in preload)
        check("org.chromium.base.ContextUtils\$Holder" !in preload)
        check("org.chromium.base.TraceEvent\$LooperMonitorHolder" !in preload)
        for (name in preload) Class.forName(name, false, javaClass.classLoader)
        val nativeBindings = Class.forName("J.N", false, javaClass.classLoader).declaredMethods
        check(nativeBindings.size > 90 && nativeBindings.all { Modifier.isNative(it.modifiers) })
        // The final registration must retain the test-suffixed methods present in AOSP's Java.
        check(Class.forName("org.jni_zero.GEN_JNI").declaredMethods.any {
            it.name == "org_chromium_base_metrics_NativeUmaRecorder_createHistogramSnapshotForTesting"
        })
        val previousProvider = NativeLibraryLoadedStatus.getProviderForTesting()
        try {
            NativeLibraryLoadedStatus.setProvider { false }
            check(runCatching { CommandLine.enableNativeProxy() }.exceptionOrNull() is
                NativeLibraryLoadedStatus.NativeNotLoadedException)
        } finally { NativeLibraryLoadedStatus.setProvider(previousProvider) }
        CommandLine.init(arrayOf("fixture", "--http-fixture=initial"))
        try {
            val commandLine = CommandLine.getInstance()
            check(commandLine.getSwitchValue("http-fixture") == "initial")
            commandLine.appendSwitchWithValue("http-fixture", "updated")
            check(commandLine.getSwitchValue("http-fixture") == "updated")
            commandLine.removeSwitch("http-fixture")
            check(!commandLine.hasSwitch("http-fixture"))
        } finally { CommandLine.resetForTesting(false) }
        println("stock HttpEngine Java configuration and preload contracts: PASS")
    }
}
