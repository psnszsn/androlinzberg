package android.webkit

import android.content.ContentResolver
import android.graphics.Bitmap

/** Hosted WebView-provider seam for the obsolete process-wide favicon store. */
@Deprecated("The platform WebIconDatabase API is obsolete")
abstract class WebIconDatabase {
    @Deprecated("The platform WebIconDatabase API is obsolete")
    fun interface IconListener {
        fun onReceivedIcon(url: String, icon: Bitmap)
    }

    abstract fun open(path: String)
    abstract fun close()
    abstract fun removeAllIcons()
    abstract fun requestIconForPageUrl(url: String, listener: IconListener)
    abstract fun bulkRequestIconForPageUrl(
        cr: ContentResolver,
        where: String,
        listener: IconListener,
    )
    abstract fun retainIconForPageUrl(url: String)
    abstract fun releaseIconForPageUrl(url: String)

    companion object {
        private val instance = object : WebIconDatabase() {
            override fun open(path: String) = Unit
            override fun close() = Unit
            override fun removeAllIcons() = Unit
            override fun requestIconForPageUrl(url: String, listener: IconListener) = Unit
            override fun bulkRequestIconForPageUrl(
                cr: ContentResolver,
                where: String,
                listener: IconListener,
            ) = Unit
            override fun retainIconForPageUrl(url: String) = Unit
            override fun releaseIconForPageUrl(url: String) = Unit
        }

        @JvmStatic
        fun getInstance(): WebIconDatabase = instance
    }
}
