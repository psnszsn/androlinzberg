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
            val pkg = if (System.getenv("AZK_LAUNCH_CREDENTIAL") != null)
                checkNotNull(System.getenv("AZK_BINDER_PACKAGE"))
            else parseApkLitePackageName(checkNotNull(System.getenv("ARTZIG_APK")))
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
            val pkg = prepareProcessIdentity()
            val processName = System.getenv("AZK_APP_PROCESS_NAME") ?: pkg
            android.os.Process.setArgV0(processName)
            // The package is installed before its process starts (by a launcher
            // or the session's preinstalls); this process runs with its uid.
            HostContexts.prepareApplicationEnvironment(frameworkRes)
            // ZygoteInit.preload's warmUpJcaProviders, which forked apps inherit.
            android.security.keystore2.AndroidKeyStoreProvider.install()
            // ProcessList uses the manifest application process name. Provider
            // discovery is scoped to that name and UID during bindApplication.
            // AOSP ordering: attach first, then bindApplication (providers
            // installed + published before Application.onCreate).
            dev.artzig.HostedServiceClient.attachApplication(pkg, processName)
            Log.i(TAG, "headless app process up: " + pkg)
        }

    }
}
