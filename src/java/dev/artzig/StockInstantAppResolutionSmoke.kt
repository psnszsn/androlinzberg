package dev.artzig

import android.content.ComponentName
import android.content.Intent
import android.content.pm.InstantAppRequestInfo
import android.net.Uri
import android.os.Parcel
import android.os.UserHandle
import com.android.server.pm.InstantAppResolver

/** Stock resolver privacy boundary and installer handoff values. */
object StockInstantAppResolutionSmoke {
    fun run() {
        val original = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("https://private.example/path?secret=value#fragment"), "text/plain")
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("fixture")
            component = ComponentName("fixture", "fixture.Activity")
            putExtra("secret", "private")
        }
        val sanitized = InstantAppResolver.sanitizeIntent(original)
        check(sanitized.action == original.action && sanitized.type == "text/plain")
        check(sanitized.categories == original.categories && sanitized.flags == original.flags)
        check(sanitized.`package` == "fixture" && sanitized.component == null && sanitized.extras == null)
        check(sanitized.scheme == "https" && sanitized.data!!.host == null)
        check(sanitized.data!!.schemeSpecificPart.isEmpty())
        check(original.getStringExtra("secret") == "private" && original.data!!.host == "private.example")

        val request = InstantAppRequestInfo(sanitized, intArrayOf(17, 31), UserHandle.SYSTEM, false, "token")
        val parcel = Parcel.obtain()
        try {
            request.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val copy = InstantAppRequestInfo.CREATOR.createFromParcel(parcel)
            check(copy.intent.filterEquals(sanitized))
            check(copy.hostDigestPrefix!!.contentEquals(intArrayOf(17, 31)))
            check(copy.userHandle == UserHandle.SYSTEM && !copy.isRequesterInstantApp && copy.token == "token")
            check(parcel.dataAvail() == 0)
        } finally { parcel.recycle() }

        val installer = InstantAppResolver.buildEphemeralInstallerIntent(original, sanitized,
            null, "fixture", null, null, "text/plain", 0, null, "token", true, null)
        check(installer.action == Intent.ACTION_RESOLVE_INSTANT_APP_PACKAGE)
        check(installer.flags and Intent.FLAG_ACTIVITY_NO_HISTORY != 0)
        check(installer.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0)
        check(installer.getStringExtra(Intent.EXTRA_INSTANT_APP_TOKEN) == "token")
        check(installer.getStringExtra(Intent.EXTRA_INSTANT_APP_HOSTNAME) == "private.example")
        check(installer.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!.filterEquals(sanitized))
    }
}
