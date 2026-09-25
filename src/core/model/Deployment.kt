package knitty.core.model

data class GameInstallation(
    val game: GameId,
    val directory: String,
    val modsDirectory: String,
)

sealed interface ApplyFailure {
    data object InvalidPlan : ApplyFailure
    data object InvalidSettings : ApplyFailure
    data object InstallationNotFound : ApplyFailure
    data object AmbiguousInstallation : ApplyFailure
    data object DeploymentConflict : ApplyFailure
    data object InstallationBusy : ApplyFailure
    data object DownloadFailed : ApplyFailure
    data object ArtifactChanged : ApplyFailure
    data object ChecksumMismatch : ApplyFailure
    data object InvalidArchive : ApplyFailure
    data object DependenciesChanged : ApplyFailure
    data class MissingDependency(val packageName: String, val dependencyName: String) : ApplyFailure
    data object FilesystemFailure : ApplyFailure
    data object AtomicCommitUnavailable : ApplyFailure
    data class Provider(val failure: ProviderFailure) : ApplyFailure
}

sealed interface InstallationOutcome {
    data class Found(val installation: GameInstallation) : InstallationOutcome
    data class Failed(val failure: ApplyFailure) : InstallationOutcome
}

sealed interface ApplyOutcome {
    data class Installed(val directory: String) : ApplyOutcome
    data class Failed(val failure: ApplyFailure) : ApplyOutcome
}

sealed interface DownloadOutcome {
    data object Downloaded : DownloadOutcome
    data class Failed(val failure: ApplyFailure) : DownloadOutcome
}
