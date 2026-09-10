package dev.artzig.system

import dev.artzig.ArtzigTestDiagnostics

/** Missing installer transaction boundary for the original PackageArchiver.
 * The stock service owns archive predicates, validation, icons and callbacks.
 * No hosted PackageInstaller draft/uninstall backend exists; fail explicitly
 * before archive metadata or icons are written, without inventing sessions. */
object HostedPackageArchiving {
    @JvmStatic fun unavailable(operation: String): RuntimeException =
        ArtzigTestDiagnostics.unsupported("PackageArchiver", operation,
            "The hosted PackageInstaller archive/unarchive transaction backend is unavailable")

    @JvmStatic fun requireBackend(operation: String) {
        throw unavailable(operation)
    }
}
