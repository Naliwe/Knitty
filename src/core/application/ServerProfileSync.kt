package knitty.core.application

import knitty.core.model.*
import knitty.core.ports.ArtifactSink
import knitty.core.ports.ServerControl
import knitty.core.ports.ServerProfileDeployment

internal class ServerProfileSync(
    private val deployment: ServerProfileDeployment?,
    private val controlPort: ServerControl?,
) {
    suspend fun plan(
        source: ProfileSnapshot,
        profile: Profile,
        name: String,
        progress: ProgressSink,
    ): ProfileResult<SyncPlan> {
        val target = profile.servers.singleOrNull { it.name == name }
            ?: return serverFailure(ServerFailure.UnknownTarget)
        val remote = deployment
            ?: return serverFailure(ServerFailure.InvalidTarget)
        val control = controlPort
            ?: return serverFailure(ServerFailure.InvalidTarget)

        val status = when (val result = control.inspect(target)) {
            is ProfileResult.Success -> result.value
            is ProfileResult.Failed -> return result
        }
        if (status.power != ServerPowerState.Running && status.power != ServerPowerState.Offline) {
            return serverFailure(ServerFailure.ServerBusy)
        }

        val inspected = when (val result = remote.inspect(profile.game, target, profile.id, progress)) {
            is ProfileResult.Success -> result.value
            is ProfileResult.Failed -> return result
        }

        return planProfileSync(source, inspected.installation, inspected.snapshot).map {
            it.copy(server = ServerSync(target, status))
        }
    }

    suspend fun apply(
        plan: SyncPlan,
        progress: ProgressSink,
        download: suspend (Change.Install, ArtifactSink) -> DownloadOutcome,
    ): ProfileResult<Unit> {
        val server = plan.server
            ?: return serverFailure(ServerFailure.InvalidTarget)
        if (server.target !in plan.source.profile?.servers.orEmpty()) {
            return serverFailure(ServerFailure.InvalidTarget)
        }

        val remote = deployment
            ?: return serverFailure(ServerFailure.InvalidTarget)
        val control = controlPort
            ?: return serverFailure(ServerFailure.InvalidTarget)

        var stopped = false
        val result = remote.apply(
            plan,
            progress = progress,
            download = download,
            beforeCommit = {
                when (val current = control.inspect(server.target)) {
                    is ProfileResult.Failed -> current
                    is ProfileResult.Success -> {
                        if (current.value != server.before) {
                            ProfileResult.Failed(ProfileFailure.StalePlan)
                        } else if (current.value.power == ServerPowerState.Running) {
                            progress.report(OperationProgress(ProgressStage.StoppingServer))
                            control.stop(server.target).also { stopped = it is ProfileResult.Success }
                        } else {
                            ProfileResult.Success(Unit)
                        }
                    }
                }
            },
            checkStopped = {
                when (val current = control.inspect(server.target)) {
                    is ProfileResult.Failed -> current
                    is ProfileResult.Success -> {
                        val expected = server.before.copy(power = ServerPowerState.Offline)
                        if (current.value != expected) {
                            ProfileResult.Failed(ProfileFailure.StalePlan)
                        } else {
                            ProfileResult.Success(Unit)
                        }
                    }
                }
            },
        )

        if (result is ProfileResult.Failed) {
            return if (stopped) {
                ProfileResult.Failed(ProfileFailure.ServerStopped(result.failure))
            } else {
                result
            }
        }

        if (stopped) {
            progress.report(OperationProgress(ProgressStage.StartingServer))
            if (control.start(server.target) is ProfileResult.Failed) return serverFailure(ServerFailure.RestartFailed)
        }

        return result
    }

    private fun serverFailure(failure: ServerFailure): ProfileResult.Failed =
        ProfileResult.Failed(ProfileFailure.Server(failure))
}
