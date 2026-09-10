package dev.artzig

import com.android.internal.os.ClassLoaderFactory
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile
import dalvik.system.DelegateLastClassLoader
import dalvik.system.PathClassLoader
import java.util.zip.ZipFile

internal object KotlinClassLoaderSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        check(args.size == 3) { "expected fixture, implementation classes and API projection paths" }
        val fixturePath = java.io.File(args[0]).canonicalPath
        val platform = ClassLoader.getSystemClassLoader().parent
        check(platform is HostedPlatformClassLoader)
        val context = "PCL[];PCL[${System.getenv("ARTZIG_FRAMEWORK_JAR")}]"
        check(DexFile.getDexOptNeeded(fixturePath, "x86_64", "speed", context, false, false) ==
            DexFile.NO_DEXOPT_NEEDED) { "Compiled APK fixture is not fresh for the runtime archive" }
        val loader = create(fixturePath, platform)
        val pathList = BaseDexClassLoader::class.java.getDeclaredField("pathList").apply {
            isAccessible = true
        }.get(loader)
        val elements = pathList.javaClass.getDeclaredField("dexElements").apply {
            isAccessible = true
        }.get(pathList) as Array<*>
        val backedByOat = DexFile::class.java.getDeclaredMethod("isBackedByOatFile").apply {
            isAccessible = true
        }
        check(elements.any { element ->
            element ?: return@any false
            val dex = element.javaClass.getDeclaredField("dexFile").apply {
                isAccessible = true
            }.get(element) as DexFile?
            dex != null && backedByOat.invoke(dex) == true
        }) { "Stock APK loader did not map the compiled fixture artifact" }
        val sibling = create(fixturePath, platform)
        check(loader.javaClass === PathClassLoader::class.java)
        check(loader.loadClass("android.content.Context") === android.content.Context::class.java)
        check(loader.loadClass("java.lang.String") === String::class.java)
        val appCaller = loader.loadClass("fixture.NewerKotlinCaller")
        check(sibling.loadClass("fixture.NewerKotlinCaller") !== appCaller)
        check(create(fixturePath, loader).loadClass("fixture.NewerKotlinCaller") === appCaller)
        val delegateLast = create(fixturePath, loader, DelegateLastClassLoader::class.java.name)
        check(delegateLast.javaClass === DelegateLastClassLoader::class.java)
        check(delegateLast.loadClass("fixture.NewerKotlinCaller").classLoader === delegateLast)
        for (apkLoader in listOf(loader, delegateLast)) {
            check(apkLoader.loadClass("android.net.Uri") === android.net.Uri::class.java)
            check(apkLoader.loadClass("fixture.PlatformReference").getMethod("uriClass")
                .invoke(null) === android.net.Uri::class.java) {
                "APK dex type resolution replaced a platform class"
            }
        }
        check(Object::class.java.classLoader.loadClass("android.net.Uri") === android.net.Uri::class.java)
        expectMissing(Object::class.java.classLoader, "kotlin.Unit")

        val appKotlinVersion = loader.loadClass("kotlin.KotlinVersion")
        check(appKotlinVersion.classLoader === loader)
        check(appKotlinVersion.getMethod("appRuntimeMarker").invoke(null) == "app")
        check(appCaller.getMethod("callNewRuntimeApi").invoke(null) == "app")
        expectMissing(loader, "kotlin.Unit")
        expectMissing(platform, "kotlin.KotlinVersion")
        val shared = ClassLoaderFactory.createClassLoader(
            fixturePath, null, platform, null, null,
            listOf(KotlinClassLoaderSmoke::class.java.classLoader),
        )
        check(shared.loadClass("kotlin.KotlinVersion").classLoader === shared)
        check(shared.loadClass("kotlin.Unit") === Unit::class.java)

        // Compare actual compiler output against runtime policy, including class
        // bytes: the API projection must neither leak nor omit a source class.
        ZipFile(args[1]).use { implementation ->
            ZipFile(args[2]).use { api ->
                val expected = HashSet<String>()
                val entries = implementation.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.name.endsWith(".class")) continue
                    val exposed = HostedPlatformClassLoader.exposes(entry.name.replace('/', '.'))
                    val projected = api.getEntry(entry.name)
                    check((projected != null) == exposed) { "API visibility differs: ${entry.name}" }
                    if (exposed) {
                        expected.add(entry.name)
                        check(implementation.getInputStream(entry).use { it.readBytes() }.contentEquals(
                            api.getInputStream(projected).use { it.readBytes() },
                        )) { "API class bytes changed: ${entry.name}" }
                    }
                }
                check(api.size() == expected.size && expected.isNotEmpty())
            }
        }
        println("Kotlin classloader smoke: PASS")
    }

    private fun create(path: String, parent: ClassLoader, name: String? = null): ClassLoader =
        ClassLoaderFactory.createClassLoader(path, null, parent, name, null, null)

    private fun expectMissing(loader: ClassLoader, name: String) {
        try {
            loader.loadClass(name)
        } catch (_: ClassNotFoundException) {
            return
        }
        error("Private host dependency leaked into APK lookup: $name")
    }
}
