package knitty.games.corekeeper

import knitty.core.model.*
import knitty.core.ports.ArtifactSink
import knitty.core.ports.GameDeployment
import knitty.filesystem.withExclusiveFileLock
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import kotlin.io.path.*

fun interface CommitModDirectory {
    fun commit(staged: Path, target: Path)
}

class CoreKeeperDeployment(
    private val commitDirectory: CommitModDirectory = CommitModDirectory { staged, target ->
        Files.move(staged, target, ATOMIC_MOVE)
    },
) : GameDeployment {
    private val receiptJson = Json { encodeDefaults = true }

    override suspend fun install(
        installation: GameInstallation,
        change: Change.Install,
        download: suspend (ArtifactSink) -> DownloadOutcome,
    ): ApplyOutcome = install(installation, change, download, checkDependencies = true)

    // Profile staging validates the complete final set after every archive has been prepared.
    internal suspend fun installInProfileStaging(
        installation: GameInstallation,
        change: Change.Install,
        download: suspend (ArtifactSink) -> DownloadOutcome,
    ): ApplyOutcome = install(installation, change, download, checkDependencies = false)

    private suspend fun install(
        installation: GameInstallation,
        change: Change.Install,
        download: suspend (ArtifactSink) -> DownloadOutcome,
        checkDependencies: Boolean,
    ): ApplyOutcome = withContext(Dispatchers.IO) {
        var staging: Path? = null
        try {
            val located = CoreKeeperInstallation().locate(installation.game, installation.directory)
            if (located !is InstallationOutcome.Found || located.installation != installation) {
                return@withContext ApplyOutcome.Failed(ApplyFailure.InstallationNotFound)
            }
            if (change.artifact.sizeBytes !in 1..512L * 1024 * 1024) {
                return@withContext ApplyOutcome.Failed(ApplyFailure.InvalidArchive)
            }

            val mods = Path(installation.modsDirectory)
            val state = mods.parent.resolve(".knitty")
            if (!safeAncestors(state)) fail(ApplyFailure.DeploymentConflict)
            state.createDirectories()
            val lockPath = state.resolve("install.lock")
            if (lockPath.isSymbolicLink()) fail(ApplyFailure.DeploymentConflict)
            withExclusiveFileLock(lockPath, onBusy = { fail(ApplyFailure.InstallationBusy) }) {
                val target = mods.resolve(installationFolder(change.id))
                if (target.exists(NOFOLLOW_LINKS)) fail(ApplyFailure.DeploymentConflict)

                val transactionDirectory = Files.createTempDirectory(state, "staging-")
                staging = transactionDirectory
                val archive = transactionDirectory.resolve("artifact.zip")
                downloadChecked(archive, change.artifact, download)

                val unpacked = transactionDirectory.resolve("unpacked").createDirectory()
                val (payload, manifest) = extractNativeMod(archive, unpacked)
                checkConflicts(mods, target, manifest)
                if (checkDependencies) validateNativeDependencies(listOf(manifest))

                val ownedFiles = hashes(payload)
                val receipt = OwnershipReceipt(
                    provider = change.id.provider,
                    packageId = change.id.value,
                    artifactId = change.artifact.version.artifactId.value,
                    version = change.artifact.version.label,
                    archiveMd5 = change.artifact.checksum?.value,
                    manifestGuid = manifest.guid,
                    files = ownedFiles,
                )
                payload.resolve(receiptName).writeText(receiptJson.encodeToString(receipt))
                currentCoroutineContext().ensureActive()

                // Files and ownership become visible together; there is no second state commit to fail.
                withContext(NonCancellable) {
                    checkConflicts(mods, target, manifest)
                    mods.createDirectories()
                    commitDirectory.commit(payload, target)
                    ApplyOutcome.Installed(target.toString())
                }
            }
        } catch (failure: DeploymentFailure) {
            ApplyOutcome.Failed(failure.failure)
        } catch (_: AtomicMoveNotSupportedException) {
            ApplyOutcome.Failed(ApplyFailure.AtomicCommitUnavailable)
        } catch (_: IOException) {
            ApplyOutcome.Failed(ApplyFailure.FilesystemFailure)
        } finally {
            staging?.let { path ->
                // Staging is outside the game's Mods directory; a failed cleanup cannot expose a partial mod.
                try {
                    deleteStaging(path)
                } catch (_: IOException) {
                    // The committed result still stands; leftover staging can be removed while Knitty is stopped.
                }
            }
        }
    }

    private suspend fun downloadChecked(
        path: Path,
        artifact: PackageArtifact,
        download: suspend (ArtifactSink) -> DownloadOutcome,
    ) {
        val digest = MessageDigest.getInstance("MD5")
        var count = 0L
        val outcome = path.outputStream(CREATE_NEW).buffered().use { output ->
            download(
                ArtifactSink { bytes, length ->
                    currentCoroutineContext().ensureActive()
                    count += length
                    if (count > artifact.sizeBytes) fail(ApplyFailure.ArtifactChanged)
                    digest.update(bytes, 0, length)
                    try {
                        output.write(bytes, 0, length)
                    } catch (_: IOException) {
                        fail(ApplyFailure.FilesystemFailure)
                    }
                },
            )
        }
        if (outcome is DownloadOutcome.Failed) fail(outcome.failure)
        if (count != artifact.sizeBytes) fail(ApplyFailure.ArtifactChanged)
        val checksum = artifact.checksum
        if (checksum != null && !digest.digest().toHexString().equals(checksum.value, ignoreCase = true)) {
            fail(ApplyFailure.ChecksumMismatch)
        }
    }

    private fun checkConflicts(mods: Path, target: Path, manifest: NativeManifest) {
        if (!safeAncestors(mods) || target.exists(NOFOLLOW_LINKS)) fail(ApplyFailure.DeploymentConflict)
        if (!mods.exists()) return
        if (!mods.isDirectory(NOFOLLOW_LINKS)) fail(ApplyFailure.DeploymentConflict)
        for (directory in mods.listDirectoryEntries()) {
            if (directory.isSymbolicLink()) fail(ApplyFailure.DeploymentConflict)
            val existing = directory.resolve("ModManifest.json")
            if (!existing.exists(NOFOLLOW_LINKS)) continue
            if (!existing.isRegularFile(NOFOLLOW_LINKS)) fail(ApplyFailure.DeploymentConflict)
            val other = try {
                readManifest(existing)
            } catch (_: DeploymentFailure) {
                fail(ApplyFailure.DeploymentConflict)
            }
            val sameGuid = other.guid.equals(manifest.guid, ignoreCase = true)
            val sameName = other.name.equals(manifest.name, ignoreCase = true)
            if (sameGuid || sameName) fail(ApplyFailure.DeploymentConflict)
        }
    }
}


private fun fail(failure: ApplyFailure): Nothing = throw DeploymentFailure(failure)
