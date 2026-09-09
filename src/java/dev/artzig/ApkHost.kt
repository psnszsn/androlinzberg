// APK installation and process bootstrap around stock ActivityThread/ATMS.
@file:Suppress("DEPRECATION")
package dev.artzig

import android.content.res.AssetManager
import android.os.Looper
import android.util.Log
import java.io.File
import dalvik.system.DexFile

class ApkHost private constructor() {
    companion object {
        private const val TAG = "ApkHost"
        private var sPackageIdentity: String? = null

        /** AOSP DexFile/OatFileAssistant freshness check for the standard
         * dalvik-cache artifact that the host background compiler installs. */
        @JvmStatic public fun apkDexoptNeeded(): Boolean {
            val apkPath: String = System.getenv("ARTZIG_APK") ?: return false
            val frameworkJar: String = System.getenv("ARTZIG_FRAMEWORK_JAR") ?: return false
            val context: String = "PCL[];PCL[" + frameworkJar + "]"
            return try {
                DexFile.getDexOptNeeded(
                    apkPath,
                    "x86_64",
                    "speed",
                    context,
                    false,
                    false,
                ) != DexFile.NO_DEXOPT_NEEDED
            } catch (t: Throwable) {
                Log.w(TAG, "dexopt freshness check failed; compilation will be retried", t)
                true
            }
        }

        @JvmStatic public fun apkDexoptStatus(): String {
            val apkPath: String = System.getenv("ARTZIG_APK") ?: return "unavailable"
            return try {
                val info = DexFile.getDexFileOptimizationInfo(apkPath, "x86_64")
                info.status + "/" + info.reason
            } catch (t: Throwable) {
                "unavailable:" + t.javaClass.simpleName
            }
        }

        @JvmStatic public fun reportDexoptFailure() {
            dev.artzig.ArtzigTestDiagnostics.reportIssue(
                "dexopt_failure",
                "dev.artzig.ApkHost",
                "backgroundDexopt",
                "speed compilation failed; temporary artifacts were removed and a later launch will retry",
            )
        }

/** Extract the host ABI from the APK because the desktop/bionic bridge
         * cannot mmap Android's special "apk!/lib/..." classloader paths. */
        @Throws(Exception::class)
        @JvmStatic
        private fun extractNativeLibraries(apkPath: String): String {
            val dirPath: String? = System.getenv("ARTZIG_NATIVE_LIB_DIR")
            if (dirPath == null || dirPath.isEmpty()) {
                throw IllegalStateException("ARTZIG_NATIVE_LIB_DIR is not configured")
            }
            val dir: File = File(dirPath)
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw IllegalStateException("cannot create native library directory: " + dir)
            }
            // The host linker requires extracted x86_64 files even when an
            // Android device could load an uncompressed library from the APK.
            // NativeLibraryHelper owns ABI filtering, CRC/timestamp reuse,
            // permissions, temporary-file cleanup and atomic replacement.
            com.android.internal.content.NativeLibraryHelper.Handle.create(
                listOf(apkPath), false, true, false, false,
            ).use { handle ->
                val result = com.android.internal.content.NativeLibraryHelper.copyNativeBinariesForSupportedAbi(
                    handle, dir, arrayOf("x86_64"), false, false,
                )
                if (result < 0 && result != android.content.pm.PackageManager.NO_NATIVE_LIBRARIES) {
                    throw java.io.IOException("native library installation failed: " + result)
                }
            }
            Log.i(TAG, "native libraries prepared in " + dir)
            return dir.getAbsolutePath()
        }

        @JvmStatic
        fun prepareProcessIdentity(): String {
            sPackageIdentity?.let { return it }
            val pkg = parseApkLitePackageName(checkNotNull(System.getenv("ARTZIG_APK")))
            BinderSession.setIdentity(pkg, false)
            // AOSP ApkLiteParseUtils reads ApkAssets directly. System assets
            // initialize overlay configuration, which can query Process.myUid;
            // the hosted process must already have its Binder identity then.
            AssetManager.createSystemAssetsInZygoteLocked(false,
                checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")))
            sPackageIdentity = pkg
            return pkg
        }

        /** The pre-bootstrap package name, read with the AOSP lightweight
         * parse (ApkLiteParseUtils) — no binder identity needed. */
        @JvmStatic
        private fun parseApkLitePackageName(apkPath: String): String {
            // Not forDefaultParsing(): that resolves the platform-compat
            // service through ServiceManager, which would connect the hosted
            // Binder runtime anonymously before bootstrap fixes this
            // process's package identity.
            val input = android.content.pm.parsing.result.ParseTypeImpl(
                android.content.pm.parsing.result.ParseInput.Callback { _, _, _ -> true },
            ).reset()
            val result = android.content.pm.parsing.ApkLiteParseUtils.parsePackageLite(
                input, java.io.File(apkPath), 0,
            )
            if (result.isError) {
                throw IllegalStateException("cannot read package name of " + apkPath + ": " + result.errorMessage)
            }
            return result.result.packageName
        }

        /** Installs this process's APK through the activity manager (parsing
         * happens only in the system process). */
        @JvmStatic
        private fun installOwnPackage(apkPath: String, pkg: String, nativeLibraryDir: String) {
            val installer = checkNotNull(dev.artzig.HostedServiceClient.installerOrNull()) {
                "no hosted installer in this session"
            }
            val installed = installer.installPackage(apkPath, nativeLibraryDir)
            check(installed == pkg) { "installed as " + installed + ", expected " + pkg }
        }

        /** Install an APK for ATMS to launch in its own process. The desktop
         * controller has already selected its Binder identity before this call. */
        @JvmStatic fun installForDesktop(): String {
            val path = checkNotNull(System.getenv("ARTZIG_APK"))
            return checkNotNull(HostedServiceClient.installerOrNull())
                .installPackage(path, extractNativeLibraries(path))
        }

        /** AOSP bindApplication supplies resources, class loading and application
         * lifecycle. The host installs the APK and attaches its declared process;
         * no window or display configuration is synthesized here. */
        @JvmStatic
        public fun initHeadless() {
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            // A windowless service host has no reason to outlive the session:
            // exit if the broker connection is lost (parent death, teardown).
            dev.artzig.BinderSession.setExitOnDisconnect()
            val frameworkRes: String = System.getenv("ARTZIG_FRAMEWORK_RES")
            val apkPath: String = System.getenv("ARTZIG_APK")
            val pkg = prepareProcessIdentity()
            val processName = System.getenv("AZK_APP_PROCESS_NAME") ?: pkg
            android.os.Process.setArgV0(processName)
            installOwnPackage(apkPath, pkg, extractNativeLibraries(apkPath))
            HostContexts.prepareApplicationEnvironment(frameworkRes)
            if (java.security.Security.getProvider("AndroidKeyStore") == null) {
                java.security.Security.insertProviderAt(ArtzigKeyStoreProvider(), 1)
            }
            // ProcessList uses the manifest application process name. Provider
            // discovery is scoped to that name and UID during bindApplication.
            // AOSP ordering: attach first, then bindApplication (providers
            // installed + published before Application.onCreate).
            dev.artzig.HostedServiceClient.attachApplication(pkg, processName)
            Log.i(TAG, "headless app process up: " + pkg)
        }

        private const val HOST_THROWABLE_CHAR_LIMIT: Int = 64 * 1024
        private const val HOST_THROWABLE_FRAME_LIMIT: Int = 64
        private const val HOST_THROWABLE_CAUSE_LIMIT: Int = 16

        /** Bounded diagnostic for the host's catch-and-continue looper seam. */
        @JvmStatic
        public fun `formatThrowableForHost$artzig`(throwable: Throwable): String {
            val out: StringBuilder = StringBuilder(4096)
            val seen: java.util.IdentityHashMap<Throwable, Boolean> =
                java.util.IdentityHashMap()
            var current: Throwable? = throwable
            var causeCount: Int = 0
            while (current != null && causeCount++ < HOST_THROWABLE_CAUSE_LIMIT) {
                if (seen.put(current, true) != null) {
                    appendHostDiagnostic(out, "[circular cause: ")
                    appendHostDiagnostic(out, current.javaClass.name)
                    appendHostDiagnostic(out, "]\n")
                    break
                }
                if (causeCount > 1) appendHostDiagnostic(out, "Caused by: ")
                appendHostDiagnostic(out, current.javaClass.name)
                appendHostDiagnostic(out, " [message omitted by bounded host logger]\n")

                val frames: Array<StackTraceElement> = current.stackTrace
                val frameCount: Int = Math.min(frames.size, HOST_THROWABLE_FRAME_LIMIT)
                var index: Int = 0
                while (index < frameCount && out.length < HOST_THROWABLE_CHAR_LIMIT) {
                    val frame: StackTraceElement = frames[index++]
                    appendHostDiagnostic(out, "\tat ")
                    appendHostDiagnostic(out, frame.className)
                    appendHostDiagnostic(out, ".")
                    appendHostDiagnostic(out, frame.methodName)
                    appendHostDiagnostic(out, "(")
                    if (frame.isNativeMethod) {
                        appendHostDiagnostic(out, "Native Method")
                    } else {
                        appendHostDiagnostic(out, frame.fileName ?: "Unknown Source")
                        if (frame.lineNumber >= 0) {
                            appendHostDiagnostic(out, ":")
                            appendHostDiagnostic(out, frame.lineNumber.toString())
                        }
                    }
                    appendHostDiagnostic(out, ")\n")
                }
                if (frames.size > frameCount) {
                    appendHostDiagnostic(out, "\t... ")
                    appendHostDiagnostic(out, (frames.size - frameCount).toString())
                    appendHostDiagnostic(out, " more frames\n")
                }
                current = current.cause
            }
            if (current != null || out.length >= HOST_THROWABLE_CHAR_LIMIT) {
                appendHostDiagnostic(out, "[throwable diagnostic truncated]\n")
            }
            return out.toString()
        }

        @JvmStatic
        private fun appendHostDiagnostic(
            out: StringBuilder,
            text: CharSequence,
        ) {
            val remaining: Int = HOST_THROWABLE_CHAR_LIMIT - out.length
            if (remaining <= 0) return
            out.append(text, 0, Math.min(remaining, text.length))
        }
    }
}
