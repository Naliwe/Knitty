package knitty.core.ports

import knitty.core.model.InstallCandidate
import knitty.core.model.PackageReference
import knitty.core.model.PlanInstallFailure
import knitty.core.model.ProviderGameId

sealed interface InstallCandidateOutcome {
    data class Found(val candidate: InstallCandidate) : InstallCandidateOutcome
    data class Failed(val failure: PlanInstallFailure) : InstallCandidateOutcome
}

fun interface GetInstallCandidate {
    suspend fun get(game: ProviderGameId, reference: PackageReference): InstallCandidateOutcome
}
