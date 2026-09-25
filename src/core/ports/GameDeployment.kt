package knitty.core.ports

import knitty.core.model.ApplyOutcome
import knitty.core.model.Change
import knitty.core.model.DownloadOutcome
import knitty.core.model.GameInstallation

interface GameDeployment {
    suspend fun install(
        installation: GameInstallation,
        change: Change.Install,
        download: suspend (ArtifactSink) -> DownloadOutcome,
    ): ApplyOutcome
}
