package knitty.core.model

data class PackageReference(val provider: String, val value: String)

@JvmInline
value class ArtifactId(val value: String)

data class PackageVersion(val artifactId: ArtifactId, val label: String?)

enum class ChecksumAlgorithm { MD5 }

data class Checksum(val algorithm: ChecksumAlgorithm, val value: String)

data class PackageArtifact(
    val version: PackageVersion,
    val filename: String,
    val sizeBytes: Long,
    val checksum: Checksum?,
)

data class InstallCandidate(
    val id: PackageId,
    val name: String,
    val artifact: PackageArtifact?,
    val dependencies: List<PackageId> = emptyList(),
)

sealed interface Change {
    data class Install(
        val id: PackageId,
        val name: String,
        val artifact: PackageArtifact,
        val dependencies: List<PackageId> = emptyList(),
    ) : Change
}

data class ChangePlan(val game: GameId, val changes: List<Change>, val requested: PackageId) {
    val downloadBytes: Long
        get() = changes.sumOf { change ->
            when (change) {
                is Change.Install -> change.artifact.sizeBytes
            }
        }
}

sealed interface PlanInstallFailure {
    data class UnsupportedGame(val game: GameId) : PlanInstallFailure
    data class UnsupportedProvider(val provider: String) : PlanInstallFailure
    data object InvalidPackageReference : PlanInstallFailure
    data object NoPublishedFile : PlanInstallFailure
    data object ArtifactUnavailable : PlanInstallFailure
    data class DependencyCycle(val packages: List<PackageId>) : PlanInstallFailure
    data object DependencyLimitExceeded : PlanInstallFailure
    data class DependencyUnavailable(
        val requiredBy: PackageId,
        val dependency: PackageId,
        val cause: PlanInstallFailure,
    ) : PlanInstallFailure

    data class Provider(val failure: ProviderFailure) : PlanInstallFailure
}

sealed interface PlanInstallOutcome {
    data class Planned(val plan: ChangePlan) : PlanInstallOutcome
    data class Failed(val failure: PlanInstallFailure) : PlanInstallOutcome
}
