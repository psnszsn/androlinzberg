package dev.artzig

import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Parcel
import android.view.View
import android.widget.ImageView
import android.window.SplashScreenView

/** Stock splash material transport and resource ownership, without compositor embedding. */
internal object StockSplashScreenSmoke {
    fun run() {
        val context = HostContexts.systemContext(checkNotNull(System.getenv("ARTZIG_FRAMEWORK_RES")),
            200, 200, 120, 0)
        val source = SplashScreenView.Builder(context)
            .setBackgroundColor(Color.BLUE)
            .setIconSize(16)
            .setCenterViewDrawable(ColorDrawable(Color.RED))
            .setBrandingDrawable(ColorDrawable(Color.GREEN), 24, 8)
            .build()
        val size = View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY)
        source.measure(size, size)
        source.layout(0, 0, 200, 200)
        check(source.isCopyable && source.initBackgroundColor == Color.BLUE)
        check(source.iconView is ImageView && source.iconView!!.width == 16)
        val materials = SplashScreenView.SplashScreenViewParcelable(source)
        val parcel = Parcel.obtain()
        val received = try {
            materials.writeToParcel(parcel, 0)
            check(parcel.dataSize() > 32)
            parcel.setDataPosition(0)
            SplashScreenView.SplashScreenViewParcelable.CREATOR.createFromParcel(parcel).also {
                check(parcel.dataAvail() == 0)
            }
        } finally { parcel.recycle() }
        materials.clearIfNeeded()
        val restored = SplashScreenView.Builder(context).createFromParcel(received).build()
        check(restored.initBackgroundColor == Color.BLUE)
        val icon = (restored.iconView as ImageView).drawable as BitmapDrawable
        val branding = restored.brandingView!!.background as BitmapDrawable
        check(icon.bitmap.width == 16 && icon.bitmap.height == 16)
        check(icon.bitmap.getPixel(8, 8) == Color.RED)
        check(branding.bitmap.width == 24 && branding.bitmap.height == 8)
        check(branding.bitmap.getPixel(12, 4) == Color.GREEN)
        restored.remove()
        check(restored.visibility == View.GONE && icon.bitmap.isRecycled && branding.bitmap.isRecycled)
        restored.remove()
        received.clearIfNeeded()
        source.remove()
        val solid = SplashScreenView.Builder(context).setBackgroundColor(Color.RED)
            .setAllowHandleSolidColor(false).build()
        check(!solid.isCopyable)
        val transferable = SplashScreenView.Builder(context).build()
        check(transferable.isCopyable)
        transferable.setNotCopyable()
        check(!transferable.isCopyable)
        solid.remove()
        transferable.remove()
        println("stock-splash: builder, icon/branding parcel pixels, copy policy and removal ownership")
    }
}
