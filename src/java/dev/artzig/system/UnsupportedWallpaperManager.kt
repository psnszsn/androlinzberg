@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.artzig.system

/** Every IWallpaperManager method throws UnsupportedOperationException; the hosted
 * service overrides what it serves (generated from the AIDL interface by
 * tools/gen-unsupported-stub.py; regenerate when the interface changes). */
abstract class UnsupportedWallpaperManager : android.app.IWallpaperManager.Stub() {
    override fun setWallpaper(name: String?, callingPackage: String?, screenOrientations: IntArray?, crops: MutableList<android.graphics.Rect?>?, allowBackup: Boolean, extras: android.os.Bundle?, which: Int, completion: android.app.IWallpaperManagerCallback?, userId: Int): android.os.ParcelFileDescriptor? = throw UnsupportedOperationException("IWallpaperManager.setWallpaper is not hosted")
    override fun setWallpaperComponentChecked(description: android.app.wallpaper.WallpaperDescription?, callingPackage: String?, which: Int, userId: Int) { throw UnsupportedOperationException("IWallpaperManager.setWallpaperComponentChecked is not hosted") }
    override fun setWallpaperComponent(name: android.content.ComponentName?) { throw UnsupportedOperationException("IWallpaperManager.setWallpaperComponent is not hosted") }
    override fun getWallpaper(callingPkg: String?, cb: android.app.IWallpaperManagerCallback?, which: Int, outParams: android.os.Bundle?, userId: Int): android.os.ParcelFileDescriptor? = throw UnsupportedOperationException("IWallpaperManager.getWallpaper is not hosted")
    override fun getWallpaperWithFeature(callingPkg: String?, callingFeatureId: String?, cb: android.app.IWallpaperManagerCallback?, which: Int, outParams: android.os.Bundle?, userId: Int, getCropped: Boolean): android.os.ParcelFileDescriptor? = throw UnsupportedOperationException("IWallpaperManager.getWallpaperWithFeature is not hosted")
    override fun getBitmapCrops(displaySizes: MutableList<android.graphics.Point?>?, which: Int, originalBitmap: Boolean, userId: Int): MutableList<*>? = throw UnsupportedOperationException("IWallpaperManager.getBitmapCrops is not hosted")
    override fun getCurrentBitmapCrops(which: Int, userId: Int): android.os.Bundle? = throw UnsupportedOperationException("IWallpaperManager.getCurrentBitmapCrops is not hosted")
    override fun getFutureBitmapCrops(bitmapSize: android.graphics.Point?, displaySizes: MutableList<android.graphics.Point?>?, screenOrientations: IntArray?, crops: MutableList<android.graphics.Rect?>?): MutableList<*>? = throw UnsupportedOperationException("IWallpaperManager.getFutureBitmapCrops is not hosted")
    override fun getBitmapCrop(bitmapSize: android.graphics.Point?, screenOrientations: IntArray?, crops: MutableList<android.graphics.Rect?>?): android.graphics.Rect? = throw UnsupportedOperationException("IWallpaperManager.getBitmapCrop is not hosted")
    override fun getWallpaperIdForUser(which: Int, userId: Int): Int = throw UnsupportedOperationException("IWallpaperManager.getWallpaperIdForUser is not hosted")
    override fun getWallpaperInfo(userId: Int): android.app.WallpaperInfo? = throw UnsupportedOperationException("IWallpaperManager.getWallpaperInfo is not hosted")
    override fun getWallpaperInfoWithFlags(which: Int, userId: Int): android.app.WallpaperInfo? = throw UnsupportedOperationException("IWallpaperManager.getWallpaperInfoWithFlags is not hosted")
    override fun getWallpaperInstance(which: Int, userId: Int): android.app.wallpaper.WallpaperInstance? = throw UnsupportedOperationException("IWallpaperManager.getWallpaperInstance is not hosted")
    override fun getWallpaperInfoFile(userId: Int): android.os.ParcelFileDescriptor? = throw UnsupportedOperationException("IWallpaperManager.getWallpaperInfoFile is not hosted")
    override fun clearWallpaper(callingPackage: String?, which: Int, userId: Int) { throw UnsupportedOperationException("IWallpaperManager.clearWallpaper is not hosted") }
    override fun hasNamedWallpaper(name: String?): Boolean = throw UnsupportedOperationException("IWallpaperManager.hasNamedWallpaper is not hosted")
    override fun setDimensionHints(width: Int, height: Int, callingPackage: String?, displayId: Int) { throw UnsupportedOperationException("IWallpaperManager.setDimensionHints is not hosted") }
    override fun getWidthHint(displayId: Int): Int = throw UnsupportedOperationException("IWallpaperManager.getWidthHint is not hosted")
    override fun getHeightHint(displayId: Int): Int = throw UnsupportedOperationException("IWallpaperManager.getHeightHint is not hosted")
    override fun setDisplayPadding(padding: android.graphics.Rect?, callingPackage: String?, displayId: Int) { throw UnsupportedOperationException("IWallpaperManager.setDisplayPadding is not hosted") }
    override fun getName(): String? = throw UnsupportedOperationException("IWallpaperManager.getName is not hosted")
    override fun settingsRestored() { throw UnsupportedOperationException("IWallpaperManager.settingsRestored is not hosted") }
    override fun isWallpaperSupported(callingPackage: String?): Boolean = throw UnsupportedOperationException("IWallpaperManager.isWallpaperSupported is not hosted")
    override fun isSetWallpaperAllowed(callingPackage: String?): Boolean = throw UnsupportedOperationException("IWallpaperManager.isSetWallpaperAllowed is not hosted")
    override fun isWallpaperBackupEligible(which: Int, userId: Int): Boolean = throw UnsupportedOperationException("IWallpaperManager.isWallpaperBackupEligible is not hosted")
    override fun getWallpaperColors(which: Int, userId: Int, displayId: Int): android.app.WallpaperColors? = throw UnsupportedOperationException("IWallpaperManager.getWallpaperColors is not hosted")
    override fun removeOnLocalColorsChangedListener(callback: android.app.ILocalWallpaperColorConsumer?, area: MutableList<android.graphics.RectF?>?, which: Int, userId: Int, displayId: Int) { throw UnsupportedOperationException("IWallpaperManager.removeOnLocalColorsChangedListener is not hosted") }
    override fun addOnLocalColorsChangedListener(callback: android.app.ILocalWallpaperColorConsumer?, regions: MutableList<android.graphics.RectF?>?, which: Int, userId: Int, displayId: Int) { throw UnsupportedOperationException("IWallpaperManager.addOnLocalColorsChangedListener is not hosted") }
    override fun registerWallpaperColorsCallback(cb: android.app.IWallpaperManagerCallback?, userId: Int, displayId: Int) { throw UnsupportedOperationException("IWallpaperManager.registerWallpaperColorsCallback is not hosted") }
    override fun unregisterWallpaperColorsCallback(cb: android.app.IWallpaperManagerCallback?, userId: Int, displayId: Int) { throw UnsupportedOperationException("IWallpaperManager.unregisterWallpaperColorsCallback is not hosted") }
    override fun setInAmbientMode(inAmbientMode: Boolean, animationDuration: Long) { throw UnsupportedOperationException("IWallpaperManager.setInAmbientMode is not hosted") }
    override fun notifyWakingUp(x: Int, y: Int, extras: android.os.Bundle?) { throw UnsupportedOperationException("IWallpaperManager.notifyWakingUp is not hosted") }
    override fun notifyGoingToSleep(x: Int, y: Int, extras: android.os.Bundle?) { throw UnsupportedOperationException("IWallpaperManager.notifyGoingToSleep is not hosted") }
    override fun setWallpaperDimAmount(dimAmount: Float) { throw UnsupportedOperationException("IWallpaperManager.setWallpaperDimAmount is not hosted") }
    override fun getWallpaperDimAmount(): Float = throw UnsupportedOperationException("IWallpaperManager.getWallpaperDimAmount is not hosted")
    override fun lockScreenWallpaperExists(): Boolean = throw UnsupportedOperationException("IWallpaperManager.lockScreenWallpaperExists is not hosted")
    override fun isStaticWallpaper(which: Int): Boolean = throw UnsupportedOperationException("IWallpaperManager.isStaticWallpaper is not hosted")
}
