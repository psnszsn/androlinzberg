package com.android.server.pm

import android.content.pm.PackageManagerInternal.PackageListObserver

/**
 * PMS.PackageManagerInternalImpl.getPackageList and PackageManagerInternalBase.removePackageListObserver.
 * The host registry replaces PMS's install database; the stock helper owns callback snapshots.
 */
class HostedPackageObservers {
    private val observers = PackageObserverHelper()

    fun snapshot(names: List<String>, observer: PackageListObserver?): PackageList =
        PackageList(ArrayList(names), observer).also {
            if (observer != null) observers.addObserver(it)
        }

    fun remove(observer: PackageListObserver) = observers.removeObserver(observer)
    fun added(name: String, uid: Int) = observers.notifyAdded(name, uid)
    fun changed(name: String, uid: Int) = observers.notifyChanged(name, uid)
}
