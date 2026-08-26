package dev.artzig

internal object KotlinClassLoaderSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        check(args.size == 1) { "expected the Kotlin shadow fixture path" }
        val loader = HostedApkClassLoader(
            args[0],
            null,
            ClassLoader.getSystemClassLoader(),
        )

        val appKotlinVersion = loader.loadClass("kotlin.KotlinVersion")
        check(appKotlinVersion.classLoader === loader) {
            "APK-bundled kotlin.KotlinVersion did not shadow the host stdlib"
        }
        check(appKotlinVersion.getMethod("appRuntimeMarker").invoke(null) == "app")

        val newerKotlinCaller = loader.loadClass("fixture.NewerKotlinCaller")
        check(newerKotlinCaller.getMethod("callNewRuntimeApi").invoke(null) == "app") {
            "APK code linked its newer Kotlin call against the host stdlib"
        }

        val hostUnit = loader.loadClass("kotlin.Unit")
        check(hostUnit.classLoader !== loader) {
            "Kotlin classes absent from the APK did not fall back to the host stdlib"
        }
        println("Kotlin classloader smoke: PASS")
    }
}
