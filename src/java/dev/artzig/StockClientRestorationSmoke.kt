package dev.artzig

import android.app.ActivityThread
import android.app.wallpaper.WallpaperDescription
import android.content.Intent
import android.content.Context
import android.service.wallpaper.IWallpaperService
import android.service.wallpaper.WallpaperService

/** Observable contracts restored when removing legacy client replacements. */
internal object StockClientRestorationSmoke {
    fun run() {
        val base: Context = ActivityThread.currentActivityThread().systemContext
        val context = base.createPackageContext("org.example.permissions", 0)
        val activityManager = checkNotNull(context.getSystemService(android.app.ActivityManager::class.java))
        check(activityManager.totalRam == android.os.Process.getTotalMemory())
        val style = context.resources.getIdentifier(
            "StockUnresolvedAttributes", "style", context.packageName)
        check(style != 0)
        val theme = context.resources.newTheme()
        val attributes = theme.obtainStyledAttributes(style, intArrayOf(android.R.attr.colorAccent))
        try {
            val getters = listOf<() -> Any?>(
                { attributes.getColor(0, 0x123456) },
                { attributes.getColorStateList(0) },
                { attributes.getDimension(0, 12f) },
                { attributes.getInteger(0, 7) },
                { attributes.getDrawable(0) },
                { attributes.getFont(0) },
            )
            for (getter in getters) {
                val failure = runCatching { getter() }.exceptionOrNull()
                check(failure is UnsupportedOperationException &&
                    failure.message.orEmpty().contains("Failed to resolve attribute")) {
                    "Unresolved theme attribute must retain the stock failure: $failure"
                }
            }
        } finally { attributes.recycle() }
        // An absent value still returns the caller's default; only an unresolved
        // attribute reference is exceptional.
        val absent = theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorAccent))
        try { check(absent.getColor(0, 0x123456) == 0x123456) }
        finally { absent.recycle() }

        val service = object : WallpaperService() {
            var creations = 0
            override fun onCreateEngine(): Engine = Engine().also { creations++ }
        }
        try {
            val binder = checkNotNull(service.onBind(Intent(WallpaperService.SERVICE_INTERFACE)))
            check(binder.interfaceDescriptor == IWallpaperService::class.java.name)
            check(IWallpaperService.Stub.asInterface(binder).asBinder() === binder)
            val first = service.onCreateEngine()
            val second = service.onCreateEngine(WallpaperDescription.Builder().build())
            check(first !== second && service.creations == 2)
            check(!first.isVisible && !first.surfaceHolder.surface.isValid)
            // A detach for an unknown engine is harmless in the stock service.
            IWallpaperService.Stub.asInterface(binder).detach(android.os.Binder())
        } finally { service.onDestroy() }
        println("stock client resource failures and wallpaper binding/engine API: PASS")
    }
}
