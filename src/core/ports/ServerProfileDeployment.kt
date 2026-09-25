package knitty.core.ports

import knitty.core.model.*

interface ServerProfileDeployment {
    suspend fun inspect(
        game: GameId,
        target: ServerTarget,
        profileId: String,
        progress: ProgressSink = ProgressSink.None,
    ): ProfileResult<ServerInspection>

    suspend fun apply(
        plan: SyncPlan,
        progress: ProgressSink = ProgressSink.None,
        download: suspend (Change.Install, ArtifactSink) -> DownloadOutcome,
        beforeCommit: suspend () -> ProfileResult<Unit>,
        checkStopped: suspend () -> ProfileResult<Unit>,
    ): ProfileResult<Unit>
}
