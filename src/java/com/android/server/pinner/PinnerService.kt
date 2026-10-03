package com.android.server.pinner

import android.util.ArraySet

/**
 * Hosted adaptation of AOSP `com.android.server.pinner.PinnerService` for the
 * one call PackageManagerService makes (`DexOptHelper.DexoptDoneHandler`).
 * The stock service mlock()s hot APK/odex pages into memory; that is a
 * memory-residency optimization with no observable effect on Android
 * behavior, and the host keeps no pinned file set to refresh.
 */
class PinnerService {
    /** Pinned `update(ArraySet<String>, boolean)`: repin files of updated packages. */
    fun update(updatedPackages: ArraySet<String>?, force: Boolean) {}
}
