package dev.artzig.system

import android.os.Parcel
import android.os.Parcelable

/**
 * What one app process tells the activity manager at attach: its identity and
 * the services its package declares (the slice of the manifest AMS needs for
 * resolution, permission checks, and process respawning).
 */
class HostedProcessManifest(
    @JvmField val packageName: String,
    @JvmField val processName: String,
    /** Absolute APK path, so AMS can respawn a process for this package; null for APK-less peers. */
    @JvmField val apkPath: String?,
    @JvmField val services: List<ServiceEntry>,
    @JvmField val grantedPermissions: List<String>,
) : Parcelable {
    class ServiceEntry(
        @JvmField val className: String,
        @JvmField val permission: String?,
        @JvmField val exported: Boolean,
        @JvmField val actions: List<String>,
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString8(packageName)
        dest.writeString8(processName)
        dest.writeString8(apkPath)
        dest.writeInt(services.size)
        for (service in services) {
            dest.writeString8(service.className)
            dest.writeString8(service.permission)
            dest.writeInt(if (service.exported) 1 else 0)
            dest.writeStringList(service.actions)
        }
        dest.writeStringList(grantedPermissions)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<HostedProcessManifest> = object : Parcelable.Creator<HostedProcessManifest> {
            override fun createFromParcel(source: Parcel): HostedProcessManifest {
                val packageName = checkNotNull(source.readString8())
                val processName = checkNotNull(source.readString8())
                val apkPath = source.readString8()
                val count = source.readInt()
                val services = ArrayList<ServiceEntry>(count)
                repeat(count) {
                    val className = checkNotNull(source.readString8())
                    val permission = source.readString8()
                    val exported = source.readInt() != 0
                    val actions = ArrayList<String>()
                    source.readStringList(actions)
                    services.add(ServiceEntry(className, permission, exported, actions))
                }
                val granted = ArrayList<String>()
                source.readStringList(granted)
                return HostedProcessManifest(packageName, processName, apkPath, services, granted)
            }

            override fun newArray(size: Int): Array<HostedProcessManifest?> = arrayOfNulls(size)
        }
    }
}
