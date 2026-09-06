package com.android.server.uri

import android.content.Intent
import android.net.Uri
import android.os.Binder
import dev.artzig.system.unsupported

/** Stock owner lifetimes, persistable modes, and URI user normalization. */
object StockUriPermissionSmoke {
    /** Supply the actual local service to isolated activity-transaction fixtures. */
    fun withService(block: () -> Unit) {
        check(com.android.server.LocalServices.getService(UriGrantsManagerInternal::class.java) == null)
        val directory = java.nio.file.Files.createTempDirectory("activity-uri-grants").toFile()
        try {
            val service = UriGrantsManagerService.createForTest(directory).localService
            com.android.server.LocalServices.addService(UriGrantsManagerInternal::class.java, service)
            block()
        } finally {
            com.android.server.LocalServices.removeServiceForTest(UriGrantsManagerInternal::class.java)
            check(directory.deleteRecursively())
        }
    }

    @JvmStatic fun run() {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val uri = GrantUri.resolve(0, Uri.parse("content://10@example.documents/tree/7"),
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        check(uri.sourceUserId == 10 && uri.prefix)
        check(uri.uri == Uri.parse("content://example.documents/tree/7"))
        val observedModes = ArrayList<Int>()
        val service = object : UriGrantsManagerInternal by unsupported<UriGrantsManagerInternal>() {
            override fun removeUriPermissionIfNeeded(permission: UriPermission) {
                observedModes.add(permission.modeFlags)
            }
        }
        val first = UriPermissionOwner(service, "first")
        val second = UriPermissionOwner(service, "second")
        check(first.externalToken === first.externalToken)
        check(UriPermissionOwner.fromExternalToken(first.externalToken) === first)
        check(UriPermissionOwner.fromExternalToken(Binder()) == null)
        val permission = UriPermission("source", "target", 10001, uri)
        permission.grantModes(read or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, first)
        permission.grantModes(write, second)
        check(permission.modeFlags == read or write)
        check(permission.takePersistableModes(read))
        check(!permission.takePersistableModes(write))
        check(permission.persistedModeFlags == read && permission.persistedCreateTime > 0)
        first.removeUriPermissions()
        check(permission.modeFlags == read or write)
        second.removeUriPermissions()
        check(permission.modeFlags == read)
        check(permission.releasePersistableModes(read) && permission.modeFlags == 0)
        check(observedModes == listOf(read or write, read))
        val shared = UriPermission("source", "target", 10001, uri)
        shared.grantModes(read, first)
        shared.grantModes(read, second)
        first.removeUriPermissions()
        check(shared.modeFlags == read)
        second.removeUriPermissions()
        check(shared.modeFlags == 0 && observedModes.takeLast(2) == listOf(read, 0))
    }
}
