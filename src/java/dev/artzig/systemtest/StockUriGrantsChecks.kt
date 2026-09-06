package dev.artzig.systemtest

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process

/** Cross-process grant contracts, using the fixture provider in package B. */
internal object StockUriGrantsChecks {
    private const val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private const val WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private const val PERSIST = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    private const val PREFIX = Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    private fun denied(action: () -> Unit) {
        try { action() } catch (_: SecurityException) { return }
        error("URI access unexpectedly permitted")
    }

    fun run(context: Context) {
        val resolver = context.contentResolver
        val public = Uri.parse("content://dev.artzig.test.b.provider/rows")
        val root = Uri.parse("content://dev.artzig.test.b.secret/granted")
        val child = Uri.withAppendedPath(root, "child")
        val other = Uri.parse("content://dev.artzig.test.b.secret/other")
        fun permission(uri: Uri, mode: Int) = context.checkUriPermission(
            uri, Process.myPid(), Process.myUid(), mode)
        fun owner(operation: String, mode: Int) {
            val request = Bundle().apply {
                putParcelable("uri", root)
                putString("package", context.packageName)
                putInt("mode", mode)
            }
            checkNotNull(resolver.call(public, operation, null, request))
        }

        check(resolver.persistedUriPermissions.isEmpty())
        // Public provider access alone does not carry a persistable grant.
        denied { resolver.takePersistableUriPermission(public, READ) }
        denied { resolver.query(child, null, null, null, null)?.close() }
        denied { context.grantUriPermission(context.packageName, other, READ) }

        owner("grant-uri", READ or PERSIST or PREFIX)
        check(permission(child, READ) == PackageManager.PERMISSION_GRANTED)
        check(permission(child, WRITE) == PackageManager.PERMISSION_DENIED)
        check(permission(other, READ) == PackageManager.PERMISSION_DENIED)
        checkNotNull(resolver.query(child, null, null, null, null)).use {
            check(it.count == 2)
        }
        denied { resolver.takePersistableUriPermission(root, WRITE) }
        resolver.takePersistableUriPermission(root, READ)
        val persisted = resolver.persistedUriPermissions
        check(persisted.size == 1 && persisted.single().uri == root &&
            persisted.single().isReadPermission && !persisted.single().isWritePermission)
        resolver.releasePersistableUriPermission(root, READ)
        check(resolver.persistedUriPermissions.isEmpty())
        // Releasing persistence preserves the owner's transient grant.
        check(permission(child, READ) == PackageManager.PERMISSION_GRANTED)
        owner("revoke-uri", READ)
        check(permission(child, READ) == PackageManager.PERMISSION_DENIED)
        denied { resolver.query(child, null, null, null, null)?.close() }

        owner("grant-uri", READ)
        check(permission(root, READ) == PackageManager.PERMISSION_GRANTED)
        check(permission(child, READ) == PackageManager.PERMISSION_DENIED)
        denied { resolver.takePersistableUriPermission(root, READ) }
        owner("revoke-uri", READ)
        check(permission(root, READ) == PackageManager.PERMISSION_DENIED)
        // ClipboardService owns a distinct UriPermissionOwner Binder token.
        // Reading the clip grants the reader access; clearing it revokes that
        // owner's grant without relying on a process-wide permissive shortcut.
        owner("clipboard-uri", 0)
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        check(clipboard.primaryClip?.getItemAt(0)?.uri == root)
        check(permission(root, READ) == PackageManager.PERMISSION_GRANTED)
        owner("clear-clipboard", 0)
        check(permission(root, READ) == PackageManager.PERMISSION_DENIED)
        println("URI grants: cross-process access, prefix, persistence, revocation and clipboard owner lifetime passed")
    }
}
