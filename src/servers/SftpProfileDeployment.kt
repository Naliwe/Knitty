package knitty.servers

import knitty.core.model.*
import knitty.core.ports.ArtifactSink
import knitty.core.ports.LocateGameInstallation
import knitty.core.ports.ProfileDeployment
import knitty.core.ports.ServerProfileDeployment
import knitty.filesystem.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class SftpProfileDeployment(
    private val files: OpenRemoteFiles,
    private val layouts: Map<GameId, ServerLayout>,
    private val locate: LocateGameInstallation,
    private val deployment: ProfileDeployment,
) : ServerProfileDeployment {
    override suspend fun inspect(
        game: GameId,
        target: ServerTarget,
        profileId: String,
        progress: ProgressSink,
    ): ProfileResult<ServerInspection> = guarded {
        progress.report(OperationProgress(ProgressStage.Checking))
        val layout = layout(game)
        files.open(target).use { remote ->
            validateInstallation(remote, layout)
            checkTransaction(remote, layout)
            temporary { root ->
                val mirror = mirror(remote, game, layout, root, profileId, progress = progress)
                ServerInspection(
                    GameInstallation(game, "/", "/${layout.modsDirectory}"),
                    mirror.snapshot.copy(revision = mirror.tree.revision),
                )
            }
        }
    }

    override suspend fun apply(
        plan: SyncPlan,
        progress: ProgressSink,
        download: suspend (Change.Install, ArtifactSink) -> DownloadOutcome,
        beforeCommit: suspend () -> ProfileResult<Unit>,
        checkStopped: suspend () -> ProfileResult<Unit>,
    ): ProfileResult<Unit> = guarded {
        val server = plan.server
            ?: abort(ProfileFailure.Server(ServerFailure.InvalidTarget))
        val profile = plan.source.profile
            ?: abort(ProfileFailure.InvalidProfile)
        val game = plan.installation.game
        progress.report(OperationProgress(ProgressStage.Checking))
        val layout = layout(game)
        if (plan.installation != GameInstallation(game, "/", "/${layout.modsDirectory}")) {
            abort(ProfileFailure.Server(ServerFailure.InvalidTarget))
        }

        files.open(server.target).use { remote ->
            validateInstallation(remote, layout)
            checkTransaction(remote, layout)
            val transaction = transaction(layout)
            // mkdir is exclusive. A lost acknowledgement deliberately leaves a recovery marker.
            remote.mkdir(transaction)
            var committing = false
            try {
                temporary { root ->
                    val before = mirror(remote, game, layout, root.resolve("before"), profile.id, progress = progress)
                    if (before.snapshot.copy(revision = before.tree.revision) != plan.before) {
                        abort(ProfileFailure.StalePlan)
                    }

                    val localPlan = planProfileSync(plan.source, before.installation, before.snapshot).value()
                    val reviewed =
                        plan.copy(installation = before.installation, before = before.snapshot, server = null)
                    if (localPlan != reviewed) abort(ProfileFailure.StalePlan)

                    deployment.apply(
                        localPlan,
                        { update ->
                            progress.report(
                                if (update.stage == ProgressStage.Committing) {
                                    update.copy(stage = ProgressStage.Preparing)
                                } else {
                                    update
                                },
                            )
                        },
                        download,
                    ).value()
                    val prepared = deployment.inspect(before.installation, profile.id).value()
                    if (prepared == before.snapshot) return@temporary

                    val staged = "$transaction/staged"
                    remote.uploadTree(
                        Path(before.installation.modsDirectory),
                        staged,
                        before.tree.permissions,
                        progress,
                    )
                    val uploaded = mirror(
                        remote,
                        game,
                        layout,
                        root.resolve("uploaded"),
                        profile.id,
                        staged,
                        progress,
                        ProgressStage.Verifying,
                    )
                    if (uploaded.snapshot != prepared) abort(ProfileFailure.ModifiedInstallation)

                    beforeCommit().value()
                    val current = mirror(
                        remote,
                        game,
                        layout,
                        root.resolve("recheck"),
                        profile.id,
                        progress = progress,
                        stage = ProgressStage.Verifying,
                    )
                    if (current.tree != before.tree) abort(ProfileFailure.StalePlan)

                    val hadMods = remote.stat("/${layout.modsDirectory}") != null
                    val journal = root.resolve("transaction.json")
                    journal.writeText(
                        Json.encodeToString(
                            ServerTransaction(
                                schemaVersion = 1,
                                profileId = profile.id,
                                modsDirectory = "/${layout.modsDirectory}",
                                hadMods = hadMods,
                                beforeRevision = before.tree.revision,
                                afterRevision = uploaded.tree.revision,
                                serverId = server.target.serverId,
                                restartAfterCommit = server.before.power == ServerPowerState.Running,
                            ),
                        ),
                    )
                    remote.upload(journal, "$transaction/transaction.json")
                    checkStopped().value()
                    currentCoroutineContext().ensureActive()

                    progress.report(OperationProgress(ProgressStage.Committing))
                    committing = true
                    withContext(NonCancellable) {
                        try {
                            commit(remote, plan, layout, root, uploaded.tree, hadMods, progress)
                            remote.deleteTree(transaction)
                        } catch (_: IOException) {
                            abort(ProfileFailure.Server(ServerFailure.RecoveryRequired))
                        }
                        committing = false
                    }
                }
            } finally {
                if (!committing) remote.deleteTree(transaction)
            }
        }
    }

    private suspend fun commit(
        remote: RemoteFiles,
        plan: SyncPlan,
        layout: ServerLayout,
        root: Path,
        expected: RemoteTree,
        hadMods: Boolean,
        progress: ProgressSink,
    ) {
        val mods = "/${layout.modsDirectory}"
        val transaction = transaction(layout)
        val backup = "$transaction/backup"
        try {
            if (hadMods) remote.rename(mods, backup)
            remote.rename("$transaction/staged", mods)
            val verified = remote.downloadTree(mods, root.resolve("verified"), progress, ProgressStage.Verifying)
            if (verified != expected) abort(ProfileFailure.Server(ServerFailure.RecoveryRequired))
        } catch (_: IOException) {
            // A rename may have succeeded despite a lost reply. Restore only when its outcome is unambiguous.
            try {
                if (hadMods && remote.stat(mods) == null && remote.stat(backup) != null) {
                    remote.rename(backup, mods)
                    val restored = remote.downloadTree(mods, root.resolve("restored"))
                    if (restored.revision == plan.before.revision) {
                        remote.deleteTree(transaction)
                        abort(ProfileFailure.Server(ServerFailure.CommitRolledBack))
                    }
                }
            } catch (_: IOException) {
                // Keep the journal and backup for recovery after reconnecting.
            }
            abort(ProfileFailure.Server(ServerFailure.RecoveryRequired))
        }
    }

    private suspend fun mirror(
        remote: RemoteFiles,
        game: GameId,
        layout: ServerLayout,
        root: Path,
        profileId: String,
        source: String = "/${layout.modsDirectory}",
        progress: ProgressSink = ProgressSink.None,
        stage: ProgressStage = ProgressStage.Checking,
    ): Mirror {
        root.createDirectories()
        root.resolve(layout.executable).writeText("")
        val mods = root.resolve(layout.modsDirectory)
        mods.parent.createDirectories()
        val tree = remote.downloadTree(source, mods, progress, stage)
        val installation = when (val result = locate.locate(game, root.toString())) {
            is InstallationOutcome.Found -> result.installation
            is InstallationOutcome.Failed -> abort(ProfileFailure.Deployment(result.failure))
        }
        return Mirror(installation, deployment.inspect(installation, profileId).value(), tree)
    }

    private suspend fun validateInstallation(remote: RemoteFiles, layout: ServerLayout) {
        if (remote.stat("/${layout.executable}")?.type != RemoteFileType.File) {
            abort(ProfileFailure.Deployment(ApplyFailure.InstallationNotFound))
        }
        var path = ""
        for (part in layout.modsDirectory.substringBeforeLast('/').split('/')) {
            path += "/$part"
            if (remote.stat(path)?.type != RemoteFileType.Directory) {
                abort(ProfileFailure.Server(ServerFailure.InvalidTarget))
            }
        }
        val mods = remote.stat("/${layout.modsDirectory}")
        if (mods != null && mods.type != RemoteFileType.Directory) {
            abort(ProfileFailure.Server(ServerFailure.InvalidTarget))
        }
    }

    private suspend fun checkTransaction(remote: RemoteFiles, layout: ServerLayout) {
        if (remote.stat(transaction(layout)) != null) abort(ProfileFailure.Server(ServerFailure.RecoveryRequired))
    }

    private fun transaction(layout: ServerLayout): String =
        "/${layout.modsDirectory.substringBeforeLast('/')}/.knitty-server-sync"

    private fun layout(game: GameId): ServerLayout = layouts[game]
        ?: abort(ProfileFailure.WrongGame)

    private suspend fun <T> temporary(action: suspend (Path) -> T): T = withContext(Dispatchers.IO) {
        val directory = Files.createTempDirectory("knitty-server-")
        try {
            action(directory)
        } finally {
            deleteLocalTree(directory)
        }
    }

    private suspend fun <T> guarded(action: suspend () -> T): ProfileResult<T> = withContext(Dispatchers.IO) {
        try {
            ProfileResult.Success(action())
        } catch (failure: ServerDeploymentFailure) {
            ProfileResult.Failed(failure.failure)
        } catch (failure: RemoteFileFailure) {
            ProfileResult.Failed(ProfileFailure.Server(failure.failure))
        } catch (_: IOException) {
            ProfileResult.Failed(ProfileFailure.Server(ServerFailure.ConnectionFailed))
        }
    }
}

private data class Mirror(
    val installation: GameInstallation,
    val snapshot: InstallationSnapshot,
    val tree: RemoteTree,
)

private class ServerDeploymentFailure(val failure: ProfileFailure) : Exception()

@Serializable
private data class ServerTransaction(
    val schemaVersion: Int,
    val profileId: String,
    val modsDirectory: String,
    val hadMods: Boolean,
    val beforeRevision: String,
    val afterRevision: String,
    val serverId: String,
    val restartAfterCommit: Boolean,
)

private fun abort(failure: ProfileFailure): Nothing = throw ServerDeploymentFailure(failure)

private fun <T> ProfileResult<T>.value(): T = when (this) {
    is ProfileResult.Success -> value
    is ProfileResult.Failed -> abort(failure)
}
