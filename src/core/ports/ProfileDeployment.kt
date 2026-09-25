package knitty.core.ports

import knitty.core.model.*

interface ProfileDeployment {
    suspend fun inspect(installation: GameInstallation, profileId: String): ProfileResult<InstallationSnapshot>
    suspend fun apply(
        plan: SyncPlan,
        progress: ProgressSink = ProgressSink.None,
        download: suspend (Change.Install, ArtifactSink) -> DownloadOutcome,
    ): ProfileResult<Unit>
}
