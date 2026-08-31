package dev.artzig.system

import android.content.pm.ProviderInfo
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable

/**
 * A published content provider: its manifest description plus the live
 * IHostedContentProvider binder (source note: android.app.ContentProviderHolder,
 * reduced — no noReleaseNeeded/wait-queue fields, the hosted activity manager
 * tracks liveness by process record).
 */
class HostedContentProviderHolder(
    @JvmField val info: ProviderInfo,
    @JvmField val provider: IBinder?,
) : Parcelable {
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        info.writeToParcel(dest, flags)
        dest.writeStrongBinder(provider)
    }

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<HostedContentProviderHolder> {
            override fun createFromParcel(source: Parcel): HostedContentProviderHolder =
                HostedContentProviderHolder(
                    ProviderInfo.CREATOR.createFromParcel(source),
                    source.readStrongBinder(),
                )

            override fun newArray(size: Int): Array<HostedContentProviderHolder?> = arrayOfNulls(size)
        }
    }
}
