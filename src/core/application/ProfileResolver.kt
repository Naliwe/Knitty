package knitty.core.application

import knitty.core.model.*

internal class ProfileResolver(private val planner: PlanInstall) {
    suspend fun lock(profile: Profile, existing: List<LockedPackage>): ProfileResult<ProfileLock> {
        var pins = existing
        for (id in profile.packages) {
            val plan = when (val result = resolve(profile.game, PackageReference(id.provider, id.value), pins)) {
                is ProfileResult.Success -> result.value
                is ProfileResult.Failed -> return result
            }
            if (plan.requested != id) return ProfileResult.Failed(ProfileFailure.InvalidProfile)

            pins = mergePins(pins, plan)
        }

        return when (val graph = orderPackages(profile.packages, pins)) {
            is PackageGraphOutcome.Ordered -> {
                val lock = ProfileLock(profile.id, profile.game, graph.packages)
                ProfileResult.Success(lock)
            }

            is PackageGraphOutcome.Cycle ->
                ProfileResult.Failed(ProfileFailure.Planning(PlanInstallFailure.DependencyCycle(graph.packages)))

            is PackageGraphOutcome.Missing, PackageGraphOutcome.Invalid -> ProfileResult.Failed(ProfileFailure.InvalidLock)
        }
    }

    suspend fun resolve(
        game: GameId,
        reference: PackageReference,
        pins: List<LockedPackage>,
    ): ProfileResult<ChangePlan> = when (val result = planner.plan(PlanInstallRequest(game, reference, pins))) {
        is PlanInstallOutcome.Planned -> ProfileResult.Success(result.plan)
        is PlanInstallOutcome.Failed -> ProfileResult.Failed(ProfileFailure.Planning(result.failure))
    }

    fun mergePins(pins: List<LockedPackage>, plan: ChangePlan): List<LockedPackage> {
        val resolved = plan.changes.map { change ->
            when (change) {
                is Change.Install -> LockedPackage(change.id, change.name, change.artifact, change.dependencies)
            }
        }

        return (resolved + pins).distinctBy { it.id }
    }
}
