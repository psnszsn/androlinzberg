package dev.artzig.system

import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable

/**
 * Everything the session's package manager records for one installed package:
 * the `PackageInfo` (with activities/services/providers), the application
 * entry points, the intent actions per service, and the permission grants.
 */
class HostedInstalledPackage(
    @JvmField val packageInfo: PackageInfo,
    @JvmField val applicationClassName: String?,
    @JvmField val launcherActivityName: String?,
    @JvmField val applicationTheme: Int,
    @JvmField val apkPath: String?,
    @JvmField val dataDir: String?,
    /** service class name -> ArrayList<String> of intent actions. */
    @JvmField val serviceActions: Bundle,
    @JvmField val grantedPermissions: List<String>,
) : Parcelable {
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        packageInfo.writeToParcel(dest, flags)
        dest.writeString8(applicationClassName)
        dest.writeString8(launcherActivityName)
        dest.writeInt(applicationTheme)
        dest.writeString8(apkPath)
        dest.writeString8(dataDir)
        dest.writeBundle(serviceActions)
        dest.writeStringList(grantedPermissions)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<HostedInstalledPackage> = object : Parcelable.Creator<HostedInstalledPackage> {
            override fun createFromParcel(source: Parcel): HostedInstalledPackage {
                val info = PackageInfo.CREATOR.createFromParcel(source)
                val applicationClassName = source.readString8()
                val launcherActivityName = source.readString8()
                val theme = source.readInt()
                val apkPath = source.readString8()
                val dataDir = source.readString8()
                val actions = checkNotNull(source.readBundle())
                val granted = ArrayList<String>()
                source.readStringList(granted)
                return HostedInstalledPackage(
                    info, applicationClassName, launcherActivityName, theme, apkPath, dataDir, actions, granted,
                )
            }

            override fun newArray(size: Int): Array<HostedInstalledPackage?> = arrayOfNulls(size)
        }
    }
}
