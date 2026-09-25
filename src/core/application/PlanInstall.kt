package knitty.core.application

import knitty.core.model.GameId
import knitty.core.model.LockedPackage
import knitty.core.model.PackageReference
import knitty.core.model.PlanInstallOutcome

data class PlanInstallRequest(
    val game: GameId,
    val packageReference: PackageReference,
    val pins: List<LockedPackage> = emptyList(),
)

fun interface PlanInstall {
    suspend fun plan(request: PlanInstallRequest): PlanInstallOutcome
}
