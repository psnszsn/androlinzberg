@file:Suppress("DEPRECATION")

package dev.artzig

import android.app.ActivityThread
import android.app.IUiAutomationConnection
import android.app.Instrumentation
import android.app.UiAutomation
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.VintfObject
import java.lang.reflect.Proxy

/** Original TestApi JNI consumes the shell output supplied by instrumentation. */
internal object StockVintfSmoke {
    fun run() {
        val thread = ActivityThread.currentActivityThread()
        val requested = mutableListOf<String>()
        val output = linkedMapOf(
            "vintf dm" to "",
            "vintf fm" to "",
            "vintf dcm" to "",
            "vintf fcm" to "",
        )
        val connection = Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(IUiAutomationConnection::class.java)) { _, method, args ->
            check(method.name == "executeShellCommand") { method.name }
            val command = args!![0] as String
            requested += command
            val sink = args[1] as ParcelFileDescriptor
            ParcelFileDescriptor.AutoCloseOutputStream(
                ParcelFileDescriptor.dup(sink.fileDescriptor)).use {
                it.write(output.getValue(command).toByteArray(Charsets.UTF_8))
            }
            null
        } as IUiAutomationConnection
        val automation = UiAutomation(Looper.getMainLooper(), connection)
        val field = ActivityThread::class.java.getDeclaredField("mInstrumentation")
            .apply { isAccessible = true }
        val original = field.get(thread)
        field.set(thread, object : Instrumentation() {
            override fun getUiAutomation(): UiAutomation = automation
        })
        try {
            check(VintfObject.report().isEmpty())
            check(requested == output.keys.toList())
            check(VintfObject.getHalNamesAndVersions().isEmpty())
            check(VintfObject.getSepolicyVersion() == null)
            check(VintfObject.getVndkSnapshots() == null)
            check(VintfObject.getTargetFrameworkCompatibilityMatrixVersion() == null)
            val missing = runCatching { VintfObject.getPlatformSepolicyVersion() }.exceptionOrNull()
            check(missing is RuntimeException && missing.message == "Cannot get framework compatibility matrix")

            output["vintf dm"] = """
                <manifest version="1.0" type="device" target-level="8">
                  <hal format="hidl"><name>android.hardware.tests.vintf</name>
                    <transport>hwbinder</transport><version>1.0</version>
                    <interface><name>ITest</name><instance>default</instance></interface>
                  </hal><sepolicy><version>30.0</version></sepolicy>
                </manifest>
            """.trimIndent()
            output["vintf fm"] = """
                <manifest version="1.0" type="framework">
                  <vendor-ndk><version>29</version><library>libvintf_fixture.so</library></vendor-ndk>
                </manifest>
            """.trimIndent()
            output["vintf dcm"] = "<compatibility-matrix version=\"1.0\" type=\"device\"/>"
            output["vintf fcm"] = """
                <compatibility-matrix version="1.0" type="framework" level="8">
                  <sepolicy><kernel-sepolicy-version>30</kernel-sepolicy-version>
                    <sepolicy-version>30.0-2</sepolicy-version></sepolicy>
                </compatibility-matrix>
            """.trimIndent()
            check(VintfObject.report().size == 4)
            check(VintfObject.getHalNamesAndVersions().contentEquals(
                arrayOf("android.hardware.tests.vintf@1.0")))
            check(VintfObject.getSepolicyVersion() == "30.0")
            check(VintfObject.getPlatformSepolicyVersion() == "30.2")
            check(VintfObject.getTargetFrameworkCompatibilityMatrixVersion() == 8L)
            check(VintfObject.getVndkSnapshots().getValue("29").contentEquals(arrayOf("libvintf_fixture.so")))
        } finally {
            field.set(thread, original)
        }
        // This query deliberately uses the actual host filesystem, not the TestApi shell fixture.
        check(VintfObject.verifyBuildAtBoot() == -android.system.OsConstants.ENODEV)
        System.err.println("stock-vintf: original JNI shell parsing, missing manifests and compatibility failure passed")
    }
}
