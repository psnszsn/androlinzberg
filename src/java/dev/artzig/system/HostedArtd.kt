package dev.artzig.system

import com.android.server.art.ArtdDexoptResult
import com.android.server.art.ArtifactsLocation
import com.android.server.art.ArtifactsPath
import com.android.server.art.CopyAndRewriteProfileResult
import com.android.server.art.DexMetadataPath
import com.android.server.art.DexoptOptions
import com.android.server.art.FileVisibility
import com.android.server.art.GetDexoptNeededResult
import com.android.server.art.GetDexoptStatusResult
import com.android.server.art.IArtd
import com.android.server.art.IArtdCancellationSignal
import com.android.server.art.IArtdNotification
import com.android.server.art.MergeProfileOptions
import com.android.server.art.OutputArtifacts
import com.android.server.art.OutputProfile
import com.android.server.art.OutputSecureDexMetadataCompanion
import com.android.server.art.ProfilePath
import com.android.server.art.RuntimeArtifactsPath
import com.android.server.art.SecureDexMetadataWithCompanionPaths
import com.android.server.art.VdexPath
import dev.artzig.ArtzigTestDiagnostics
import java.io.File

/**
 * The artd daemon of a hosted session: the `IArtd` Binder stock ART Service
 * (`com.android.server.art.ArtManagerLocal`) reaches through
 * `ArtModuleServiceManager.getArtdServiceRegisterer()`. Pinned art/artd/artd.cc
 * runs dex2oat/profman in confined child processes and manages AOT artifacts
 * next to APKs or in the dalvik cache. Hosted app processes run their dex code
 * with the JIT and keep no install-time OAT, VDEX or reference-profile
 * artifacts, so this daemon reports every dex file as not needing dexopt and
 * every artifact as absent; operations that would produce artifacts report a
 * structured unsupported result. Dex and class-loader-context validation keep
 * artd's contracts.
 */
class HostedArtd : IArtd.Stub() {
    /** artd serves only system_server's ART Service (its SELinux domain policy). */
    override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
        if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) enforceSystemCaller("artd")
        return super.onTransact(code, data, reply, flags)
    }

    private fun unsupported(member: String): UnsupportedOperationException =
        ArtzigTestDiagnostics.unsupported("artd", member, "Hosted ART keeps no AOT artifacts or reference profiles")

    override fun isAlive(): Boolean = true

    // Artifact queries: none exist, so nothing is found, deleted or measured.
    override fun deleteArtifacts(artifactsPath: ArtifactsPath?): Long = 0
    override fun deleteRuntimeArtifacts(runtimeArtifactsPath: RuntimeArtifactsPath?): Long = 0
    override fun deleteSdmSdcFiles(sdmSdcPaths: SecureDexMetadataWithCompanionPaths?): Long = 0
    override fun getArtifactsSize(artifactsPath: ArtifactsPath?): Long = 0
    override fun getVdexFileSize(vdexPath: VdexPath?): Long = 0
    override fun getSdmFileSize(sdmPath: SecureDexMetadataWithCompanionPaths?): Long = 0
    override fun getRuntimeArtifactsSize(runtimeArtifactsPath: RuntimeArtifactsPath?): Long = 0
    override fun getProfileSize(profile: ProfilePath?): Long = 0
    override fun getArtifactsVisibility(artifactsPath: ArtifactsPath?): Int = FileVisibility.NOT_FOUND
    override fun isInDalvikCache(dexFile: String?): Boolean = false

    // artd.cc GetDexoptStatus with no artifacts: the runtime runs from the APK.
    override fun getDexoptStatus(dexFile: String?, instructionSet: String?, classLoaderContext: String?): GetDexoptStatusResult =
        GetDexoptStatusResult().apply {
            compilerFilter = "run-from-apk"
            compilationReason = "unknown"
            locationDebugString = "hosted: no AOT artifacts"
            artifactsLocation = ArtifactsLocation.NONE_OR_ERROR
        }

    override fun getDexoptNeeded(dexFile: String?, instructionSet: String?, classLoaderContext: String?, compilerFilter: String?,
        dexoptTrigger: Int): GetDexoptNeededResult = GetDexoptNeededResult().apply {
        isDexoptNeeded = false
        isVdexUsable = false
        artifactsLocation = ArtifactsLocation.NONE_OR_ERROR
        hasDexCode = true
    }

    override fun dexopt(outputArtifacts: OutputArtifacts?, dexFile: String?, instructionSet: String?, classLoaderContext: String?,
        compilerFilter: String?, profile: ProfilePath?, inputVdex: VdexPath?, dmFile: DexMetadataPath?, priorityClass: Int,
        dexoptOptions: DexoptOptions?, cancellationSignal: IArtdCancellationSignal?): ArtdDexoptResult = throw unsupported("dexopt")

    override fun maybeCreateSdc(outputSdc: OutputSecureDexMetadataCompanion?) = throw unsupported("maybeCreateSdc")

    override fun createCancellationSignal(): IArtdCancellationSignal = object : IArtdCancellationSignal.Stub() {
        override fun cancel() {}
        override fun getType(): Long = 0
    }

    // Profiles: hosted processes save no profiles, so none is usable or found.
    override fun isProfileUsable(profile: ProfilePath?, dexFile: String?): Boolean = false
    override fun copyAndRewriteProfile(src: ProfilePath?, dst: OutputProfile?, dexFile: String?): CopyAndRewriteProfileResult =
        CopyAndRewriteProfileResult().apply { status = CopyAndRewriteProfileResult.Status.NO_PROFILE }
    override fun copyAndRewriteEmbeddedProfile(dst: OutputProfile?, dexFile: String?): CopyAndRewriteProfileResult =
        CopyAndRewriteProfileResult().apply { status = CopyAndRewriteProfileResult.Status.NO_PROFILE }
    override fun commitTmpProfile(profile: ProfilePath.TmpProfilePath?) = throw unsupported("commitTmpProfile")
    override fun deleteProfile(profile: ProfilePath?) {}
    override fun getProfileVisibility(profile: ProfilePath?): Int = FileVisibility.NOT_FOUND
    override fun mergeProfiles(profiles: MutableList<ProfilePath>?, referenceProfile: ProfilePath?, outputProfile: OutputProfile?,
        dexFiles: MutableList<String>?, options: MergeProfileOptions?): Boolean = false
    override fun initProfileSaveNotification(profilePath: ProfilePath.PrimaryCurProfilePath?, pid: Int): IArtdNotification =
        throw unsupported("initProfileSaveNotification")

    override fun getDexFileVisibility(dexFile: String?): Int = visibility(dexFile)
    override fun getDmFileVisibility(dmFile: DexMetadataPath?): Int =
        visibility(dmFile?.dexPath?.let { it.substringBeforeLast('.') + ".dm" })

    private fun visibility(path: String?): Int {
        val file = path?.let(::File) ?: return FileVisibility.NOT_FOUND
        return when {
            !file.exists() -> FileVisibility.NOT_FOUND
            file.canRead() -> FileVisibility.OTHER_READABLE
            else -> FileVisibility.NOT_OTHER_READABLE
        }
    }

    // There is nothing to collect besides the artifacts the caller keeps.
    override fun cleanup(profilesToKeep: MutableList<ProfilePath>?, artifactsToKeep: MutableList<ArtifactsPath>?,
        vdexFilesToKeep: MutableList<VdexPath>?, SdmSdcFilesToKeep: MutableList<SecureDexMetadataWithCompanionPaths>?,
        runtimeArtifactsToKeep: MutableList<RuntimeArtifactsPath>?, keepPreRebootStagedFiles: Boolean): Long = 0

    // Pre-reboot (OTA) dexopt needs a chroot of the next system image.
    override fun cleanUpPreRebootStagedFiles() {}
    override fun commitPreRebootStagedFiles(artifacts: MutableList<ArtifactsPath>?,
        profiles: MutableList<ProfilePath.WritableProfilePath>?): Boolean = false
    override fun checkPreRebootSystemRequirements(chrootDir: String?): Boolean = false
    override fun preRebootInit(cancellationSignal: IArtdCancellationSignal?): Boolean = false

    // artd path_utils.cc ValidateDexPath: an absolute, normalized path.
    override fun validateDexPath(dexFile: String?): String? = when {
        dexFile == null || !dexFile.startsWith("/") -> "Path '$dexFile' is not absolute"
        File(dexFile).normalize().path != dexFile -> "Path '$dexFile' is not in normal form"
        else -> null
    }

    override fun validateClassLoaderContext(dexFile: String?, classLoaderContext: String?): String? = validateDexPath(dexFile)
}
