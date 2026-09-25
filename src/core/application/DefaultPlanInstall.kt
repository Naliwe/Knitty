package knitty.core.application

import knitty.core.model.*
import knitty.core.ports.GetInstallCandidate
import knitty.core.ports.InstallCandidateOutcome
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class DefaultPlanInstall(
    private val getCandidate: GetInstallCandidate,
    private val providerId: String,
    private val games: Map<GameId, ProviderGameId>,
) : PlanInstall {
    override suspend fun plan(request: PlanInstallRequest): PlanInstallOutcome {
        val game = games[request.game]
            ?: return PlanInstallOutcome.Failed(PlanInstallFailure.UnsupportedGame(request.game))
        val reference = request.packageReference
        if (reference.provider != providerId) {
            return PlanInstallOutcome.Failed(PlanInstallFailure.UnsupportedProvider(reference.provider))
        }
        if (reference.value.isBlank()) {
            return PlanInstallOutcome.Failed(PlanInstallFailure.InvalidPackageReference)
        }

        val pins = request.pins.associateBy { it.id }
        val resolved = linkedMapOf<PackageId, LockedPackage>()
        val pending = ArrayDeque<Pair<PackageReference, PackageId?>>()
        pending.add(reference to null)
        var requested = PackageId(reference.provider, reference.value)

        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (next, requiredBy) = pending.removeFirst()
            val expectedId = PackageId(next.provider, next.value)
            if (expectedId in resolved) continue
            if (resolved.size >= 500) return PlanInstallOutcome.Failed(PlanInstallFailure.DependencyLimitExceeded)

            val outcome = pins[expectedId]?.let {
                InstallCandidateOutcome.Found(InstallCandidate(it.id, it.name, it.artifact, it.dependencies))
            } ?: getCandidate.get(game, next)
            val candidate = when (outcome) {
                is InstallCandidateOutcome.Found -> outcome.candidate
                is InstallCandidateOutcome.Failed -> return failedDependency(requiredBy, expectedId, outcome.failure)
            }

            if (candidate.id.provider != providerId || candidate.id.value.isBlank() ||
                (requiredBy != null && candidate.id != expectedId) ||
                candidate.dependencies.any { it.provider != providerId || it.value.isBlank() }
            ) {
                return failedDependency(
                    requiredBy,
                    expectedId,
                    PlanInstallFailure.Provider(ProviderFailure.InvalidResponse),
                )
            }
            if (requiredBy == null) requested = candidate.id

            val artifact = candidate.artifact
                ?: return failedDependency(requiredBy, candidate.id, PlanInstallFailure.NoPublishedFile)

            // A slug may resolve to a package already pinned under its canonical ID.
            val item = pins[candidate.id]
                ?: LockedPackage(candidate.id, candidate.name, artifact, candidate.dependencies)

            resolved[item.id] = item
            item.dependencies.forEach { id ->
                if (id !in resolved) pending.add(PackageReference(id.provider, id.value) to item.id)
            }
        }

        return when (val graph = orderPackages(listOf(requested), resolved.values.toList())) {
            is PackageGraphOutcome.Ordered -> PlanInstallOutcome.Planned(
                ChangePlan(request.game, graph.packages.map { it.install() }, requested),
            )

            is PackageGraphOutcome.Cycle -> PlanInstallOutcome.Failed(PlanInstallFailure.DependencyCycle(graph.packages))
            is PackageGraphOutcome.Missing, PackageGraphOutcome.Invalid ->
                PlanInstallOutcome.Failed(PlanInstallFailure.Provider(ProviderFailure.InvalidResponse))
        }
    }

    private fun failedDependency(
        requiredBy: PackageId?,
        id: PackageId,
        failure: PlanInstallFailure,
    ): PlanInstallOutcome.Failed = PlanInstallOutcome.Failed(
        if (requiredBy == null) failure else PlanInstallFailure.DependencyUnavailable(requiredBy, id, failure),
    )
}
