package dev.artzig.system

import android.Manifest
import android.app.ILocalWallpaperColorConsumer
import android.app.IWallpaperManagerCallback
import android.app.WallpaperColors
import android.app.WallpaperInfo
import android.app.wallpaper.WallpaperDescription
import android.app.wallpaper.WallpaperInstance
import android.content.ComponentName
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor

/**
 * The session's IWallpaperManager over the real AIDL surface, published as
 * `Context.WALLPAPER_SERVICE`.
 *
 * Source note: `com.android.server.wallpaper.WallpaperManagerService` for a
 * device whose wallpaper service is disabled and never received an image: no
 * wallpaper file, info, instance or colors (WallpaperManager falls back to the
 * framework's default wallpaper drawable), wallpaper is neither supported nor
 * settable for any package (so the setters hand back null, which the manager
 * treats as "nothing written"), the hint and dim setters keep their permission
 * gates, and the colors callbacks are registered and never fire.
 */
class HostedWallpaperManagerService(private val permissions: HostedPermissions) : UnsupportedWallpaperManager() {
    private val lock = Any()
    private val colorsCallbacks = LinkedHashMap<IBinder, IWallpaperManagerCallback>()

    override fun isWallpaperSupported(callingPackage: String?): Boolean = false
    override fun isSetWallpaperAllowed(callingPackage: String?): Boolean = false
    override fun isWallpaperBackupEligible(which: Int, userId: Int): Boolean = false
    override fun hasNamedWallpaper(name: String?): Boolean = false
    override fun getName(): String? = null
    override fun lockScreenWallpaperExists(): Boolean = false
    override fun isStaticWallpaper(which: Int): Boolean = false

    override fun getWallpaper(callingPkg: String?, cb: IWallpaperManagerCallback?, which: Int, outParams: Bundle?, userId: Int): ParcelFileDescriptor? = null
    override fun getWallpaperWithFeature(callingPkg: String?, callingFeatureId: String?, cb: IWallpaperManagerCallback?, which: Int, outParams: Bundle?, userId: Int, getCropped: Boolean): ParcelFileDescriptor? = null
    override fun getWallpaperInfo(userId: Int): WallpaperInfo? = null
    override fun getWallpaperInfoWithFlags(which: Int, userId: Int): WallpaperInfo? = null
    override fun getWallpaperInstance(which: Int, userId: Int): WallpaperInstance? = null
    override fun getWallpaperInfoFile(userId: Int): ParcelFileDescriptor? = null
    override fun getWallpaperIdForUser(which: Int, userId: Int): Int = -1
    override fun getWallpaperColors(which: Int, userId: Int, displayId: Int): WallpaperColors? = null
    override fun getBitmapCrops(displaySizes: MutableList<Point?>?, which: Int, originalBitmap: Boolean, userId: Int): MutableList<*>? = ArrayList<Rect>()
    override fun getCurrentBitmapCrops(which: Int, userId: Int): Bundle? = Bundle()
    override fun getFutureBitmapCrops(bitmapSize: Point?, displaySizes: MutableList<Point?>?, screenOrientations: IntArray?, crops: MutableList<Rect?>?): MutableList<*>? = ArrayList<Rect>()
    override fun getBitmapCrop(bitmapSize: Point?, screenOrientations: IntArray?, crops: MutableList<Rect?>?): Rect? = null
    override fun getWidthHint(displayId: Int): Int = 0
    override fun getHeightHint(displayId: Int): Int = 0
    override fun getWallpaperDimAmount(): Float = 0f

    /** WallpaperManagerService.setWallpaper: not allowed for this package, so no file is opened. */
    override fun setWallpaper(name: String?, callingPackage: String?, screenOrientations: IntArray?, crops: MutableList<Rect?>?, allowBackup: Boolean, extras: Bundle?, which: Int, completion: IWallpaperManagerCallback?, userId: Int): ParcelFileDescriptor? = null
    override fun setWallpaperComponentChecked(description: WallpaperDescription?, callingPackage: String?, which: Int, userId: Int) = permissions.enforce(Manifest.permission.SET_WALLPAPER_COMPONENT, "setWallpaperComponentChecked")
    override fun setWallpaperComponent(name: ComponentName?) = permissions.enforce(Manifest.permission.SET_WALLPAPER_COMPONENT, "setWallpaperComponent")
    override fun clearWallpaper(callingPackage: String?, which: Int, userId: Int) {}
    override fun setDimensionHints(width: Int, height: Int, callingPackage: String?, displayId: Int) = permissions.enforce(Manifest.permission.SET_WALLPAPER_HINTS, "setDimensionHints")
    override fun setDisplayPadding(padding: Rect?, callingPackage: String?, displayId: Int) = permissions.enforce(Manifest.permission.SET_WALLPAPER_HINTS, "setDisplayPadding")
    override fun setWallpaperDimAmount(dimAmount: Float) = permissions.enforce(Manifest.permission.SET_WALLPAPER_DIM_AMOUNT, "setWallpaperDimAmount")
    override fun settingsRestored() = permissions.enforceSystem("settingsRestored")
    override fun setInAmbientMode(inAmbientMode: Boolean, animationDuration: Long) = permissions.enforceSystem("setInAmbientMode")
    override fun notifyWakingUp(x: Int, y: Int, extras: Bundle?) = permissions.enforceSystem("notifyWakingUp")
    override fun notifyGoingToSleep(x: Int, y: Int, extras: Bundle?) = permissions.enforceSystem("notifyGoingToSleep")

    override fun registerWallpaperColorsCallback(cb: IWallpaperManagerCallback?, userId: Int, displayId: Int) {
        val binder = cb?.asBinder() ?: return
        synchronized(lock) { colorsCallbacks[binder] = cb }
    }
    override fun unregisterWallpaperColorsCallback(cb: IWallpaperManagerCallback?, userId: Int, displayId: Int) {
        synchronized(lock) { colorsCallbacks.remove(cb?.asBinder() ?: return) }
    }
    override fun addOnLocalColorsChangedListener(callback: ILocalWallpaperColorConsumer?, regions: MutableList<RectF?>?, which: Int, userId: Int, displayId: Int) {}
    override fun removeOnLocalColorsChangedListener(callback: ILocalWallpaperColorConsumer?, area: MutableList<RectF?>?, which: Int, userId: Int, displayId: Int) {}
}
