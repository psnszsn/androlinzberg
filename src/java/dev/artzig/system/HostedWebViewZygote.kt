package dev.artzig.system

import android.app.LoadedApk
import android.content.pm.ApplicationInfo
import android.net.Credentials
import android.net.LocalSocket
import android.os.Build
import android.os.Parcel
import android.os.Process
import android.util.Base64
import android.webkit.WebViewFactory
import com.android.internal.os.ChildZygoteInit
import com.android.internal.os.Zygote
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream

/** ChildZygoteInit argument/range handling and WebViewZygoteConnection preload.
 * The original fork/select loop cannot run in an exec supervisor. Its original
 * provider/native preload runs here in the actual UID-1053 process; exec children
 * initialize their own VM instead of inheriting preloaded ART pages or APK FDs. */
internal class HostedWebViewZygote(val provider: ApplicationInfo, arguments: Array<String>) {
    val socketName = checkNotNull(parseString("parseSocketNameFromArgs", arguments))
    val abiList = checkNotNull(parseString("parseAbiListFromArgs", arguments))
    private val minimum = parseInt(arguments, Zygote.CHILD_ZYGOTE_UID_RANGE_START)
    private val maximum = parseInt(arguments, Zygote.CHILD_ZYGOTE_UID_RANGE_END)
    @Volatile private var preloaded = false

    init {
        require(socketName.isNotEmpty() && '\u0000' !in socketName)
        require(abiList.split(',').all { it in Build.SUPPORTED_ABIS })
        require(minimum >= Process.FIRST_ISOLATED_UID && minimum <= maximum)
    }

    fun requireChild(uid: Int) {
        check(preloaded) { "WebView provider has not preloaded successfully" }
        require(uid in minimum..maximum && Process.isIsolated(uid)) { "UID is outside the child zygote range" }
    }

    @Synchronized fun preload(peer: LocalSocket, encoded: String, output: DataOutputStream) {
        val supplied = decodeApplication(encoded)
        require(supplied.packageName == provider.packageName && supplied.sourceDir == provider.sourceDir &&
            supplied.splitSourceDirs.contentEquals(provider.splitSourceDirs) &&
            supplied.nativeLibraryDir == provider.nativeLibraryDir) { "Preload differs from the selected provider" }
        val loaded = LoadedApk(null, provider, null, null, false, true, false)
        val type = Class.forName("com.android.internal.os.WebViewZygoteInit\$WebViewZygoteConnection")
        // Capture the original preload result before forwarding its exact wire
        // status. Credentials and timeout operations still use the real peer.
        val capture = PreloadSocket(peer)
        val timeout = peer.soTimeout
        try {
            val connection = type.getDeclaredConstructor(LocalSocket::class.java, String::class.java)
                .apply { isAccessible = true }.newInstance(capture, abiList)
            type.getDeclaredMethod("doPreload", ClassLoader::class.java, String::class.java)
                .apply { isAccessible = true }.invoke(connection, loaded.classLoader,
                    WebViewFactory.getWebViewLibrary(provider))
            val bytes = capture.result.toByteArray()
            check(bytes.size == 4) { "Invalid original WebView preload reply" }
            val result = DataInputStream(bytes.inputStream()).readInt()
            check(result == 0 || result == 1)
            preloaded = result == 1
            output.writeInt(result)
        } finally { peer.soTimeout = timeout }
    }

    private class PreloadSocket(private val peer: LocalSocket) : LocalSocket() {
        val result = ByteArrayOutputStream(4)
        override fun getOutputStream(): OutputStream = result
        override fun getPeerCredentials(): Credentials = peer.peerCredentials
        override fun setSoTimeout(timeout: Int) { peer.soTimeout = timeout }
    }

    companion object {
        const val ENTRY = "com.android.internal.os.WebViewZygoteInit"

        fun run(encodedProvider: String, arguments: Array<String>) {
            check(Process.myUid() == Process.WEBVIEW_ZYGOTE_UID)
            val controller = HostedWebViewZygote(decodeApplication(encodedProvider), arguments)
            val service = HostedZygoteService(null, { it() }, controller)
            try { service.awaitClosed() } finally { service.close() }
        }

        fun encodeApplication(info: ApplicationInfo): String {
            val parcel = Parcel.obtain()
            try {
                info.writeToParcel(parcel, 0)
                return Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
            } finally { parcel.recycle() }
        }

        private fun decodeApplication(encoded: String): ApplicationInfo {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                return ApplicationInfo.CREATOR.createFromParcel(parcel)
            } finally { parcel.recycle() }
        }

        private fun parseString(name: String, arguments: Array<String>): String? =
            ChildZygoteInit::class.java.getDeclaredMethod(name, Array<String>::class.java)
                .apply { isAccessible = true }.invoke(null, arguments as Any) as String?

        private fun parseInt(arguments: Array<String>, name: String): Int =
            ChildZygoteInit::class.java.getDeclaredMethod("parseIntFromArg", Array<String>::class.java,
                String::class.java).apply { isAccessible = true }.invoke(null, arguments, name) as Int
    }
}
