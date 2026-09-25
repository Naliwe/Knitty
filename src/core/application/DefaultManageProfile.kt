package knitty.core.application

import knitty.core.model.*
import knitty.core.ports.*

class DefaultManageProfile(
    private val store: ProfileStore,
    planner: PlanInstall,
    private val locate: LocateGameInstallation,
    private val deployment: ProfileDeployment,
    private val download: DownloadArtifact,
    private val games: Map<GameId, ProviderGameId>,
    serverDeployment: ServerProfileDeployment? = null,
    serverControl: ServerControl? = null,
    private val newProfileId: () -> String,
) : ManageProfile {
    private val resolver = ProfileResolver(planner)
    private val servers = ServerProfileSync(serverDeployment, serverControl)

    override fun supports(game: GameId): Boolean = game in games

    override suspend fun add(game: GameId, reference: PackageReference): ProfileResult<ProfileEditPlan> =
        withProfile(game, create = true) { before, profile ->
            val previousPins = before.lock?.packages.orEmpty()
            val resolved = when (val result = resolver.resolve(game, reference, previousPins)) {
                is ProfileResult.Success -> result.value
                is ProfileResult.Failed -> return@withProfile result
            }

            val desired = profile.copy(packages = (profile.packages + resolved.requested).distinct())
            val pins = resolver.mergePins(previousPins, resolved)

            resolver.lock(desired, pins).map { lock ->
                ProfileEditPlan(before, desired, lock)
            }
        }

    override suspend fun remove(game: GameId, id: PackageId): ProfileResult<ProfileEditPlan> =
        withProfile(game) { before, profile ->
            if (id !in profile.packages) return@withProfile ProfileResult.Failed(ProfileFailure.PackageNotInProfile)

            val desired = profile.copy(packages = profile.packages - id)

            resolver.lock(desired, before.lock?.packages.orEmpty()).map { lock ->
                ProfileEditPlan(before, desired, lock)
            }
        }

    override suspend fun lock(game: GameId): ProfileResult<ProfileEditPlan> = withProfile(game) { before, profile ->
        resolver.lock(profile, before.lock?.packages.orEmpty()).map { lock ->
            ProfileEditPlan(before, profile, lock)
        }
    }

    override suspend fun save(plan: ProfileEditPlan): ProfileResult<Unit> = store.save(plan)

    override suspend fun planUpdate(game: GameId): ProfileResult<ProfileEditPlan> =
        withProfile(game) { before, profile ->
            val lock = before.lock
                ?: return@withProfile ProfileResult.Failed(ProfileFailure.LockOutOfDate)
            if (!lock.isCompleteFor(profile)) return@withProfile ProfileResult.Failed(ProfileFailure.LockOutOfDate)

            resolver.lock(profile, emptyList()).map { refreshed ->
                val previous = lock.packages.associateBy { it.id }
                val packages = refreshed.packages.map { current ->
                    val pinned = previous[current.id]
                    // Published file identity is authoritative; dependency declarations can change independently.
                    if (pinned != null && current.artifact.version.artifactId == pinned.artifact.version.artifactId) {
                        pinned.copy(dependencies = current.dependencies)
                    } else {
                        current
                    }
                }

                ProfileEditPlan(before, profile, lock.copy(packages = packages))
            }
        }

    override suspend fun planSync(
        game: GameId,
        directory: String?,
        target: String?,
        progress: ProgressSink,
    ): ProfileResult<SyncPlan> =
        withProfile(game) { source, profile ->
            progress.report(OperationProgress(ProgressStage.Checking))

            val lock = source.lock
                ?: return@withProfile ProfileResult.Failed(ProfileFailure.LockOutOfDate)
            if (!lock.isCompleteFor(profile)) return@withProfile ProfileResult.Failed(ProfileFailure.LockOutOfDate)

            if (target != null) {
                if (directory != null) return@withProfile serverFailure(ServerFailure.InvalidTarget)
                return@withProfile servers.plan(source, profile, target, progress)
            }

            val installation = when (val result = locate.locate(game, directory)) {
                is InstallationOutcome.Found -> result.installation
                is InstallationOutcome.Failed -> {
                    val failure = ProfileFailure.Deployment(result.failure)
                    return@withProfile ProfileResult.Failed(failure)
                }
            }
            val before = when (val result = deployment.inspect(installation, profile.id)) {
                is ProfileResult.Success -> result.value
                is ProfileResult.Failed -> return@withProfile result
            }

            planProfileSync(source, installation, before)
        }

    override suspend fun apply(plan: SyncPlan, progress: ProgressSink): ProfileResult<Unit> =
        store.withSnapshot(plan.source) {
            val providerGame = games[plan.installation.game]
                ?: return@withSnapshot ProfileResult.Failed(ProfileFailure.WrongGame)

            if (plan.server == null) {
                deployment.apply(plan, progress) { change, sink -> download(providerGame, change, sink, progress) }
            } else {
                servers.apply(plan, progress) { change, sink -> download(providerGame, change, sink, progress) }
            }
        }

    private suspend fun download(
        game: ProviderGameId,
        change: Change.Install,
        sink: ArtifactSink,
        progress: ProgressSink,
    ): DownloadOutcome {
        var received = 0L
        val transfer = OperationProgress(ProgressStage.Downloading, change.name, total = change.artifact.sizeBytes)
        progress.report(transfer)

        return download.download(game, change) { bytes, count ->
            sink.write(bytes, count)
            received += count
            progress.report(transfer.copy(completed = received))
        }
    }

    private fun serverFailure(failure: ServerFailure): ProfileResult.Failed =
        ProfileResult.Failed(ProfileFailure.Server(failure))

    private suspend fun <T> withProfile(
        game: GameId,
        create: Boolean = false,
        action: suspend (ProfileSnapshot, Profile) -> ProfileResult<T>,
    ): ProfileResult<T> {
        if (!supports(game)) {
            return ProfileResult.Failed(ProfileFailure.Planning(PlanInstallFailure.UnsupportedGame(game)))
        }

        val before = when (val result = store.read()) {
            is ProfileResult.Success -> result.value
            is ProfileResult.Failed -> return result
        }

        val profile = before.profile
            ?: if (create) {
                Profile(newProfileId(), game, emptyList())
            } else {
                return ProfileResult.Failed(ProfileFailure.MissingProfile)
            }
        if (profile.game != game) return ProfileResult.Failed(ProfileFailure.WrongGame)

        return action(before, profile)
    }
}
