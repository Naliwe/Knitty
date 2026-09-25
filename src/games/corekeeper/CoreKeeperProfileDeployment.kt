package knitty.games.corekeeper

import knitty.core.model.*
import knitty.core.ports.ArtifactSink
import knitty.core.ports.ProfileDeployment
import knitty.filesystem.withExclusiveFileLock
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.security.MessageDigest
import kotlin.io.path.*

class CoreKeeperProfileDeployment(
    private val publish: CommitModDirectory = CommitModDirectory { staged, target ->
        Files.move(
            staged,
            target,
            ATOMIC_MOVE,
        )
    },
) : ProfileDeployment {
    private val json = Json { encodeDefaults = true }

    override suspend fun inspect(
        installation: GameInstallation,
        profileId: String,
    ): ProfileResult<InstallationSnapshot> =
        withContext(Dispatchers.IO) { guarded { inspectFiles(installation, profileId) } }

    override suspend fun apply(
        plan: SyncPlan,
        progress: ProgressSink,
        download: suspend (Change.Install, ArtifactSink) -> DownloadOutcome,
    ): ProfileResult<Unit> = withContext(Dispatchers.IO) {
        guarded {
            val installation = plan.installation
            val state = Path(installation.modsDirectory).parent.resolve(".knitty")
            if (!safeAncestors(state)) abort(ProfileFailure.Deployment(ApplyFailure.DeploymentConflict))
            state.createDirectories()
            withExclusiveFileLock(state.resolve("install.lock"), onBusy = { abort(ProfileFailure.Busy) }) {
                applyLocked(plan, state, progress, download)
            }
        }
    }

    private suspend fun applyLocked(
        plan: SyncPlan,
        state: Path,
        progress: ProgressSink,
        download: suspend (Change.Install, ArtifactSink) -> DownloadOutcome,
    ) = withContext(Dispatchers.IO) {
        val profile = plan.source.profile
            ?: abort(ProfileFailure.InvalidProfile)
        val locked = plan.source.lock
            ?: abort(ProfileFailure.InvalidLock)
        progress.report(OperationProgress(ProgressStage.Checking))
        val before = inspectFiles(plan.installation, profile.id)
        if (before != plan.before) abort(ProfileFailure.StalePlan)

        if (!locked.isCompleteFor(profile)) abort(ProfileFailure.InvalidLock)
        if (plan.remove.any { it.id !in before.managed } || plan.install.any { it !in locked.packages } ||
            (plan.install.map { it.id } + plan.keep.map { it.id }).toSet() != locked.packages.map { it.id }.toSet()) {
            abort(ProfileFailure.InvalidLock)
        }
        val mods = Path(plan.installation.modsDirectory)
        val ledger = ManagedProfile(
            profileId = profile.id,
            packages = locked.packages.map { "${it.id.provider}:${it.id.value}" },
        )
        if (plan.install.isEmpty() && plan.remove.isEmpty() && readLedger(mods) == ledger) return@withContext

        val staging = Files.createTempDirectory(state, "profile-staging-")
        try {
            staging.resolve("CoreKeeper").writeText("")
            val stagedMods = staging.resolve("CoreKeeper_Data/StreamingAssets/Mods").createDirectories()
            progress.report(OperationProgress(ProgressStage.Preparing))
            copyTree(mods, stagedMods)
            for ((id) in plan.remove) {
                deleteStaging(stagedMods.resolve(installationFolder(id)))
            }

            val stagedInstallation = GameInstallation(
                game = profile.game,
                directory = staging.toRealPath().toString(),
                modsDirectory = stagedMods.toRealPath().toString(),
            )
            val installer = CoreKeeperDeployment()
            for ((index, item) in plan.install.withIndex()) {
                val change = item.install()
                val result = installer.installInProfileStaging(stagedInstallation, change) { sink ->
                    download(change, sink).also {
                        if (it == DownloadOutcome.Downloaded) {
                            progress.report(OperationProgress(ProgressStage.Verifying, item.name))
                        }
                    }
                }
                if (result is ApplyOutcome.Failed) abort(ProfileFailure.Deployment(result.failure))
                progress.report(
                    OperationProgress(
                        ProgressStage.Preparing, item.name, index + 1L, plan.install.size.toLong(), ProgressUnit.Items,
                    ),
                )
            }

            progress.report(OperationProgress(ProgressStage.Verifying))
            val manifests = locked.packages.map { item ->
                currentCoroutineContext().ensureActive()
                readManifest(stagedMods.resolve(installationFolder(item.id)).resolve("ModManifest.json"))
            }
            validateNativeDependencies(manifests)

            stagedMods.resolve(ledgerName).writeText(json.encodeToString(ledger))
            if (inspectFiles(plan.installation, profile.id) != before) abort(ProfileFailure.StalePlan)
            currentCoroutineContext().ensureActive()

            val backup = state.resolve("profile-backup")
            progress.report(OperationProgress(ProgressStage.Committing))
            withContext(NonCancellable) {
                val hadMods = mods.exists(NOFOLLOW_LINKS)
                if (hadMods) Files.move(mods, backup, ATOMIC_MOVE)
                try {
                    publish.commit(stagedMods, mods)
                } catch (failure: IOException) {
                    if (hadMods) {
                        try {
                            Files.move(backup, mods, ATOMIC_MOVE)
                        } catch (_: IOException) {
                            abort(ProfileFailure.RecoveryRequired)
                        }
                    }
                    throw failure
                }
                if (hadMods) {
                    try {
                        deleteStaging(backup)
                    } catch (_: IOException) {
                        abort(ProfileFailure.RecoveryRequired)
                    }
                }
            }
        } finally {
            try {
                deleteStaging(staging)
            } catch (_: IOException) {
                // Leftover staging is outside Mods and cannot be loaded by the game.
            }
        }
    }

    private suspend fun inspectFiles(
        installation: GameInstallation,
        profileId: String,
    ): InstallationSnapshot = withContext(Dispatchers.IO) {
        val mods = Path(installation.modsDirectory)
        val backup = mods.parent.resolve(".knitty/profile-backup")
        if (backup.exists(NOFOLLOW_LINKS)) abort(ProfileFailure.RecoveryRequired)
        val located = CoreKeeperInstallation().locate(installation.game, installation.directory)
        if (located !is InstallationOutcome.Found || located.installation != installation) {
            abort(ProfileFailure.Deployment(ApplyFailure.InstallationNotFound))
        }
        if (!safeAncestors(mods)) abort(ProfileFailure.Deployment(ApplyFailure.DeploymentConflict))
        val ledger = readLedger(mods)
        if (ledger != null && ledger.profileId != profileId) abort(ProfileFailure.DifferentProfile)
        val managed = ledger?.packages.orEmpty().map { parsePackageId(it) }.toSet()
        val packages = mutableListOf<InstalledPackage>()
        val other = mutableListOf<String>()
        if (mods.exists()) {
            for (path in mods.listDirectoryEntries().sorted()) {
                if (path.name == ledgerName) continue
                if (path.isSymbolicLink()) abort(ProfileFailure.ModifiedInstallation)
                val receipt = path.resolve(receiptName)
                if (!receipt.exists(NOFOLLOW_LINKS)) {
                    other += path.name
                    continue
                }
                if (!receipt.isRegularFile(NOFOLLOW_LINKS) || receipt.fileSize() > 4 * 1024 * 1024) {
                    abort(ProfileFailure.ModifiedInstallation)
                }

                val owned = try {
                    json.decodeFromString<OwnershipReceipt>(receipt.readText())
                } catch (_: SerializationException) {
                    abort(ProfileFailure.ModifiedInstallation)
                }
                val id = parsePackageId("${owned.provider}:${owned.packageId}")
                if (owned.schemaVersion != 1 || path.name != installationFolder(id) ||
                    owned.artifactId.isBlank()
                ) {
                    abort(ProfileFailure.ModifiedInstallation)
                }

                val actual = fingerprintFiles(path).filterKeys { it != receiptName }
                if (actual != owned.files) abort(ProfileFailure.ModifiedInstallation)
                val manifest = readManifest(path.resolve("ModManifest.json"))
                if (manifest.guid != owned.manifestGuid) abort(ProfileFailure.ModifiedInstallation)

                packages += InstalledPackage(
                    id,
                    ArtifactId(owned.artifactId),
                    manifest.name,
                    owned.archiveMd5?.let { Checksum(ChecksumAlgorithm.MD5, it.lowercase()) },
                )
            }
        }

        val digest = MessageDigest.getInstance("SHA-256")
        if (mods.exists()) {
            Files.walk(mods).use { paths ->
                for (directory in paths.filter { it.isDirectory(NOFOLLOW_LINKS) }.sorted().toList()) {
                    val relativePath = mods.relativize(directory).invariantSeparatorsPathString
                    digest.update("$relativePath/\u0000".encodeToByteArray())
                }
            }
        }
        for ((path, hash) in fingerprintFiles(mods)) {
            digest.update(path.encodeToByteArray())
            digest.update(0.toByte())
            digest.update(hash.encodeToByteArray())
        }
        InstallationSnapshot(packages, managed, other, digest.digest().toHexString())
    }

    private fun readLedger(mods: Path): ManagedProfile? {
        val file = mods.resolve(ledgerName)
        if (!file.exists(NOFOLLOW_LINKS)) return null
        if (!file.isRegularFile(NOFOLLOW_LINKS) || file.fileSize() > 128 * 1024) {
            abort(ProfileFailure.ModifiedInstallation)
        }

        return try {
            val ledger = json.decodeFromString<ManagedProfile>(file.readText())
            if (ledger.schemaVersion != 1 || ledger.packages.size > 500) {
                abort(ProfileFailure.ModifiedInstallation)
            }
            if (ledger.packages.distinct().size != ledger.packages.size) {
                abort(ProfileFailure.ModifiedInstallation)
            }
            ledger
        } catch (_: SerializationException) {
            abort(ProfileFailure.ModifiedInstallation)
        }
    }

    private suspend fun copyTree(source: Path, target: Path) = withContext(Dispatchers.IO) {
        if (!source.exists()) return@withContext

        Files.walk(source).use { paths ->
            for (path in paths.toList()) {
                currentCoroutineContext().ensureActive()
                if (path == source) continue
                if (path.isSymbolicLink()) abort(ProfileFailure.ModifiedInstallation)
                val destination = target.resolve(source.relativize(path))
                val ordinaryFile = path.isDirectory(NOFOLLOW_LINKS) || path.isRegularFile(NOFOLLOW_LINKS)
                if (!ordinaryFile) abort(ProfileFailure.ModifiedInstallation)

                Files.copy(path, destination, COPY_ATTRIBUTES)
            }
        }
    }

    private suspend fun fingerprintFiles(root: Path): Map<String, String> = withContext(Dispatchers.IO) {
        if (!root.exists(NOFOLLOW_LINKS)) return@withContext emptyMap()

        Files.walk(root).use { paths ->
            val unsafePath = paths.anyMatch { path ->
                path.isSymbolicLink() ||
                    (!path.isDirectory(NOFOLLOW_LINKS) && !path.isRegularFile(NOFOLLOW_LINKS))
            }
            if (unsafePath) {
                abort(ProfileFailure.ModifiedInstallation)
            }
        }
        hashes(root)
    }

    private fun parsePackageId(text: String): PackageId {
        if (!Regex("[a-z][a-z0-9-]*:[A-Za-z0-9._-]+").matches(text)) abort(ProfileFailure.ModifiedInstallation)
        return PackageId(text.substringBefore(':'), text.substringAfter(':'))
    }

    private suspend fun <T> guarded(block: suspend () -> T): ProfileResult<T> = try {
        ProfileResult.Success(block())
    } catch (failure: SyncFailure) {
        ProfileResult.Failed(failure.failure)
    } catch (failure: DeploymentFailure) {
        ProfileResult.Failed(ProfileFailure.Deployment(failure.failure))
    } catch (_: IOException) {
        ProfileResult.Failed(ProfileFailure.FilesystemFailure)
    }
}

private const val ledgerName = ".knitty-profile.json"

@Serializable
private data class ManagedProfile(
    val schemaVersion: Int = 1,
    val profileId: String,
    val packages: List<String>,
)

private class SyncFailure(val failure: ProfileFailure) : Exception()

private fun abort(failure: ProfileFailure): Nothing = throw SyncFailure(failure)
